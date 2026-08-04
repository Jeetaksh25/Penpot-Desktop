;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.file-import
  "P1.22 — Import .sketch and .fig files.

  Thin invoke wrappers around the Rust `import_sketch` / `import_figma`
  Tauri commands (see `src-tauri/src/file_import.rs`), plus the pure
  converters that turn a Sketch ZIP parse / a Figma document JSON into a
  Penpot DesignSpec consumed by the existing `design-gen/apply-design-spec`
  (reused — no new shape-creation path).

  Shape of the Rust `import_sketch` result:
    {:pages [{:name \"Page 1\" :layers [<raw sketch layer>, …]} …]
     :images {\"<id>\" \"<base64-png>\"}}

  The Figma command returns the raw Figma REST document JSON
  (`{\"document\": {…}}`).

  Figma token gating: the key is held in browser localStorage under
  `ovion.figma-token` (mirroring the Pexels key pattern in
  `stock_assets.cljs`). When absent/empty the Rust command returns the
  sentinel `\"figma-token-missing\"`; HTTP 401/403 returns
  `\"figma-token-invalid\"` — the UI matches these exactly to render the
  right empty state. No call is made when the key is missing.

  Byte-identical-when-inactive: import is a user action (File menu / import
  dialog). No import = no invoke = no change to the file. This namespace is
  purely additive.

  Conversion is pragmatic: the common Sketch/Figma layer types (artboard /
  frame, group, rectangle, text, image) map to DesignSpec shapes; exotic
  layers (vectors, booleans, slices, …) are skipped (never crash)."
  (:require
   ["@tauri-apps/api/core" :refer [invoke]]
   [app.common.data.macros :as dm]
   [app.main.data.notifications :as ntf]
   [app.main.data.workspace.design-gen :as design-gen]
   [app.main.repo :as rp]
   [app.util.i18n :as i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]
   [promesa.core :as p]))

;; ── Figma token (browser localStorage, user-supplied) ────────────────────────

(def ^:private figma-token-storage-key "ovion.figma-token")

(defn load-figma-token
  "Read the saved Figma API token from localStorage. Returns a string
  (empty when unset or unavailable — the Figma tab then shows the
  key-missing empty state). Nil-safe against browsers that throw on
  localStorage access (private mode)."
  []
  (try
    (or (.getItem js/localStorage figma-token-storage-key) "")
    (catch :default _ "")))

(defn save-figma-token
  "Persist `token` to localStorage and return it. Empty string clears the
  token (re-enables the key-missing empty state)."
  [token]
  (try
    (if (str/blank? token)
      (.removeItem js/localStorage figma-token-storage-key)
      (.setItem js/localStorage figma-token-storage-key token))
    (catch :default _))
  token)

(defn figma-token-set?
  "True when a non-blank Figma token is saved in localStorage."
  []
  (not (str/blank? (load-figma-token))))

;; ── Invoke wrappers ──────────────────────────────────────────────────────────

(defn import-sketch
  "Invoke the Rust `import_sketch` command on `path` (a .sketch file
  path returned by the Tauri open dialog). Returns a promesa promise
  resolving to the normalized Sketch JSON (`{:pages … :images …}`). On
  error the promise rejects with the Rust error string."
  [path]
  (invoke "import_sketch" #js {:path path}))

(defn import-figma
  "Invoke the Rust `import_figma` command with `file-key` + `figma-token`.
  Returns a promesa promise resolving to the raw Figma document JSON.
  Rejects with `\"figma-token-missing\"` / `\"figma-token-invalid\"` /
  `\"figma-file-key-missing\"` sentinels the UI matches exactly."
  ([file-key]
   (import-figma file-key (load-figma-token)))
  ([file-key figma-token]
   (invoke "import_figma" #js {:fileKey file-key :figmaToken figma-token})))

;; ── Color helpers (Sketch CSS rgba / Figma 0..1 floats → hex) ─────────────────

(defn- clamp-byte [n]
  (max 0 (min 255 (int (Math/round n)))))

(defn- ->hex2 [n]
  (let [s (.toString (clamp-byte n) 16)]
    (if (< (count s) 2) (str "0" s) s)))

(defn- rgb->hex [r g b]
  (str "#" (->hex2 r) (->hex2 g) (->hex2 b)))

(defn- parse-sketch-color
  "Sketch stores fill colors as CSS `rgba(r,g,b,a)` (or `#rrggbb`)
  strings. Returns `{:fill-color \"#rrggbb\" :fill-opacity a}` or nil."
  [s]
  (when (string? s)
    (cond
      (str/starts-with? s "rgba")
      ;; rgba(r, g, b, a)
      (let [inner (subs s (inc (str/index-of s "(")) (str/index-of s ")"))
            parts (str/split inner ",")
            n (count parts)]
        (when (>= n 3)
          (let [r (js/parseFloat (str/trim (nth parts 0)))
                g (js/parseFloat (str/trim (nth parts 1)))
                b (js/parseFloat (str/trim (nth parts 2)))
                a (if (>= n 4) (js/parseFloat (str/trim (nth parts 3))) 1)]
            {:fill-color (rgb->hex r g b)
             :fill-opacity (if (js/isNaN a) 1 (max 0 (min 1 a)))})))

      (str/starts-with? s "rgb")
      (let [inner (subs s (inc (str/index-of s "(")) (str/index-of s ")"))
            parts (str/split inner ",")]
        (when (>= (count parts) 3)
          {:fill-color (rgb->hex (js/parseFloat (str/trim (nth parts 0)))
                                  (js/parseFloat (str/trim (nth parts 1)))
                                  (js/parseFloat (str/trim (nth parts 2))))
           :fill-opacity 1}))

      (str/starts-with? s "#")
      {:fill-color s :fill-opacity 1}

      :else nil)))

(defn- figma-color->fill
  "A Figma fill object: `{color {r g b a 0..1}, opacity, type \"SOLID\"}`.
  Returns `{:fill-color :fill-opacity}` or nil for non-solid / image fills."
  [fill]
  (when (map? fill)
    (let [type (get fill :type (get fill "type"))
          color (get fill :color (get fill "color"))]
      (cond
        (and (= type "SOLID") (map? color))
        (let [r (* (or (get color :r (get color "r")) 0) 255)
              g (* (or (get color :g (get color "g")) 0) 255)
              b (* (or (get color :b (get color "b")) 0) 255)
              a (or (get color :a (get color "a")) 1)
              op (or (get fill :opacity (get fill "opacity")) 1)]
          {:fill-color (rgb->hex r g b)
           :fill-opacity (max 0 (min 1 (* a op)))})
        ;; Image fills are handled separately as :image shapes; not a fill.
        :else nil))))

;; ── id minting ───────────────────────────────────────────────────────────────

(defn- next-id!
  "Mint a unique string id from a volatile counter. DesignSpec ids only
  need to be unique within one spec (they map to real UUIDs in
  `spec->shape-tree`)."
  [c]
  (vswap! c inc)
  (str "i" @c))

;; ── Sketch layer → spec shape ────────────────────────────────────────────────

(defn- sketch-frame-rect
  "Sketch layer frame: `{x, y, width, height}`."
  [layer]
  (let [frame (or (get layer :frame) (get layer "frame") {})]
    {:x (or (get frame :x (get frame "x")) 0)
     :y (or (get frame :y (get frame "y")) 0)
     :width (or (get frame :width (get frame "width")) 100)
     :height (or (get frame :height (get frame "height")) 100)}))

(defn- get-k
  "Get `k` from `m` trying both the keyword and string form (works whether
  or not the map was keywordized by `js->clj`)."
  [m k]
  (when (map? m)
    (or (get m k) (get m (name k)))))

(defn- sketch-fills
  "Sketch `style.fills` → a Penpot fills vector. Only the first solid fill
  is used (pragmatic). Returns `[{:fill-color :fill-opacity}]`."
  [layer]
  (let [style (or (get-k layer :style) {})
        fills (or (get-k style :fills) [])
        solid (->> fills
                   (filter #(let [ft (get-k % :fillType)]
                              (or (nil? ft) (= ft 0))))
                   first)
        color (when solid (get-k solid :color))]
    (if-let [parsed (when color (parse-sketch-color color))]
      [parsed]
      [{:fill-color "#cccccc" :fill-opacity 1}])))

;; Sketch `_class` values we recognize.
(def ^:private sketch-artboard?
  #{"artboard" "symbolMaster"})

(def ^:private sketch-group?
  #{"group" "symbolMaster"})

(def ^:private sketch-rect?
  #{"rectangle" "shape" "shapeGroup" "oval" "star" "polygon"})

(defn- sketch-text-content
  "Sketch text content lives in `attributedString.string` (preferred) or
  `name`."
  [layer]
  (or (-> layer (get-k :attributedString) (get-k :string))
      (get-k layer :name)
      ""))

(defn- convert-sketch-layer
  "Convert one Sketch layer to a DesignSpec shape map, or nil to skip.
  `images` is the `{id -> base64}` map from the Rust parse."
  [layer images c]
  (when (map? layer)
    (let [class (or (get layer :_class) (get layer "_class")
                    (get layer :class) (get layer "class") "")
          is-artboard (or (sketch-artboard? class)
                          (true? (or (get layer :isArtboard) (get layer "isArtboard"))))
          rect (sketch-frame-rect layer)
          id (next-id! c)
          name (or (get layer :name) (get layer "name") "Layer")]
      (cond
        ;; Image layer — Sketch `image` fills reference `image.ref.id`.
        (or (= class "image")
            (some #(let [ft (get-k % :fillType)]
                     (= ft 4))   ; fillType 4 = image in Sketch
                  (or (-> layer (get-k :style) (get-k :fills)) [])))
        (let [ref (-> layer (get-k :image) (get-k :ref))
              ref-id (when ref (str ref))
              img-data (when ref-id (get images ref-id))]
          (when img-data
            (merge {:id id :type "image" :name name}
                   rect
                   {:image-id ref-id :image-data img-data})))

        ;; Artboard / symbolMaster → group-like container; emitted as a
        ;; group shape with children (top-level artboards are wrapped into
        ;; frames by `convert-sketch->spec`).
        (or is-artboard (sketch-group? class))
        (let [children (or (get layer :layers) (get layer "layers") [])
              child-specs (into [] (keep #(convert-sketch-layer % images c)) children)]
          (merge {:id id :type "group" :name name}
                 rect
                 {:shapes child-specs}))

        ;; Rectangle / shape / oval / star / polygon → rect shape.
        (sketch-rect? class)
        (merge {:id id :type "rect" :name name}
               rect
               {:fills (sketch-fills layer)})

        ;; Text → text shape.
        (= class "text")
        (merge {:id id :type "text" :name name}
               rect
               {:fills (sketch-fills layer)
                :content (sketch-text-content layer)
                :font-size (or (-> layer (get-k :style) (get-k :fontSize))
                               (get-k layer :fontSize) 14)})

        ;; Exotic / unknown layers — skip rather than crash.
        :else nil))))

(defn convert-sketch->spec
  "Pure: walk a normalized Sketch JSON (`{:pages … :images …}`) and emit a
  Penpot DesignSpec. Each Sketch artboard becomes a DesignSpec frame;
  top-level non-artboard page layers are wrapped in a single
  'Imported Page' frame. Exotic layer types are skipped."
  [sketch-json]
  (let [sketch (js->clj sketch-json :keywordize-keys true)
        pages (or (get sketch :pages) [])
        ;; `js->clj :keywordize-keys` keywordizes map keys, but Sketch image
        ;; ref ids are UUID *strings* looked up by the string `image.ref`.
        ;; Normalize every image key back to a string so the lookup works
        ;; regardless of how the JS object was deserialized.
        images-raw (or (get sketch :images) {})
        images (reduce-kv (fn [m k v] (assoc m (name k) v)) {} images-raw)
        c (volatile! 0)
        frames
        (into []
              (mapcat
               (fn [page]
                 (let [page-name (or (get page :name) "Page")
                       layers (or (get page :layers) [])
                       converted (into [] (keep #(convert-sketch-layer % images c)) layers)
                       artboards (filter #(= (get % :type) "group") converted)
                       non-artboards (vec (remove #(= (get % :type) "group") converted))]
                   (cond
                     ;; Artboards present → each becomes its own frame.
                     (seq artboards)
                     (for [ab (seq artboards)]
                       (let [fid (next-id! c)]
                         {:id fid
                          :name (str page-name " — " (get ab :name "Artboard"))
                          :x (get ab :x 0)
                          :y (get ab :y 0)
                          :width (get ab :width 1440)
                          :height (get ab :height 900)
                          :fills [{:fill-color "#ffffff" :fill-opacity 1}]
                          :shapes (get ab :shapes [])}))

                     ;; No artboards → wrap all top-level layers in one frame.
                     (seq non-artboards)
                     [(let [fid (next-id! c)]
                        {:id fid
                         :name (str page-name " (imported)")
                         :x 0 :y 0 :width 1440 :height 900
                         :fills [{:fill-color "#ffffff" :fill-opacity 1}]
                         :shapes non-artboards})]

                     :else []))))
              pages)]
    {:target "new-board" :frames (vec frames)}))

;; ── Figma node → spec shape ──────────────────────────────────────────────────

(defn- figma-rect
  "Figma `absoluteBoundingBox` → Penpot rect."
  [node]
  (let [bb (or (get node :absoluteBoundingBox) (get node "absoluteBoundingBox") {})]
    {:x (or (get bb :x (get bb "x")) 0)
     :y (or (get bb :y (get bb "y")) 0)
     :width (or (get bb :width (get bb "width")) 100)
     :height (or (get bb :height (get bb "height")) 100)}))

(defn- figma-solid-fills
  "Figma `fills` → the first SOLID fill as a 1-element vector, or nil when
  the node has no solid fill (so frames default to white via `build-frame`
  and image fills are handled separately as :image shapes)."
  [node]
  (let [fills (or (get node :fills) (get node "fills") [])
        solid (->> fills (map #(if (map? %) (figma-color->fill %) nil)) (keep identity) first)]
    (when solid [solid])))

(defn- figma-fills
  "Figma `fills` → a Penpot fills vector. Falls back to neutral grey for
  leaf shapes; for frames use `figma-solid-fills` (nil → white default)."
  [node]
  (or (figma-solid-fills node)
      [{:fill-color "#cccccc" :fill-opacity 1}]))

(def ^:private figma-frame-types
  #{"FRAME" "COMPONENT" "COMPONENT_SET" "INSTANCE" "CANVAS" "SECTION"})

(def ^:private figma-group-type "GROUP")

(def ^:private figma-rect-types
  #{"RECTANGLE" "ELLIPSE" "LINE" "VECTOR" "STAR" "POLYGON" "REGULAR_POLYGON"})

(defn- convert-figma-node
  "Convert one Figma node to a DesignSpec shape, or nil to skip."
  [node c]
  (when (map? node)
    (let [type (or (get node :type) (get node "type") "")
          rect (figma-rect node)
          id (next-id! c)
          name (or (get node :name) (get node "name") "Node")
          children (or (get node :children) (get node "children") [])
          ;; Image fill detection (Figma fill type "IMAGE").
          has-image-fill (some #(let [t (or (get % :type) (get % "type"))] (= t "IMAGE"))
                               (or (get node :fills) (get node "fills") []))]
      (cond
        has-image-fill
        (merge {:id id :type "image" :name name} rect)

        (or (figma-frame-types type) (= type figma-group-type))
        (let [child-specs (into [] (keep #(convert-figma-node % c)) children)]
          (merge {:id id :type "group" :name name}
                 rect
                 {:fills (figma-fills node)
                  :shapes child-specs}))

        (figma-rect-types type)
        (merge {:id id :type "rect" :name name}
               rect
               {:fills (figma-fills node)})

        (= type "TEXT")
        (merge {:id id :type "text" :name name}
               rect
               {:fills (figma-fills node)
                :content (or (get node :characters) (get node "characters") "")})

        ;; Exotic / unknown (BOOLEAN_OPERATION, SLICE, STICKY, …) — skip.
        :else nil))))

(defn convert-figma->spec
  "Pure: walk a Figma document JSON and emit a Penpot DesignSpec. Each
  top-level FRAME/COMPONENT/INSTANCE under `document` becomes a DesignSpec
  frame; nested nodes become shapes. Exotic nodes are skipped."
  [figma-json]
  (let [doc (js->clj figma-json :keywordize-keys true)
        root (or (get doc :document) (get doc "document") {})
        top-children (or (get root :children) (get root "children") [])
        canvas-nodes (if (seq top-children)
                       ;; Figma wraps frames in a CANVAS per page; descend
                       ;; one level so top-level frames become our frames.
                       (mapcat #(or (get % :children) (get % "children") []) top-children)
                       [])
        c (volatile! 0)
        frames
        (into []
              (keep
               (fn [node]
                 (let [type (or (get node :type) (get node "type") "")
                       rect (figma-rect node)
                       id (next-id! c)
                       name (or (get node :name) (get node "name") "Frame")
                       children (or (get node :children) (get node "children") [])
                       child-specs (into [] (keep #(convert-figma-node % c)) children)]
                   ;; Top-level frames (and components/instances) → frames.
                   (when (or (figma-frame-types type) (= type figma-group-type))
                     (cond-> {:id id :name name
                              :x (get rect :x 0)
                              :y (get rect :y 0)
                              :width (get rect :width 1440)
                              :height (get rect :height 900)
                              :shapes child-specs}
                       ;; Only include `:fills` when a solid fill exists —
                       ;; `build-frame` defaults to #ffffff when absent.
                       (some? (figma-solid-fills node))
                       (assoc :fills (figma-solid-fills node)))))))
              (if (seq canvas-nodes) canvas-nodes top-children))]
    {:target "new-board" :frames frames}))

;; ── Image upload (apply-time) ────────────────────────────────────────────────

(defn- collect-image-specs
  "Walk a DesignSpec and return the vector of every `{:image-data …}` shape
  found anywhere under `:frames`. Used so `apply-imported-spec` can upload
  the embedded Sketch/Figma images via the existing media path before
  committing the spec."
  [spec]
  (let [out (volatile! [])]
    (letfn [(walk-shape [s]
              (when (map? s)
                (when (some? (get s :image-data))
                  (vswap! out conj s))
                (doseq [child (get s :shapes [])]
                  (walk-shape child))))
            (walk-frame [f]
              (walk-shape f)
              (doseq [s (get f :shapes [])]
                (walk-shape s)))]
      (doseq [f (get spec :frames [])]
        (walk-frame f)))
    @out))

(defn- strip-image-data
  "Return a new spec with `:image-data` removed from every shape (so the
  base64 payloads never reach `cds/spec->shape-tree` / `cts/setup-shape`).
  `:image-id` is retained for traceability."
  [spec]
  (letfn [(walk-shape [s]
            (if-not (map? s) s
                    (-> s
                        (dissoc :image-data)
                        (update :shapes #(mapv walk-shape (or % []))))))]
    (-> spec
        (update :frames
                (fn [frames]
                  (mapv (fn [f]
                          (-> f
                              (dissoc :image-data)
                              (update :shapes #(mapv walk-shape (or % [])))))
                        (or frames [])))))))

(defn- base64->blob
  "Decode a base64 string into a Blob with `mtype`. Returns nil on failure
  (nil-safe — the caller skips the upload)."
  [b64 mtype]
  (try
    (when (and (string? b64) (not (str/blank? b64)))
      (let [bin (js/atob b64)
            len (.-length bin)
            arr (js/Uint8Array. len)]
        (dotimes [i len]
          (aset arr i (.charCodeAt bin i)))
        (js/Blob. #js [arr] #js {:type mtype})))
    (catch :default _ nil)))

(defn- upload-image!
  "Upload one base64 image as a file-media-object via the existing repo
  `:upload-file-media-object` command (the same path `media.cljs` uses for
  drag-drop images). Returns an observable that emits the media object or
  completes empty on failure (never throws — a bad image never aborts the
  whole import)."
  [file-id image-spec]
  (let [b64 (get image-spec :image-data)
        name (or (get image-spec :name) "imported-image")
        blob (base64->blob b64 "image/png")]
    (if (nil? blob)
      (rx/empty)
      (->> (rp/cmd! :upload-file-media-object
                    {:file-id file-id :name name :is-local true :content blob})
           (rx/catch (fn [_] (rx/empty)))))))

;; ── The apply event ──────────────────────────────────────────────────────────

(defn apply-imported-spec
  "Commit an imported DesignSpec to the current page. Reuses
  `design-gen/apply-design-spec` (the existing single-transaction commit
  path — no new shape-creation path). Any embedded images (`:image-data`)
  are uploaded via the media path FIRST, then stripped from the spec so the
  base64 never reaches `spec->shape-tree`; the image shapes land as neutral
  placeholder rects (v1 behavior) and the media objects exist in the file
  for a future Phase 6 wire-up.

  Nil-safe: a nil/empty spec emits a warning toast and does not touch the
  canvas (no partial commit)."
  [{:keys [spec]}]
  (ptk/reify ::apply-imported-spec
    ptk/WatchEvent
    (watch [_ state _]
      (let [file-id (:current-file-id state)
            frames (or (get spec :frames) [])]
        (if (or (nil? spec) (empty? frames))
          (rx/of (ntf/info (tr "workspace.import.empty")))
          (let [image-specs (collect-image-specs spec)
                spec-clean (strip-image-data spec)]
            (if (empty? image-specs)
              (rx/of (design-gen/apply-design-spec {:spec spec-clean :target "new-board"}))
              (->> (rx/from image-specs)
                   (rx/merge-map (fn [img] (upload-image! file-id img)))
                   (rx/reduce conj [])
                   (rx/mapcat
                    (fn [_]
                      (rx/of (ntf/info (tr "workspace.import.images-uploaded" (count image-specs)))
                             (design-gen/apply-design-spec {:spec spec-clean :target "new-board"}))))))))))))