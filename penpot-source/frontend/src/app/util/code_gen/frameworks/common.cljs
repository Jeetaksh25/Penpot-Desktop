;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.util.code-gen.frameworks.common
  "Framework-agnostic helpers shared by all code-export generators
  (React, Next.js, React Native, Android XML, WinUI3 XAML, Flutter).

  Design principle: every generator emits a NESTED tree of absolutely
  positioned elements. Each shape is placed relative to its parent using
  the difference of the shape's :selrect and the parent's :selrect (minus
  the parent border, exactly like CSS does). The selected root shapes are
  offset by the bounding-box origin of the selection so the exported
  component's own coordinate space starts at (0,0).

  All geometry is read from `objects` (the original, un-translated page
  objects) so roots and descendants share one consistent coordinate
  space. The translated `shapes` the panel passes in are used only for
  their :ids to locate the roots."
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.geom.matrix :as gmt]
   [app.common.types.color :as clr]
   [app.common.types.path :as path]
   [app.common.types.text :as types.text]
   [app.config :as cfg]
   [app.main.fonts :as fonts]
   [app.util.code-gen.code-connect :as cc]
   [app.util.code-gen.common :as cgc]
   [app.util.code-gen.markup-svg :as markup-svg]
   [app.util.code-gen.style-css-values :as scv]
   [cuerdas.core :as str]))

;; ---------------------------------------------------------------------------
;; Number / value formatting
;; ---------------------------------------------------------------------------

(defn fmt-num
  "Format a number for source code: up to 2 decimals, trailing zeros and
  a lone trailing dot stripped. nil -> nil."
  [x]
  (when (some? x)
    (let [s (if (number? x) (str x) (str x))]
      (-> s
          (str/replace #"\.(\d*?[1-9])0+$" ".$1")
          (str/replace #"\.$" "")))))

(defn px
  "Format a pixel value as a unitless number string (generators append
  the unit themselves: 'px' for web, 'dp' for android, nothing for RN)."
  [x]
  (fmt-num x))

(defn sanitize-identifier
  "Turn an arbitrary shape name into a safe identifier component /
  variable name."
  [name]
  (let [base (or name "Shape")
        cleaned (-> base
                    (str/replace #"[^a-zA-Z0-9]+" " ")
                    (str/trim))
        cleaned (str/replace cleaned #"\s+" "_")
        cleaned (if (re-matches #"^\d.*" cleaned) (dm/str "S_" cleaned) cleaned)]
    (if (str/blank? cleaned) "Shape" cleaned)))

(defn pascal
  "PascalCase an identifier (e.g. 'login_button' -> 'LoginButton')."
  [s]
  (let [parts (-> s (str/split #"[_\s]+") (remove str/blank?))]
    (if (empty? parts)
      "Component"
      (->> parts
           (map #(str/concat (str/upper (subs % 0 1)) (subs % 1)))
           (str/join "")))))

(defn component-name
  [shape]
  (pascal (sanitize-identifier (:name shape))))

(defn kebab-name
  "Lowercase kebab-case identifier for package / npm names."
  [s]
  (let [s (or s "export")]
    (-> (.toLowerCase (str s))
        (str/replace #"[^a-z0-9]+" "-")
        (str/replace #"^[_-]+|[_-]+$" ""))))

(defn snake-name
  "Lowercase snake_case identifier for file names (Android layouts, Dart)."
  [s]
  (let [s (or s "export")]
    (-> (.toLowerCase (str s))
        (str/replace #"[^a-z0-9]+" "_")
        (str/replace #"^[_-]+|[_-]+$" ""))))

;; ---------------------------------------------------------------------------
;; Geometry (all relative, computed from original objects)
;; ---------------------------------------------------------------------------

(defn root-originals
  "Look up the original (un-translated) shape maps for the selected roots."
  [objects shapes]
  (into [] (keep #(get objects (:id %)) shapes)))

(defn selrect
  [shape]
  (or (:selrect shape)
      (when (and (:x shape) (:y shape))
        {:x (:x shape) :y (:y shape) :width (:width shape) :height (:height shape)})))

(defn selection-origin
  "Top-left of the bounding box of the selected roots (in original
  coordinates). The exported component places its root container at this
  origin so descendants keep their relative positions."
  [roots]
  (let [rects (keep selrect roots)]
    (if (empty? rects)
      {:x 0 :y 0}
      {:x (apply min (map :x rects))
       :y (apply min (map :y rects))})))

(defn selection-size
  "Width/height of the bounding box of the selected roots."
  [roots]
  (let [rects (keep selrect roots)]
    (if (empty? rects)
      {:width 0 :height 0}
      (let [x0 (apply min (map :x rects))
            y0 (apply min (map :y rects))
            x1 (apply max (map #(+ (:x %) (:width %)) rects))
            y1 (apply max (map #(+ (:y %) (:height %)) rects))]
        {:width (- x1 x0) :height (- y1 y0)}))))

(defn parent-border-width
  "Inner border width of `parent`, so absolutely positioned children keep
  their visual position when the parent has a border (mirrors CSS padding
  box semantics)."
  [parent]
  (let [stroke (first (:strokes parent))]
    (if (and stroke
             (not= :none (:stroke-style stroke))
             (not (cgc/svg-markup? parent)))
      (d/nilv (:stroke-width stroke) 0)
      0)))

(defn rel-position
  "Position of `shape` relative to its parent (looked up in `objects`).
  For root shapes `parent` is nil and the position is given relative to
  `origin` instead."
  [objects shape origin]
  (let [sr (selrect shape)
        parent (get objects (:parent-id shape))]
    (if parent
      (let [bw (parent-border-width parent)
            psr (selrect parent)]
        {:left (- (:x sr) (:x psr) bw)
         :top  (- (:y sr) (:y psr) bw)})
      {:left (- (:x sr) (:x origin))
       :top  (- (:y sr) (:y origin))})))

(defn shape-size
  [shape]
  (let [sr (selrect shape)]
    {:width (:width sr) :height (:height sr)}))

(defn rotation
  "Rotation in degrees, or 0."
  [shape]
  (d/nilv (:rotation shape) 0))

;; ---------------------------------------------------------------------------
;; Fills / strokes / radius / shadow / opacity (reuse scv/get-value)
;; ---------------------------------------------------------------------------

(defn fill-color-rgba
  "Convert a fill/stroke color map `{:color :opacity}` to an 'rgba(r,g,b,a)'
  string. Falls back to transparent when no color."
  [{:keys [color opacity]}]
  (if (str/blank? color)
    "rgba(0,0,0,0)"
    (let [[r g b a] (clr/hex->rgba color (d/nilv opacity 1))]
      (dm/fmt "rgba(%,%,%,%)" r g b a))))

(defn gradient-stops->string
  "Format gradient stops into a framework-neutral 'color offset%' list
  string, suitable for re-serialization by each framework."
  [stops]
  (->> stops
       (map (fn [{:keys [offset color opacity]}]
              (let [[r g b a] (clr/hex->rgba (or color "#000000") (d/nilv opacity 1))]
                (dm/fmt "rgba(%,%,%,%) %" r g b a (fmt-num (* 100 (d/nilv offset 0)))))))
       (str/join ", ")))

(defn first-fill
  "The first non-hidden fill of a shape, or nil."
  [shape]
  (->> (:fills shape)
       (remove :hidden)
       first))

(defn rgba->argb-hex
  "Convert [r g b a] (0-255, a 0-1) to an '#AARRGGBB' hex string."
  [[r g b a]]
  (let [to2 (fn [x] (let [s (.toString (js/Math.round x) 16)]
                      (if (< (count s) 2) (str/concat "0" s) s)))
        alpha (js/Math.round (* (d/nilv a 1) 255))]
    (dm/str "#" (to2 alpha) (to2 r) (to2 g) (to2 b))))

(defn fill->argb-hex
  "ARGB hex string for a fill color map {:color :opacity} or rgba-string."
  ([color opacity]
   (if (str/blank? color)
     "#00000000"
     (rgba->argb-hex (clr/hex->rgba color (d/nilv opacity 1)))))
  ([{:keys [color opacity]}]
   (fill->argb-hex color opacity)))

(defn shape-background
  "Structured background for a shape: {:type :solid|:gradient|:image
  :color rgba-string :gradient {:type :linear|:radial :stops ...}
  :image url}. nil when the shape has no fill."
  [shape]
  (when-let [fill (first-fill shape)]
    (cond
      (some? (:fill-image fill))
      {:type :image
       :image (cfg/resolve-file-media (:fill-image fill))
       :opacity (:fill-opacity fill)}

      (some? (:fill-color-gradient fill))
      (let [g (:fill-color-gradient fill)]
        {:type :gradient
         :gradient-type (d/name (or (:type g) :linear))
         :stops (:stops g)
         :opacity (:fill-opacity fill)})

      (some? (:fill-color fill))
      {:type :solid
       :color (fill-color-rgba {:color (:fill-color fill)
                                :opacity (:fill-opacity fill)})}

      :else nil)))

(defn shape-stroke
  "Structured border for a shape: {:color rgba-string :style :solid|:dashed|:dotted
  :width number}. nil when none."
  [shape]
  (let [stroke (first (:strokes shape))]
    (when (and stroke (not= :none (:stroke-style stroke)))
      {:color (fill-color-rgba {:color (:stroke-color stroke)
                                :opacity (:stroke-opacity stroke)})
       :style (d/name (or (:stroke-style stroke) :solid))
       :width (d/nilv (:stroke-width stroke) 1)})))

(defn shape-radius
  "Corner radius as a vector [r1 r2 r3 r4] (TL TR BR BL) or :circle for
  fully-round circles. nil when all zero."
  [shape]
  (cond
    (cfh/circle-shape? shape) :circle
    (some? (:rx shape)) [(:rx shape) (:rx shape) (:rx shape) (:rx shape)]
    (and (:r1 shape) (:r2 shape) (:r3 shape) (:r4 shape))
    [(:r1 shape) (:r2 shape) (:r3 shape) (:r4 shape)]
    :else nil))

(defn shape-shadow
  "Structured drop-shadow list. Each: {:inner? bool :x :y :blur :spread
  :color rgba-string}. nil when none."
  [shape]
  (when-let [shadows (seq (remove :hidden (:shadow shape)))]
    (map (fn [{:keys [style offset-x offset-y blur spread color opacity]
               :or {offset-x 0 offset-y 0 blur 0 spread 0 opacity 1}}]
           {:inner? (= style :inner-shadow)
            :x offset-x :y offset-y
            :blur (d/nilv blur 0) :spread (d/nilv spread 0)
            :color (fill-color-rgba {:color color :opacity opacity})})
         shadows)))

(defn shape-opacity
  [shape]
  (let [o (:opacity shape)]
    (when (and (number? o) (< o 1)) o)))

;; ---------------------------------------------------------------------------
;; Text extraction
;; ---------------------------------------------------------------------------

(defn- text-node?
  [node]
  (and (map? node) (string? (:text node))))

(defn- paragraph-node?
  [node]
  (and (map? node) (= (:type node) "paragraph")))

(defn- collect-paragraph-text
  [paragraph]
  (->> (tree-seq map? :children paragraph)
       (filter text-node?)
       (map :text)
       (str/join "")))

(defn extract-paragraphs
  "Return the list of paragraph plain-text strings inside a text shape's
  content tree."
  [content]
  (when (map? content)
    (->> (tree-seq map? :children content)
         (filter paragraph-node?)
         (map collect-paragraph-text)
         (remove nil?))))

(defn extract-text
  "Return the full plain text of a text shape, with paragraphs separated
  by a newline."
  [shape]
  (let [paragraphs (extract-paragraphs (:content shape))]
    (str/join "\n" (or paragraphs []))))

(defn- first-text-node
  "The first leaf text node in the content tree, used to derive the base
  typography."
  [content]
  (->> (tree-seq map? :children content)
       (filter text-node?)
       first))

(defn- root-node
  [content]
  (when (and (map? content) (= (:type content) "root")) content))

(defn font-family-name
  "Resolve a font-id to a human-readable family name (best-effort),
  falling back to 'sans-serif'."
  [font-id]
  (if (str/blank? font-id)
    "sans-serif"
    (let [data (fonts/get-font-data font-id)]
      (if (map? data)
        (or (:name data) (:family data) "sans-serif")
        "sans-serif"))))

(defn extract-typography
  "Base typography map for a text shape, derived from its first text node
  + root alignment. Keys: :font-family :font-size :font-weight :font-style
  :line-height :letter-spacing :text-align :text-transform :text-decoration
  :color rgba-string :vertical-align."
  [shape]
  (let [content (:content shape)
        root (root-node content)
        node (or (first-text-node content) {})
        fills (:fills node)
        first-fill (first fills)
        color (when first-fill
                (fill-color-rgba {:color (:fill-color first-fill)
                                  :opacity (:fill-opacity first-fill)}))]
    {:font-family    (font-family-name (:font-id node))
     :font-size      (d/nilv (:font-size node) "14")
     :font-weight    (d/nilv (:font-weight node) "400")
     :font-style     (d/nilv (:font-style node) "normal")
     :line-height    (d/nilv (:line-height node) "1.2")
     :letter-spacing (d/nilv (:letter-spacing node) "0")
     :text-align     (d/nilv (:text-align node) "left")
     :text-transform (d/nilv (:text-transform node) "none")
     :text-decoration (d/nilv (:text-decoration node) "none")
     :vertical-align (d/nilv (:vertical-align root) "top")
     :color          (or color "rgba(0,0,0,1)")}))

;; ---------------------------------------------------------------------------
;; Shape classification (for the tree walk)
;; ---------------------------------------------------------------------------

(defn container?
  "A shape that contains children and should render as a nesting element."
  [shape]
  (and (seq (:shapes shape))
       (or (cfh/frame-shape? shape)
           (cfh/group-shape? shape)
           (cfh/group-like-shape? shape)
           (cfh/bool-shape? shape)
           (cfh/mask-shape? shape))))

(defn hidden?
  [shape]
  (true? (:hidden shape)))

(defn children-of
  "The child shapes (looked up from objects) of `shape`, in z-index order."
  [objects shape]
  (->> (:shapes shape)
       (map #(get objects %))
       (remove nil?)))

(defn svg-shape?
  "Whether this shape should be emitted as inline SVG (paths, bools,
  imported SVG, multi-fill / complex-stroke shapes). Mirrors
  cgc/svg-markup? but exposed for the framework generators."
  [shape]
  (cgc/svg-markup? shape))

;; ---------------------------------------------------------------------------
;; Native-SVG / PNG-raster helpers (Phase C/D)
;; ---------------------------------------------------------------------------

(defn path-d-string
  "The SVG `d`-attribute string for a path/bool shape's `:content`, in
  the shape's LOCAL coordinate space (origin at the shape's bounding-box
  top-left, exactly as Penpot's own path renderer treats it — the on-canvas
  position is carried separately by :transform). Returns \"\" when the
  shape has no path content."
  [shape]
  (let [c (:content shape)]
    (cond
      (nil? c) ""
      (path/content? c) (.toString c)
      :else (some-> (path/content c) (.toString)))))

(defn identity-transform?
  "True when the shape's :transform is nil or the identity matrix (no
  rotation/scale/skew/translation baked into the path's local frame)."
  [shape]
  (let [t (:transform shape)]
    (or (nil? t)
        (= t (gmt/matrix)))))

(defn- single-solid-fill
  "The single solid fill map of a shape, or nil if the shape has zero or
  many fills, or its only fill is a gradient/image/hidden."
  [shape]
  (let [fills (remove :hidden (:fills shape))]
    (when (= 1 (count fills))
      (let [f (first fills)]
        (when (and (some? (:fill-color f))
                   (nil? (:fill-color-gradient f))
                   (nil? (:fill-image f)))
          f)))))

(defn- single-inner-color-stroke
  "The single inner-aligned solid-color stroke map of a shape, or nil when
  the shape has zero/many strokes, a non-solid style, a gradient/image
  stroke, line caps, or a non-inner alignment (center/outer need mask
  machinery). nil strokes are also acceptable (no stroke at all)."
  [shape]
  (let [strokes (remove :hidden (:strokes shape))]
    (cond
      (empty? strokes) nil
      (not= 1 (count strokes)) nil
      :else
      (let [s (first strokes)]
        (when (and (some? (:stroke-color s))
                   (nil? (:stroke-color-gradient s))
                   (nil? (:stroke-image s))
                   (#{:solid :dashed :dotted} (:stroke-style s))
                   (= :inner (:stroke-alignment s))
                   (nil? (:stroke-cap-start s))
                   (nil? (:stroke-cap-end s)))
          s)))))

(defn simple-svg?
  "Conservative predicate: a shape that can be emitted as a lone native
  vector element (VectorDrawable / single <path>) WITHOUT masks, defs,
  filters or transforms. True ONLY for a :path shape with a single solid
  fill (or none), at most one inner-aligned solid-color stroke, no
  shadow/blur, no svg-attrs, and an identity transform. Everything else
  (bools, svg-raw, masks, multi-fill, gradients, non-inner strokes,
  shadows, transforms, multi-subpath-with-effects) is left to the PNG
  raster fallback. Defaults to false on any doubt — a false negative
  simply rasterizes, which is always correct."
  [shape]
  (and (cfh/path-shape? shape)
       (identity-transform? shape)
       (nil? (:svg-attrs shape))
       (nil? (:shadow shape))
       (nil? (:blur shape))
       (nil? (:background-blur shape))
       (not (cfh/mask-shape? shape))
       (or (single-solid-fill shape)
           (empty? (remove :hidden (:fills shape))))
       (or (single-inner-color-stroke shape)
           (empty? (remove :hidden (:strokes shape))))
       (not (str/blank? (path-d-string shape)))))

(defn solid-fill-hex
  "The `#AARRGGBB` hex of a simple-svg? shape's single solid fill, or
  `#00000000` (transparent) when it has no fill."
  [shape]
  (if-let [f (single-solid-fill shape)]
    (fill->argb-hex f)
    "#00000000"))

(defn simple-stroke
  "The `{:color #AARRGGBB :width n}` of a simple-svg? shape's single inner
  stroke, or nil when it has no stroke."
  [shape]
  (when-let [s (single-inner-color-stroke shape)]
    {:color (fill->argb-hex {:color (:stroke-color s)
                            :opacity (:stroke-opacity s)})
     :width (d/nilv (:stroke-width s) 1)}))

(defn svg-xml
  "The full `<svg ...>...</svg>` string for an svg-shape, produced by the
  same renderer the web frameworks use (markup-svg/generate-svg). This
  carries the correct viewBox, transform, fills, strokes, masks and
  gradients — so it is a faithful representation for native-SVG libraries
  (react-native-svg `SvgXml`, flutter_svg `SvgPicture.string`) even for
  shapes `simple-svg?` rejects. Returns \"\" if generation yields nothing."
  [objects shape]
  (or (markup-svg/generate-svg objects shape) ""))

;; ---------------------------------------------------------------------------
;; Component-instance hoisting (Phase E)
;; ---------------------------------------------------------------------------

;; Dynamic map of {instance-id -> comp-name}, bound by a framework's
;; `generate-project` while rendering the primary tree. When non-nil,
;; `render-shape` emits a `<CompName/>` reference for any top-level
;; instance head whose id is in the map, instead of recursing into it.
;; Default nil => no hoisting => byte-identical behavior to pre-hoisting.
(def ^:dynamic *hoist-map* nil)

(defn hoisted-instance?
  "True when `shape` is a top-level instance head that the current
  `*hoist-map*` has hoisted (so render-shape should emit a reference
  instead of recursing)."
  [shape]
  (and (true? (:component-root shape))
       (some? *hoist-map*)
       (contains? *hoist-map* (:id shape))))

(defn hoisted-name
  "The component reference name for a hoisted instance, or nil."
  [shape]
  (get *hoist-map* (:id shape)))

;; ---------------------------------------------------------------------------
;; Code Connect binding (gap #40 / P1.08)
;; ---------------------------------------------------------------------------

;; Dynamic framework-id (one of the keys of `app.util.code-gen/framework-meta`)
;; bound by each framework's `generate` / `generate-project` while rendering
;; the tree. Default nil => no Code Connect lookup happens => the emitters
;; behave byte-identically to the pre-Code-Connect output.
(def ^:dynamic *framework-type* nil)

(defn code-connect-binding
  "Resolve the Code Connect tag+props binding for `shape` on the currently
  bound `*framework-type*`. Returns `{:tag :props}` or nil (no binding or
  no framework bound → emitters fall back to generic markup)."
  [objects shape]
  (when *framework-type*
    (cc/binding-for objects *framework-type* shape)))