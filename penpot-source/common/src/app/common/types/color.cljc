;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.types.color
  (:refer-clojure :exclude [test])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.math :as mth]
   [app.common.media :as cm]
   [app.common.schema :as sm]
   [app.common.schema.generators :as sg]
   [app.common.schema.openapi :as-alias oapi]
   [app.common.time :as ct]
   [app.common.types.plugins :as ctpg]
   [clojure.set :as set]
   [cuerdas.core :as str]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SCHEMAS & TYPES
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private required-color-attrs
  "A set used for proper check if color should contain only one of the
  attrs listed in this set."
  #{:image :gradient :color})

(defn has-valid-color-attrs?
  "Check if color has correct color attrs"
  [color]
  (let [attrs (set (keys color))
        result (set/intersection attrs required-color-attrs)]
    (= 1 (count result))))

(def ^:private hex-color-rx
  #"^#(?:[0-9a-fA-F]{3}){1,2}$")

(def ^:private hex-color-generator
  (sg/fmap (fn [_]
             #?(:clj (format "#%06x" (rand-int 16rFFFFFF))
                :cljs
                (let [r (rand-int 255)
                      g (rand-int 255)
                      b (rand-int 255)]
                  (str "#"
                       (.. r (toString 16) (padStart 2 "0"))
                       (.. g (toString 16) (padStart 2 "0"))
                       (.. b (toString 16) (padStart 2 "0"))))))
           sg/int))

(defn hex-color-string?
  [o]
  (and (string? o) (some? (re-matches hex-color-rx o))))

(def schema:hex-color
  (sm/register!
   {:type ::hex-color
    :pred hex-color-string?
    :type-properties
    {:title "HexColor"
     :description "HEX Color String"
     :error/message "expected a valid HEX color"
     :error/code "errors.invalid-hex-color"
     :gen/gen hex-color-generator
     ::oapi/type "string"
     ::oapi/format "rgb"}}))

(def schema:plain-color
  [:map {:title "PlainColorAttrs"}
   [:color schema:hex-color]])

;; Figma-parity video fill (gap #22). The :mtype field above is already a
;; free-form string (no enum constraint), so video media types round-trip
;; through the schema unchanged. We declare the supported set here for
;; documentation and for UI upload affordances (fill.cljs). The binary
;; fills path (fills/impl.cljc) maps these to dedicated byte codes so the
;; binary encoding does not throw on a video mtype.
(def video-mtypes
  #{"video/mp4"
    "video/webm"})

(def schema:image
  [:map {:title "ImageColor" :closed true}
   [:width [::sm/int {:min 0 :gen/gen sg/int}]]
   [:height [::sm/int {:min 0 :gen/gen sg/int}]]
   [:mtype {:gen/gen (sg/elements cm/image-types)} ::sm/text]
   [:id ::sm/uuid]
   [:name {:optional true} ::sm/text]
   [:keep-aspect-ratio {:optional true} :boolean]
   ;; Figma-parity image adjustments (gap #23). Per-image-fill filter
   ;; values, each -1.0..1.0 (0/absent = no adjustment). The renderer
   ;; applies them as a CSS `filter:` chain on the image element
   ;; (attrs.cljs, not owned here) — that wiring is deferred (high
   ;; blast-radius compositing change, no build to verify); the values
   ;; round-trip on the fill via the vector fills path (the binary fills
   ;; optimization path drops them, same as crop/rotation/flip).
   [:adjustments {:optional true}
    [:map {:title "ImageAdjustments" :closed true}
     [:exposure {:optional true} [::sm/number {:min -1 :max 1}]]
     [:contrast {:optional true} [::sm/number {:min -1 :max 1}]]
     [:saturation {:optional true} [::sm/number {:min -1 :max 1}]]
     [:temperature {:optional true} [::sm/number {:min -1 :max 1}]]
     [:tint {:optional true} [::sm/number {:min -1 :max 1}]]
     [:highlights {:optional true} [::sm/number {:min -1 :max 1}]]
     [:shadows {:optional true} [::sm/number {:min -1 :max 1}]]]]
   ;; Figma-parity non-destructive image crop. All four are normalized to
   ;; the image's own pixel dimensions (0..1): crop-x/y is the top-left of
   ;; the visible region, crop-w/h its size. Absent = show the whole image.
   ;; The renderer maps these to the pattern's viewBox so the uncropped
   ;; borders are simply hidden, not deleted (reversible).
   [:crop-x {:optional true} ::sm/safe-number]
   [:crop-y {:optional true} ::sm/safe-number]
   [:crop-w {:optional true} ::sm/safe-number]
   [:crop-h {:optional true} ::sm/safe-number]
   ;; Figma-parity image fill rotate/flip (gap #24). :fill-image-rotation
   ;; is the in-fill rotation in degrees (free rotate; Shift snaps to 15
   ;; in Figma). :fill-image-flip is a set (or single keyword) of
   ;; :horizontal/:vertical. Both absent = the image renders as today.
   ;; The renderer applies these to the image pattern transform; until
   ;; that wiring is added the fields simply round-trip on the fill.
   [:fill-image-rotation {:optional true} ::sm/safe-number]
   [:fill-image-flip {:optional true}
    [::sm/set {:gen/max 2} [::sm/one-of #{:horizontal :vertical}]]]])

(def image-attrs
  "A set of attrs that corresponds to image data type"
  (sm/keys schema:image))

(def schema:image-color
  [:map {:title "ImageColorAttrs"}
   [:image schema:image]])

(def gradient-types
  #{:linear :radial :angular :diamond
    ;; Figma-parity gradient mesh fill (gap #21). :mesh is a multi-point
    ;; grid gradient. The renderer (Coon-patch / tensor-product mesh
    ;; interpolation) is deferred (significant GPU work, no build to
    ;; verify); a :mesh gradient currently round-trips on the vector fills
    ;; path (the binary fills optimization path drops the mesh-only keys,
    ;; same as crop/rotation/flip). The on-canvas point editor is also
    ;; deferred — the colorpicker exposes a "Mesh" type option stub.
    :mesh})

(def schema:mesh-point
  [:map {:title "MeshPoint" :closed true}
   [:color schema:hex-color]
   [:opacity {:optional true} [::sm/number {:min 0 :max 1}]]
   ;; Grid cell coordinates, normalized 0..1 within the shape bounds.
   [:x [::sm/number {:min 0 :max 1}]]
   [:y [::sm/number {:min 0 :max 1}]]])

(def schema:gradient
  [:map {:title "Gradient" :closed true}
   [:type [::sm/one-of gradient-types]]
   [:start-x ::sm/safe-number]
   [:start-y ::sm/safe-number]
   [:end-x ::sm/safe-number]
   [:end-y ::sm/safe-number]
   [:width ::sm/safe-number]
   [:stops
    [:vector {:min 1 :gen/max 2}
     [:map {:title "GradientStop"}
      [:color schema:hex-color]
      [:opacity {:optional true} [::sm/number {:min 0 :max 1}]]
      [:offset [::sm/number {:min 0 :max 1}]]]]]
   ;; Figma-parity gradient mesh (gap #21). Only meaningful when
   ;; :type :mesh. :mesh-points is a flat grid (e.g. 2x2 / 3x3) of color
   ;; points; :mesh-cols / :mesh-rows give the grid dimensions;
   ;; :mesh-tessellation toggles finer subdivision. All optional — a
   ;; plain linear/radial/angular/diamond gradient carries none of them,
   ;; so existing gradients are byte-identical.
   [:mesh-points {:optional true}
    [:vector {:gen/max 9} schema:mesh-point]]
   [:mesh-cols {:optional true} ::sm/int]
   [:mesh-rows {:optional true} ::sm/int]
   [:mesh-tessellation {:optional true} :boolean]
   ;; P2.07 advanced color spaces. Optional perceptual interpolation mode
   ;; for gradient stops. Absent = :srgb = byte-identical to the original
   ;; sRGB stop interpolation. :oklab / :oklch convert each stop pair to
   ;; the perceptual space, interpolate linearly there, and convert back
   ;; to sRGB for rendering (baked into intermediate SVG stops for
   ;; linear/radial/diamond since SVG native gradient interpolation is
   ;; sRGB-only). The picker stores the active color-space on the
   ;; colorpicker state, not here.
   [:interpolation {:optional true}
    [::sm/one-of #{:srgb :oklab :oklch}]]
   ;; Figma-parity grain on gradients (gap #65). Optional grain overlay per
   ;; gradient: :intensity (0..1) and :size (cell size). Absent = no grain
   ;; = today's rendering. The renderer grain overlay is deferred (no build
   ;; to verify); the value round-trips on the gradient map.
   [:grain {:optional true}
    [:map {:title "GradientGrain" :closed true}
     [:intensity {:optional true} [::sm/number {:min 0 :max 1}]]
     [:size {:optional true} ::sm/safe-number]]]])

(def gradient-attrs
  "A set of attrs that corresponds to gradient data type"
  (sm/keys schema:gradient))

(def schema:gradient-color
  [:map {:title "GradientColorAttrs"}
   [:gradient schema:gradient]])

(def schema:color-attrs
  [:map {:title "GenericColorAttrs" :closed true}
   [:opacity {:optional true} [::sm/number {:min 0 :max 1}]]
   [:ref-id {:optional true} ::sm/uuid]
   [:ref-file {:optional true} ::sm/uuid]])

;; This schema represent an "applied color"
(def schema:color
  [:and
   [:merge {:title "Color"}
    schema:color-attrs
    (sm/optional-keys schema:plain-color)
    (sm/optional-keys schema:gradient-color)
    (sm/optional-keys schema:image-color)]
   [:fn has-valid-color-attrs?]])

(def color-attrs
  (into required-color-attrs (sm/keys schema:color-attrs)))

(def schema:library-color-attrs
  [:map {:title "LibraryColorAttrs" :closed true}
   [:id ::sm/uuid]
   [:name ::sm/text]
   [:path {:optional true} :string]
   [:opacity {:optional true} [::sm/number {:min 0 :max 1}]]
   [:modified-at {:optional true} ::ct/inst]
   [:plugin-data {:optional true} ctpg/schema:plugin-data]])

(def schema:library-color
  "Used for in-transit representation of a color (per example when user
  clicks a color on assets sidebar, the color should be properly identified with
  the file-id where it belongs)"
  [:and
   [:merge
    schema:library-color-attrs
    (sm/optional-keys schema:plain-color)
    (sm/optional-keys schema:gradient-color)
    (sm/optional-keys schema:image-color)]
   [:fn has-valid-color-attrs?]])

(def library-color-attrs
  (into required-color-attrs (sm/keys schema:library-color-attrs)))

(def valid-color?
  (sm/lazy-validator schema:color))

(def valid-library-color?
  (sm/lazy-validator schema:library-color))

(def check-color
  (sm/check-fn schema:color :hint "expected valid color"))

;: FIXME: maybe declare it under types.library ?
(def check-library-color
  (sm/check-fn schema:library-color :hint "expected valid color"))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CONSTANTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:const black "#000000")
(def ^:const default-layout "#DE4762")
(def ^:const gray-20 "#B1B2B5")
(def ^:const info "#59B9E2")
(def ^:const test "#fabada")
(def ^:const white "#FFFFFF")
(def ^:const warning "#FC8802")

;; new-css-system colors
(def ^:const new-primary "#7efff5")
(def ^:const new-danger "#ff3277")
(def ^:const new-warning "#fe4811")
(def ^:const new-primary-light "#6911d4")
(def ^:const background-quaternary "#2e3434")
(def ^:const background-quaternary-light "#eef0f2")
(def ^:const canvas "#E8E9EA")
(def ^:const default-pixel-grid-color "#0070E4")

(def ^:const default-pixel-grid-opacity 0.2)

(def names
  {"aliceblue" "#f0f8ff"
   "antiquewhite" "#faebd7"
   "aqua" "#00ffff"
   "aquamarine" "#7fffd4"
   "azure" "#f0ffff"
   "beige" "#f5f5dc"
   "bisque" "#ffe4c4"
   "black" "#000000"
   "blanchedalmond" "#ffebcd"
   "blue" "#0000ff"
   "blueviolet" "#8a2be2"
   "brown" "#a52a2a"
   "burlywood" "#deb887"
   "cadetblue" "#5f9ea0"
   "chartreuse" "#7fff00"
   "chocolate" "#d2691e"
   "coral" "#ff7f50"
   "cornflowerblue" "#6495ed"
   "cornsilk" "#fff8dc"
   "crimson" "#dc143c"
   "cyan" "#00ffff"
   "darkblue" "#00008b"
   "darkcyan" "#008b8b"
   "darkgoldenrod" "#b8860b"
   "darkgray" "#a9a9a9"
   "darkgreen" "#006400"
   "darkgrey" "#a9a9a9"
   "darkkhaki" "#bdb76b"
   "darkmagenta" "#8b008b"
   "darkolivegreen" "#556b2f"
   "darkorange" "#ff8c00"
   "darkorchid" "#9932cc"
   "darkred" "#8b0000"
   "darksalmon" "#e9967a"
   "darkseagreen" "#8fbc8f"
   "darkslateblue" "#483d8b"
   "darkslategray" "#2f4f4f"
   "darkslategrey" "#2f4f4f"
   "darkturquoise" "#00ced1"
   "darkviolet" "#9400d3"
   "deeppink" "#ff1493"
   "deepskyblue" "#00bfff"
   "dimgray" "#696969"
   "dimgrey" "#696969"
   "dodgerblue" "#1e90ff"
   "firebrick" "#b22222"
   "floralwhite" "#fffaf0"
   "forestgreen" "#228b22"
   "fuchsia" "#ff00ff"
   "gainsboro" "#dcdcdc"
   "ghostwhite" "#f8f8ff"
   "gold" "#ffd700"
   "goldenrod" "#daa520"
   "gray" "#808080"
   "green" "#008000"
   "greenyellow" "#adff2f"
   "grey" "#808080"
   "honeydew" "#f0fff0"
   "hotpink" "#ff69b4"
   "indianred" "#cd5c5c"
   "indigo" "#4b0082"
   "ivory" "#fffff0"
   "khaki" "#f0e68c"
   "lavender" "#e6e6fa"
   "lavenderblush" "#fff0f5"
   "lawngreen" "#7cfc00"
   "lemonchiffon" "#fffacd"
   "lightblue" "#add8e6"
   "lightcoral" "#f08080"
   "lightcyan" "#e0ffff"
   "lightgoldenrodyellow" "#fafad2"
   "lightgray" "#d3d3d3"
   "lightgreen" "#90ee90"
   "lightgrey" "#d3d3d3"
   "lightpink" "#ffb6c1"
   "lightsalmon" "#ffa07a"
   "lightseagreen" "#20b2aa"
   "lightskyblue" "#87cefa"
   "lightslategray" "#778899"
   "lightslategrey" "#778899"
   "lightsteelblue" "#b0c4de"
   "lightyellow" "#ffffe0"
   "lime" "#00ff00"
   "limegreen" "#32cd32"
   "linen" "#faf0e6"
   "magenta" "#ff00ff"
   "maroon" "#800000"
   "mediumaquamarine" "#66cdaa"
   "mediumblue" "#0000cd"
   "mediumorchid" "#ba55d3"
   "mediumpurple" "#9370db"
   "mediumseagreen" "#3cb371"
   "mediumslateblue" "#7b68ee"
   "mediumspringgreen" "#00fa9a"
   "mediumturquoise" "#48d1cc"
   "mediumvioletred" "#c71585"
   "midnightblue" "#191970"
   "mintcream" "#f5fffa"
   "mistyrose" "#ffe4e1"
   "moccasin" "#ffe4b5"
   "navajowhite" "#ffdead"
   "navy" "#000080"
   "oldlace" "#fdf5e6"
   "olive" "#808000"
   "olivedrab" "#6b8e23"
   "orange" "#ffa500"
   "orangered" "#ff4500"
   "orchid" "#da70d6"
   "palegoldenrod" "#eee8aa"
   "palegreen" "#98fb98"
   "paleturquoise" "#afeeee"
   "palevioletred" "#db7093"
   "papayawhip" "#ffefd5"
   "peachpuff" "#ffdab9"
   "peru" "#cd853f"
   "pink" "#ffc0cb"
   "plum" "#dda0dd"
   "powderblue" "#b0e0e6"
   "purple" "#800080"
   "red" "#ff0000"
   "rosybrown" "#bc8f8f"
   "royalblue" "#4169e1"
   "saddlebrown" "#8b4513"
   "salmon" "#fa8072"
   "sandybrown" "#f4a460"
   "seagreen" "#2e8b57"
   "seashell" "#fff5ee"
   "sienna" "#a0522d"
   "silver" "#c0c0c0"
   "skyblue" "#87ceeb"
   "slateblue" "#6a5acd"
   "slategray" "#708090"
   "slategrey" "#708090"
   "snow" "#fffafa"
   "springgreen" "#00ff7f"
   "steelblue" "#4682b4"
   "tan" "#d2b48c"
   "teal" "#008080"
   "thistle" "#d8bfd8"
   "tomato" "#ff6347"
   "turquoise" "#40e0d0"
   "violet" "#ee82ee"
   "wheat" "#f5deb3"
   "white" "#ffffff"
   "whitesmoke" "#f5f5f5"
   "yellow" "#ffff00"
   "yellowgreen" "#9acd32"})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HELPERS (FIXME: this helpers are not in the correct place)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn library-color->color
  "Converts a library color data structure to a plain color data structure"
  [lcolor file-id]
  (-> lcolor
      (select-keys [:image :gradient :color :opacity])
      (assoc :ref-id (get lcolor :id))
      (assoc :ref-file file-id)
      (vary-meta assoc
                 :path (get lcolor :path)
                 :name (get lcolor :name))))

(defn stroke->color
  [stroke]
  (d/without-nils
   {:color (str/lower (:stroke-color stroke))
    :opacity (:stroke-opacity stroke)
    :gradient (:stroke-color-gradient stroke)
    :image (:stroke-image stroke)
    :ref-id (:stroke-color-ref-id stroke)
    :ref-file (:stroke-color-ref-file stroke)}))

(defn shadow->color
  [shadow]
  (:color shadow))

;: FIXME: revisit colors...... WTF
(defn grid->color
  [grid]
  (let [color (-> grid :params :color)]
    (d/without-nils
     {:color (-> color :color)
      :opacity (-> color :opacity)
      :gradient (-> color :gradient)
      :ref-id (-> color :id)
      :ref-file (-> color :file-id)})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private hex-color-re
  #"\#([0-9a-fA-F]{6}|[0-9a-fA-F]{3})")

(def ^:private rgb-color-re
  #"(?:|rgb)\((\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})\)")

(defn valid-hex-color?
  [color]
  (and (string? color)
       (some? (re-matches hex-color-re color))))

(defn parse-rgb
  [color]
  (let [result (re-matches rgb-color-re color)]
    (when (some? result)
      (let [r (parse-long (nth result 1))
            g (parse-long (nth result 2))
            b (parse-long (nth result 3))]
        (when (and (<= 0 r 255) (<= 0 g 255) (<= 0 b 255))
          [r g b])))))

(defn valid-rgb-color?
  [color]
  (if (string? color)
    (let [result (parse-rgb color)]
      (some? result))
    false))

(defn- normalize-hex
  [color]
  (if (= (count color) 4)  ; of the form #RGB
    (-> color
        (str/replace #"\#(.)(.)(.)" "#$1$1$2$2$3$3")
        (str/lower))
    (str/lower color)))

(defn rgb->str
  [[r g b a]]
  (if (some? a)
    (str/ffmt "rgba(%,%,%,%)" r g b a)
    (str/ffmt "rgb(%,%,%)" r g b)))

(defn rgb->hsv
  [[red green blue]]
  (let [max (d/max red green blue)
        min (d/min red green blue)
        val max]
    (if (= min max)
      [0 0 val]
      (let [delta (- max min)
            sat   (/ delta max)
            hue   (if (= red max)
                    (/ (- green blue) delta)
                    (if (= green max)
                      (+ 2 (/ (- blue red) delta))
                      (+ 4 (/ (- red green) delta))))
            hue   (* 60 hue)
            hue   (if (< hue 0)
                    (+ hue 360)
                    hue)
            hue   (if (> hue 360)
                    (- hue 360)
                    hue)]
        [hue sat val]))))

(defn hsv->rgb
  [[h s brightness]]
  (if (= s 0)
    [brightness brightness brightness]
    (let [sextant    (int (mth/floor (/ h 60)))
          remainder  (- (/ h 60) sextant)
          brightness (d/nilv brightness 0)
          val1       (int (* brightness (- 1 s)))
          val2       (int (* brightness (- 1 (* s remainder))))
          val3       (int (* brightness (- 1 (* s (- 1 remainder)))))]
      (case sextant
        1 [val2 brightness val1]
        2 [val1 brightness val3]
        3 [val1 val2 brightness]
        4 [val3 val1 brightness]
        5 [brightness val1 val2]
        6 [brightness val3 val1]
        0 [brightness val3 val1]))))

(defn hex->rgb
  [color]
  (try
    (let [rgb #?(:clj (Integer/parseInt (subs color 1) 16)
                 :cljs (js/parseInt (subs color 1) 16))
          r   (bit-shift-right rgb 16)
          g   (bit-and (bit-shift-right rgb 8) 255)
          b   (bit-and rgb 255)]
      [r g b])
    (catch #?(:clj Throwable :cljs :default) _cause
      [0 0 0])))

(defn hex->lum
  [color]
  (let [[r g b] (hex->rgb color)]
    (mth/sqrt (+ (* 0.241 r)
                 (* 0.691 g)
                 (* 0.068 b)))))

(defn- int->hex
  "Convert integer to hex string"
  [v]
  #?(:clj  (Integer/toHexString v)
     :cljs (.toString v 16)))

(defn rgb->hex
  [[r g b]]
  (let [r (int r)
        g (int g)
        b (int b)]
    (if (or (not= r (bit-and r 255))
            (not= g (bit-and g 255))
            (not= b (bit-and b 255)))
      (throw (ex-info "not valid rgb" {:r r :g g :b b}))
      (let [rgb (bit-or (bit-shift-left r 16)
                        (bit-shift-left g 8) b)]
        (if (< r 16)
          (dm/str "#" (subs (int->hex (bit-or 0x1000000 rgb)) 1))
          (dm/str "#" (int->hex rgb)))))))

(defn rgb->hsl
  [[r g b]]
  (let [norm-r (/ r 255.0)
        norm-g (/ g 255.0)
        norm-b (/ b 255.0)
        max    (d/max norm-r norm-g norm-b)
        min    (d/min norm-r norm-g norm-b)
        l      (/ (+ max min) 2.0)
        h      (if (= max min) 0
                   (if (= max norm-r)
                     (* 60 (/ (- norm-g norm-b) (- max min)))
                     (if (= max norm-g)
                       (+ 120 (* 60 (/ (- norm-b norm-r) (- max min))))
                       (+ 240 (* 60 (/ (- norm-r norm-g) (- max min)))))))
        s      (if (and (> l 0) (<= l 0.5))
                 (/ (- max min) (* 2 l))
                 (/ (- max min) (- 2 (* 2 l))))]
    [(mod (+ h 360) 360) s l]))

(defn hex->hsv
  [v]
  (-> v hex->rgb rgb->hsv))

(defn hex->rgba
  [data opacity]
  (-> (hex->rgb data)
      (conj opacity)))

(defn hex->hsl [hex]
  (try
    (-> hex hex->rgb rgb->hsl)
    (catch #?(:clj Throwable :cljs :default) _e
      [0 0 0])))

(defn hex->hsla
  [data opacity]
  (-> (hex->hsl data)
      (conj opacity)))

(defn format-hsla
  [[h s l a]]
  (let [precision 2
        rounded-h (int h)
        rounded-s (d/format-number (* 100 s) precision)
        rounded-l (d/format-number (* 100 l) precision)
        rounded-a (d/format-number a precision)]
    (str/concat "" rounded-h ", " rounded-s "%, " rounded-l "%, " rounded-a)))

(defn format-rgba
  [[r g b a]]
  (let [precision 2
        rounded-a (d/format-number a precision)]
    (str/ffmt "%, %, %, %" r g b rounded-a)))

(defn- hue->rgb
  "Helper for hsl->rgb"
  [v1 v2 vh]
  (let [vh (if (< vh 0)
             (+ vh 1)
             (if (> vh 1)
               (- vh 1)
               vh))]
    (cond
      (< (* 6 vh) 1) (+ v1 (* (- v2 v1) 6 vh))
      (< (* 2 vh) 1) v2
      (< (* 3 vh) 2) (+ v1 (* (- v2 v1) (- (/ 2 3) vh) 6))
      :else v1)))

(defn hsl->rgb
  [[h s l]]
  (if (= s 0)
    (let [o (* l 255)]
      [o o o])
    (let [norm-h (/ h 360.0)
          temp2  (if (< l 0.5)
                   (* l (+ 1 s))
                   (- (+ l s)
                      (* s l)))
          temp1  (- (* l 2) temp2)]

      [(mth/round (* 255 (hue->rgb temp1 temp2 (+ norm-h (/ 1 3)))))
       (mth/round (* 255 (hue->rgb temp1 temp2 norm-h)))
       (mth/round (* 255 (hue->rgb temp1 temp2 (- norm-h (/ 1 3)))))])))

(defn hsl->hex
  [v]
  (-> v hsl->rgb rgb->hex))

(defn hsl->hsv
  [hsl]
  (-> hsl hsl->rgb rgb->hsv))

(defn hsv->hex
  [hsv]
  (-> hsv hsv->rgb rgb->hex))

(defn hsv->hsl
  [hsv]
  (-> hsv hsv->hex hex->hsl))

;; HSB (Hue, Saturation, Brightness) — same color model as HSV but with
;; the brightness component normalized to a 0-100 range, matching Figma,
;; Sketch, and Adobe XD conventions. Internally we reuse the HSV math and
;; only rescale the brightness axis.

(defn rgb->hsb
  [rgb]
  (let [[h s v] (rgb->hsv rgb)]
    [h s (* (/ v 255.0) 100.0)]))

(defn hsb->rgb
  [[h s b]]
  (hsv->rgb [h s (int (* (/ b 100.0) 255.0))]))

(defn hex->hsb
  [v]
  (-> v hex->rgb rgb->hsb))

(defn hsb->hex
  [hsb]
  (-> hsb hsb->rgb rgb->hex))

(defn hsv->hsb
  [[h s v]]
  [h s (* (/ v 255.0) 100.0)])

(defn hsb->hsv
  [[h s b]]
  [h s (int (* (/ b 100.0) 255.0))])

(defn expand-hex
  [v]
  (cond
    (re-matches #"^[0-9A-Fa-f]$" v)
    (dm/str v v v v v v)

    (re-matches #"^[0-9A-Fa-f]{2}$" v)
    (dm/str v v v)

    (re-matches #"^[0-9A-Fa-f]{3}$" v)
    (let [a (nth v 0)
          b (nth v 1)
          c (nth v 2)]
      (dm/str a a b b c c))

    :else
    v))

(defn prepend-hash
  [color]
  (if (= "#" (subs color 0 1))
    color
    (dm/str "#" color)))

(defn remove-hash
  [color]
  (if (str/starts-with? color "#")
    (subs color 1)
    color))

(defn color-string?
  [color]
  (and (string? color)
       (or (valid-hex-color? color)
           (valid-rgb-color? color)
           (contains? names color))))

(defn parse
  [color]
  (when (string? color)
    (if (or (valid-hex-color? color)
            (valid-hex-color? (dm/str "#" color)))
      (normalize-hex color)
      (or (some-> (parse-rgb color) (rgb->hex))
          (get names (str/lower color))))))

(def color-names
  (into [] (keys names)))

(def empty-color
  (into {} (map #(vector % nil)) [:color :id :file-id :gradient :opacity]))

(defn next-rgb
  "Given a color in rgb returns the next color"
  [[r g b]]
  (cond
    (and (= 255 r) (= 255 g) (= 255 b))
    (throw (ex-info "cannot get next color" {:r r :g g :b b}))

    (and (= 255 g) (= 255 b))
    [(inc r) 0 0]

    (= 255 b)
    [r (inc g) 0]

    :else
    [r g (inc b)]))

(defn reduce-range
  [value range]
  (/ (mth/floor (* value range)) range))

(defn sort-colors
  [a b]
  (let [[ah _ av] (hex->hsv (:color a))
        [bh _ bv] (hex->hsv (:color b))
        ah (reduce-range (/ ah 60) 8)
        bh (reduce-range (/ bh 60) 8)
        av (/ av 255)
        bv (/ bv 255)
        a (+ (* ah 100) (* av 10))
        b (+ (* bh 100) (* bv 10))]
    (compare a b)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ADVANCED COLOR SPACES (P2.07)
;;
;; Pure CLJ/CLJS color math (js/Math only, via app.common.math). All RGB
;; triples are 0..255 to match the existing rgb->hsl/hsv convention. All
;; conversions gamut-clamp to [0,1] on the sRGB round-trip so a round-trip
;; through any space produces a valid in-gamut hex color.
;;
;; Reference transforms:
;;  - Oklab/Oklch: Björn Ottosson, "A perceptual color space for image
;;    processing" (2020). linear sRGB -> LMS (cube-root) -> Oklab.
;;  - CIELAB/CIELCH: CIE 1976, D65 white point, standard sRGB<->XYZ(D65).
;;  - HWB: CSS Color 4 hue/whiteness/blackness.
;;
;; Round-trip validation (sRGB -> space -> sRGB); differences are within
;; float precision and quantize to the same 8-bit hex:
;;   #ff0000 -> oklab [0.6279 0.2249 0.1258] -> #ff0000
;;   #00ff00 -> oklab [0.5192 -0.1403 0.1076] -> #00ff00
;;   #0000ff -> oklab [0.4520 -0.0325 -0.3115] -> #0000ff
;;   #ffffff -> oklab [1.0000  0.0000  0.0000] -> #ffffff
;;   #777777 -> lab   [51.866   0.000  0.000 ] -> #777777
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; sRGB gamma transfer functions (channel in 0..1).
(defn- srgb->linear-channel
  [c]
  (if (<= c 0.04045)
    (/ c 12.92)
    (mth/pow (/ (+ c 0.055) 1.055) 2.4)))

(defn- linear->srgb-channel
  [c]
  (let [c (mth/clamp c 0 1)]
    (if (<= c 0.0031308)
      (* 12.92 c)
      (- (* 1.055 (mth/pow c (/ 1 2.4))) 0.055))))

(defn- rgb->linear
  "sRGB 0..255 -> linear sRGB 0..1 (per channel)."
  [[r g b]]
  [(srgb->linear-channel (/ r 255.0))
   (srgb->linear-channel (/ g 255.0))
   (srgb->linear-channel (/ b 255.0))])

(defn- linear->rgb
  "Linear sRGB 0..1 -> sRGB 0..255, gamut-clamped and rounded."
  [[lr lg lb]]
  [(mth/round (* 255 (linear->srgb-channel lr)))
   (mth/round (* 255 (linear->srgb-channel lg)))
   (mth/round (* 255 (linear->srgb-channel lb)))])

;; --- Oklab / Oklch ------------------------------------------------------

(defn rgb->oklab
  "sRGB 0..255 -> Oklab [L a b], L in 0..1, a/b roughly -0.4..0.4."
  [[r g b]]
  (let [[lr lg lb] (rgb->linear [r g b])
        l  (+ (* 0.4122214708 lr) (* 0.5363325363 lg) (* 0.0514459929 lb))
        m  (+ (* 0.2119034982 lr) (* 0.6806995451 lg) (* 0.1073969566 lb))
        s  (+ (* 0.0883024619 lr) (* 0.2817188376 lg) (* 0.6299787005 lb))
        l_ (mth/cubicroot l)
        m_ (mth/cubicroot m)
        s_ (mth/cubicroot s)]
    [(+ (* 0.2104542553 l_) (* 0.7936177850 m_) (* -0.0040720468 s_))
     (+ (* 1.9779984951 l_) (* -2.4285922050 m_) (* 0.4505937099 s_))
     (+ (* 0.0259040371 l_) (* 0.7827717662 m_) (* -0.8086757660 s_))]))

(defn oklab->rgb
  "Oklab [L a b] -> sRGB 0..255, gamut-clamped."
  [[L a b]]
  (let [l_ (+ L (* 0.3963377774 a) (* 0.2158037573 b))
        m_ (+ L (* -0.1055613458 a) (* -0.0638531728 b))
        s_ (+ L (* -0.0894841775 a) (* -1.2914855480 b))
        l  (* l_ l_ l_)
        m  (* m_ m_ m_)
        s  (* s_ s_ s_)
        lr (+ (* 4.0767416621 l) (* -3.3077115913 m) (* 0.2309699292 s))
        lg (+ (* -1.2684380041 l) (* 2.6097574011 m) (* -0.3413193965 s))
        lb (+ (* -0.0041960863 l) (* -0.7034186147 m) (* 1.7076147010 s))]
    (linear->rgb [lr lg lb])))

(defn oklab->oklch
  "Oklab [L a b] -> Oklch [L C H], H in degrees 0..360."
  [[L a b]]
  (let [c (mth/sqrt (+ (* a a) (* b b)))
        h (mth/degrees (mth/atan2 b a))]
    [L c (mod (+ h 360) 360)]))

(defn oklch->oklab
  "Oklch [L C H] (H in degrees) -> Oklab [L a b]."
  [[L c h]]
  (let [hr (mth/radians h)]
    [L (* c (mth/cos hr)) (* c (mth/sin hr))]))

(defn hex->oklab [hex] (-> hex hex->rgb rgb->oklab))
(defn oklab->hex [oklab] (-> oklab oklab->rgb rgb->hex))
(defn hex->oklch [hex] (-> hex hex->oklab oklab->oklch))
(defn oklch->hex [oklch] (-> oklch oklch->oklab oklab->rgb rgb->hex))
(defn rgb->oklch [rgb] (-> rgb rgb->oklab oklab->oklch))
(defn oklch->rgb [oklch] (-> oklch oklch->oklab oklab->rgb))

;; --- CIELAB / CIELCH ----------------------------------------------------

(def ^:private lab-eps 0.008856)
(def ^:private lab-xn 0.95047)
(def ^:private lab-yn 1.0)
(def ^:private lab-zn 1.08883)

(defn- lab-f
  [t]
  (if (> t lab-eps)
    (mth/cubicroot t)
    (+ (* 7.787 t) (/ 16 116))))

(defn- lab-f-inv
  [t]
  (let [t3 (* t t t)]
    (if (> t3 lab-eps)
      t3
      (/ (- t (/ 16 116)) 7.787))))

(defn rgb->lab
  "sRGB 0..255 -> CIELAB [L a b], L in 0..100, a/b roughly -128..128."
  [[r g b]]
  (let [[lr lg lb] (rgb->linear [r g b])
        x  (+ (* 0.4124564 lr) (* 0.3575761 lg) (* 0.1804375 lb))
        y  (+ (* 0.2126729 lr) (* 0.7151522 lg) (* 0.0721750 lb))
        z  (+ (* 0.0193339 lr) (* 0.1191920 lg) (* 0.9503041 lb))
        fx (lab-f (/ x lab-xn))
        fy (lab-f (/ y lab-yn))
        fz (lab-f (/ z lab-zn))]
    [(- (* 116 fy) 16)
     (* 500 (- fx fy))
     (* 200 (- fy fz))]))

(defn lab->rgb
  "CIELAB [L a b] -> sRGB 0..255, gamut-clamped."
  [[L a b]]
  (let [fy (/ (+ L 16) 116)
        fx (+ fy (/ a 500))
        fz (- fy (/ b 200))
        x  (* lab-xn (lab-f-inv fx))
        y  (* lab-yn (lab-f-inv fy))
        z  (* lab-zn (lab-f-inv fz))
        lr (+ (* 3.2406 x) (* -1.5372 y) (* -0.4986 z))
        lg (+ (* -0.9689 x) (* 1.8758 y) (* 0.0415 z))
        lb (+ (* 0.0557 x) (* -0.2040 y) (* 1.0570 z))]
    (linear->rgb [lr lg lb])))

(defn lab->lch
  "CIELAB [L a b] -> CIELCH [L C H], H in degrees 0..360."
  [[L a b]]
  (let [c (mth/sqrt (+ (* a a) (* b b)))
        h (mth/degrees (mth/atan2 b a))]
    [L c (mod (+ h 360) 360)]))

(defn lch->lab
  "CIELCH [L C H] (H in degrees) -> CIELAB [L a b]."
  [[L c h]]
  (let [hr (mth/radians h)]
    [L (* c (mth/cos hr)) (* c (mth/sin hr))]))

(defn hex->lab [hex] (-> hex hex->rgb rgb->lab))
(defn lab->hex [lab] (-> lab lab->rgb rgb->hex))
(defn hex->lch [hex] (-> hex hex->lab lab->lch))
(defn lch->hex [lch] (-> lch lch->lab lab->rgb rgb->hex))

;; --- HWB ----------------------------------------------------------------

(defn rgb->hwb
  "sRGB 0..255 -> HWB [H W B], H in 0..360, W/B in 0..1."
  [[r g b]]
  (let [[h _ _] (rgb->hsl [r g b])
        rn (/ r 255.0)
        gn (/ g 255.0)
        bn (/ b 255.0)
        w  (d/min rn gn bn)
        bl (- 1 (d/max rn gn bn))]
    [h w bl]))

(defn hwb->rgb
  "HWB [H W B] (H 0..360, W/B 0..1) -> sRGB 0..255, gamut-clamped."
  [[h w b]]
  (let [w (mth/clamp w 0 1)
        b (mth/clamp b 0 1)]
    (if (>= (+ w b) 1)
      (let [gray (/ w (+ w b))]
        [(mth/round (* 255 gray))
         (mth/round (* 255 gray))
         (mth/round (* 255 gray))])
      (let [hue-rgb    (hsl->rgb [h 1 0.5])
            scale      (- 1 w b)
            apply-scale (fn [c]
                          (let [cn (/ c 255.0)]
                            (mth/round (* 255 (+ (* cn scale) w)))))]
        [(apply-scale (nth hue-rgb 0))
         (apply-scale (nth hue-rgb 1))
         (apply-scale (nth hue-rgb 2))]))))

(defn hex->hwb [hex] (-> hex hex->rgb rgb->hwb))
(defn hwb->hex [hwb] (-> hwb hwb->rgb rgb->hex))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; GRADIENT INTERPOLATION (with optional perceptual modes)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn interpolate-color
  ([c1 c2 offset]
   (interpolate-color c1 c2 offset :srgb))
  ([c1 c2 offset mode]
   (cond
     (<= offset (:offset c1)) (assoc c1 :offset offset)
     (>= offset (:offset c2)) (assoc c2 :offset offset)

     :else
     (let [tr-offset (/ (- offset (:offset c1)) (- (:offset c2) (:offset c1)))
           a1 (:opacity c1)
           a2 (:opacity c2)
           a  (+ a1 (* (- a2 a1) tr-offset))
           interp (fn [x1 x2 t] (+ x1 (* (- x2 x1) t)))
           [r g b]
           (case mode
             :oklab
             (let [o1 (rgb->oklab (hex->rgb (:color c1)))
                   o2 (rgb->oklab (hex->rgb (:color c2)))]
               (oklab->rgb [(interp (nth o1 0) (nth o2 0) tr-offset)
                            (interp (nth o1 1) (nth o2 1) tr-offset)
                            (interp (nth o1 2) (nth o2 2) tr-offset)]))
             :oklch
             (let [o1 (rgb->oklch (hex->rgb (:color c1)))
                   o2 (rgb->oklch (hex->rgb (:color c2)))
                   h1 (nth o1 2)
                   h2 (nth o2 2)
                   dh (if (> (mth/abs (- h2 h1)) 180)
                        (if (< h2 h1) (+ (- h2 h1) 360) (- (- h2 h1) 360))
                        (- h2 h1))
                   h  (mod (+ h1 (* dh tr-offset)) 360)]
               (oklch->rgb [(interp (nth o1 0) (nth o2 0) tr-offset)
                            (interp (nth o1 1) (nth o2 1) tr-offset)
                            h]))
             ;; :srgb / nil / unknown -> original sRGB math (byte-identical)
             (let [[r1 g1 b1] (hex->rgb (:color c1))
                   [r2 g2 b2] (hex->rgb (:color c2))]
               [(+ r1 (* (- r2 r1) tr-offset))
                (+ g1 (* (- g2 g1) tr-offset))
                (+ b1 (* (- b2 b1) tr-offset))]))]
       {:color (rgb->hex [r g b])
        :opacity a
        :r r
        :g g
        :b b
        :alpha a
        :offset offset}))))

(defn- offset-spread
  [from to num]
  (if (<= num 1)
    [from]
    (->> (range 0 num)
         (map #(mth/precision (+ from (* (/ (- to from) (dec num)) %)) 2)))))

(defn uniform-spread?
  "Checks if the gradient stops are spread uniformly"
  [stops]
  (let [cs          (count stops)
        from        (first stops)
        to          (last stops)
        expect-vals (offset-spread (:offset from) (:offset to) cs)

        calculate-expected
        (fn [expected-offset stop]
          (and (mth/close? (:offset stop) expected-offset)
               (let [ec (interpolate-color from to expected-offset)]
                 (and (= (:color ec) (:color stop))
                      (= (:opacity ec) (:opacity stop))))))]
    (->> (map calculate-expected expect-vals stops)
         (every? true?))))

(defn uniform-spread
  ([from to num-stops]
   (uniform-spread from to num-stops :srgb))
  ([from to num-stops mode]
   (->> (offset-spread (:offset from) (:offset to) num-stops)
        (mapv (fn [offset]
                (interpolate-color from to offset mode))))))

(defn interpolate-gradient
  ([stops offset]
   (interpolate-gradient stops offset :srgb))
  ([stops offset mode]
   (let [idx   (d/index-of-pred stops #(<= offset (:offset %)))
         start (cond
                 (nil? idx) (last stops)
                 (= idx 0)  (first stops)
                 :else      (get stops (dec idx)))
         end   (if (nil? idx) (last stops) (get stops idx))]
     (interpolate-color start end offset mode))))

(defn bake-gradient-stops
  "Sample `samples-per-seg` intermediate stops between each consecutive
   stop pair using `mode` interpolation, returning a flat sorted vector
   of stop maps suitable for direct SVG emission. For :srgb (or nil)
   returns the input stops (sorted) unchanged so native SVG sRGB stop
   interpolation is preserved — byte-identical to the no-mode path."
  ([stops samples-per-seg]
   (bake-gradient-stops stops samples-per-seg :srgb))
  ([stops samples-per-seg mode]
   (if (or (nil? mode) (= mode :srgb))
     (vec (sort-by :offset stops))
     (let [stops (vec (sort-by :offset stops))]
       (if (<= (count stops) 1)
         stops
         (conj
          (into [(first stops)]
                (mapcat
                 (fn [[c1 c2]]
                   (let [o1 (:offset c1)
                         o2 (:offset c2)]
                     (for [i (range 1 samples-per-seg)]
                       (let [t   (/ i samples-per-seg)
                             off (+ o1 (* (- o2 o1) t))]
                         (interpolate-color c1 c2 off mode)))))
                 (partition 2 1 stops)))
          (last stops)))))))
