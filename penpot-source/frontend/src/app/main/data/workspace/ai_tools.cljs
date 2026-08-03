;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL
;;
;; Ovion AI tool registry. Exposes ~80 of the user's canvas capabilities to the
;; design agent as OpenAI-style function tools. The agent loop
;; (`ai_gen.cljs` → `run-agent-design`) calls `tools-list` to ship the schema to
;; the model and `execute-tool` to run a returned call against the live scene
;; graph via `st/emit!` on the existing `dw*` potok events.
;;
;; Design rules (no-build, byte-identical-when-inactive):
;;   * PURE ADDITIONS. No existing event is changed; nothing imports this ns
;;     except `ai_gen`, so the whole registry is dead code when the agent loop
;;     is not invoked.
;;   * Every `:execute` is wrapped so a bad call NEVER throws out of the loop —
;;     it returns `{:ok false :error "..."}` and the loop carries on.
;;   * UUIDs from the model are coerced + nil-checked; nil → `{:ok false ...}`.
;;   * Read tools (`get_scene`, `get_selection`) return data without emitting.
;;   * The universal `update_shape` covers fills/strokes/shadow/blur/radius/
;;     opacity/geometry/rotation/name; the convenience wrappers just call it.

(ns app.main.data.workspace.ai-tools
  (:require
   [app.common.data :as d]
   [app.common.types.shape :as cts]
   [app.common.types.text :as txt]
   [app.common.uuid :as uuid]
   [app.main.data.common :as dcm]
   [app.main.data.exports.assets :as de]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.design-gen :as dg]
   [app.main.data.workspace.interactions :as dwi]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.pages :as dwp]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.texts :as dwt]
   [app.main.data.workspace.tokens.application :as dwta]
   [app.main.data.workspace.tokens.library-edit :as dwtl]
   [app.main.data.workspace.variants :as dwv]
   [app.main.store :as st]
   [app.common.types.tokens-lib :as ctob]
   [cuerdas.core :as str]))

;; ── Helpers ──────────────────────────────────────────────────────────────────

(defn- parse-uuid
  "Coerce a model-supplied id (string/uuid) to a Penpot uuid, or nil."
  [s]
  (try (uuid/parse (str s))
       (catch :default _ nil)))

(defn- parse-uuids
  "Coerce a seq of ids to a vector of uuids, dropping invalid ones."
  [coll]
  (into [] (keep parse-uuid) coll))

(defn- safe-emit!
  "Emit one or more potok events, returning an ok/err map. Any assertion or
  shape-validation failure inside the event is caught so the agent loop never
  crashes on a malformed tool call."
  [& events]
  (try
    (apply st/emit! events)
    {:ok true}
    (catch :default e
      {:ok false :error (str (or (some-> e .-message) e))})))

(defn- resolve-active-token
  "Resolve a token by name from the current file's active token sets.
  Returns the token object (satisfies `ctob/token?`) or nil when the
  tokens feature is unavailable / no active set contains the name. This
  is defensive by design so the agent loop never crashes on a missing
  token."
  [state token-name]
  (try
    (some-> (dsh/lookup-file-data state)
            :tokens-lib
            (ctob/get-tokens-in-active-sets)
            (get (str token-name)))
    (catch :default _ nil)))

(defn- default-text-root
  "A minimal Penpot text content tree (root → paragraph-set → paragraph → text)."
  []
  {:type "root"
   :children [{:type "paragraph-set"
               :children [{:type "paragraph"
                           :children [{:text ""}]}]}]})

(defn- text-content-from-string
  "Build a Penpot content tree from a plain string, reusing the typographic
  styles of a default root (txt/change-text preserves root/paragraph/text
  styles and only swaps the text nodes)."
  [s]
  (txt/change-text (default-text-root) (str s)))

(defn- num-or
  [v fallback]
  (let [n (js/Number v)]
    (if (js/isFinite n) n fallback)))

;; ── Registry ──────────────────────────────────────────────────────────────────
;;
;; Each entry: {<name> {:description <string> :schema <json-schema> :execute (fn [args state] <result-map>)}}
;; `args` is a keywordized CLJS map. Schemas use snake/hyphenated JSON property
;; names that keywordize to the exact Penpot shape keys (:font-id, :r1 …).

(def registry
  {;; ── Drawing / geometry / transform ────────────────────────────────────────
   "create_shape"
   {:description
    "Create a new shape on the current page and select it. `type` is one of
    rect, frame, text, circle, path, polygon, star, note, slice, svg-raw. For
    text, pass `content` as a plain string. Pass Penpot shape keys directly
    (fills, strokes, r1-r4, rotation, opacity, x, y, width, height, name)."
    :schema
    {:type "object"
     :properties
     {:type {:type "string" :enum ["rect" "frame" "text" "circle" "path"
                                   "polygon" "star" "note" "slice" "svg-raw"]}
      :x {:type "number"}
      :y {:type "number"}
      :width {:type "number"}
      :height {:type "number"}
      :name {:type "string"}
      :fills {:type "array" :description "Penpot fill maps"}
      :strokes {:type "array" :description "Penpot stroke maps"}
      :r1 {:type "number"} :r2 {:type "number"}
      :r3 {:type "number"} :r4 {:type "number"}
      :rotation {:type "number"}
      :opacity {:type "number"}
      :content {:type "string" :description "text content (text type only)"}}
     :required ["type" "x" "y" "width" "height"]}
    :execute
    (fn [a _state]
      (let [type (keyword (or (:type a) :rect))
            props (cond->
                    {:type type
                     :x (num-or (:x a) 0)
                     :y (num-or (:y a) 0)
                     :width (num-or (:width a) 100)
                     :height (num-or (:height a) 100)}
                    (:name a)      (assoc :name (:name a))
                    (:fills a)     (assoc :fills (:fills a))
                    (:strokes a)   (assoc :strokes (:strokes a))
                    (some? (:r1 a)) (assoc :r1 (num-or (:r1 a) 0))
                    (some? (:r2 a)) (assoc :r2 (num-or (:r2 a) 0))
                    (some? (:r3 a)) (assoc :r3 (num-or (:r3 a) 0))
                    (some? (:r4 a)) (assoc :r4 (num-or (:r4 a) 0))
                    (:rotation a)  (assoc :rotation (num-or (:rotation a) 0))
                    (:opacity a)   (assoc :opacity (num-or (:opacity a) 1))
                    (and (= type :text) (:content a))
                    (assoc :content (text-content-from-string (:content a))))
            shape (cts/setup-shape props)]
        (safe-emit! (dw/add-shape shape))))}

   "update_shape"
   {:description
    "The universal shape mutator. Merges `attrs` into the shape with the given
    `id`. Use this for fills, strokes, shadow, blur, r1-r4, opacity, rotation,
    x, y, width, height, name, grow-type, blend-mode, constraints-h/v. Pass
    Penpot keys verbatim. Prefer the convenience tools (set_fill, set_radius,
    set_opacity, set_rotation, rename_shape) when they fit."
    :schema
    {:type "object"
     :properties
     {:id {:type "string"}
      :attrs {:type "object" :description "Penpot shape attr map to merge"}}
     :required ["id" "attrs"]}
    :execute
    (fn [a _state]
      (if-let [id (parse-uuid (:id a))]
        (safe-emit! (dw/update-shape id (:attrs a)))
        {:ok false :error "invalid id"}))}

   "update_shapes"
   {:description
    "Merge the same `attrs` into every shape in `ids`. See update_shape for the
    attr map."
    :schema
    {:type "object"
     :properties {:ids {:type "array" :items {:type "string"}}
                  :attrs {:type "object"}}
     :required ["ids" "attrs"]}
    :execute
    (fn [a _state]
      (let [ids (parse-uuids (:ids a))]
        (if (seq ids)
          (safe-emit! (dwsh/update-shapes ids #(merge % (:attrs a))))
          {:ok false :error "no valid ids"})))}

   "delete_shapes"
   {:description "Delete the shapes with the given ids."
    :schema
    {:type "object" :properties {:ids {:type "array" :items {:type "string"}}}
     :required ["ids"]}
    :execute
    (fn [a _state]
      (let [ids (set (parse-uuids (:ids a)))]
        (if (seq ids)
          (safe-emit! (dwsh/delete-shapes ids) (dws/deselect-all))
          {:ok false :error "no valid ids"})))}

   "duplicate_selected"
   {:description "Duplicate the current selection (in place). No arguments."
    :schema {:type "object" :properties {}}
    :execute (fn [_a _state] (safe-emit! (dws/duplicate-selected)))}

   "nudge_shapes"
   {:description "Move the given shapes by a delta (dx, dy) in pixels."
    :schema
    {:type "object"
     :properties {:ids {:type "array" :items {:type "string"}}
                  :dx {:type "number"} :dy {:type "number"}}
     :required ["ids" "dx" "dy"]}
    :execute
    (fn [a _state]
      (let [ids (parse-uuids (:ids a))
            dx (num-or (:dx a) 0) dy (num-or (:dy a) 0)]
        (if (seq ids)
          (safe-emit! (dwsh/update-shapes ids
                       #(-> % (update :x + dx) (update :y + dy))))
          {:ok false :error "no valid ids"})))}

   "align_objects"
   {:description "Align the current selection along an axis. axis: h or v."
    :schema
    {:type "object" :properties {:axis {:type "string" :enum ["h" "v"]}}
     :required ["axis"]}
    :execute
    (fn [a _state] (safe-emit! (dw/align-objects (keyword (:axis a)))))}

   "distribute_objects"
   {:description "Distribute the current selection along an axis. axis: h or v."
    :schema
    {:type "object" :properties {:axis {:type "string" :enum ["h" "v"]}}
     :required ["axis"]}
    :execute
    (fn [a _state] (safe-emit! (dw/distribute-objects (keyword (:axis a)))))}

   "flip_horizontal"
   {:description "Flip the current selection horizontally. No arguments."
    :schema {:type "object" :properties {}}
    :execute (fn [_a _state] (safe-emit! (dw/flip-horizontal-selected)))}

   "flip_vertical"
   {:description "Flip the current selection vertically. No arguments."
    :schema {:type "object" :properties {}}
    :execute (fn [_a _state] (safe-emit! (dw/flip-vertical-selected)))}

   "order_shape"
   {:description
    "Change z-order of the current selection. loc: top, bottom, up, down."
    :schema
    {:type "object"
     :properties {:loc {:type "string" :enum ["top" "bottom" "up" "down"]}}
     :required ["loc"]}
    :execute
    (fn [a _state] (safe-emit! (dw/vertical-order-selected (keyword (:loc a)))))}

   "convert_to_path"
   {:description "Convert the current selection to an editable path. No arguments."
    :schema {:type "object" :properties {}}
    :execute (fn [_a _state] (safe-emit! (dw/convert-selected-to-path)))}

   "create_artboard_from_selection"
   {:description
    "Wrap the current selection into a new frame/artboard. No arguments."
    :schema {:type "object" :properties {}}
    :execute (fn [_a _state] (safe-emit! (dwsh/create-artboard-from-selection)))}

   ;; ── Styling (convenience wrappers over update_shape) ────────────────────────
   "set_fill"
   {:description "Set the fills of a shape. `fills` is an array of Penpot fill maps."
    :schema
    {:type "object"
     :properties {:id {:type "string"} :fills {:type "array"}}
     :required ["id" "fills"]}
    :execute
    (fn [a _state]
      (if-let [id (parse-uuid (:id a))]
        (safe-emit! (dw/update-shape id {:fills (:fills a)}))
        {:ok false :error "invalid id"}))}

   "set_strokes"
   {:description "Set the strokes of a shape. `strokes` is an array of Penpot stroke maps."
    :schema
    {:type "object"
     :properties {:id {:type "string"} :strokes {:type "array"}}
     :required ["id" "strokes"]}
    :execute
    (fn [a _state]
      (if-let [id (parse-uuid (:id a))]
        (safe-emit! (dw/update-shape id {:strokes (:strokes a)}))
        {:ok false :error "invalid id"}))}

   "set_shadow"
   {:description "Set the shadow of a shape. `shadow` is an array of Penpot shadow maps."
    :schema
    {:type "object"
     :properties {:id {:type "string"} :shadow {:type "array"}}
     :required ["id" "shadow"]}
    :execute
    (fn [a _state]
      (if-let [id (parse-uuid (:id a))]
        (safe-emit! (dw/update-shape id {:shadow (:shadow a)}))
        {:ok false :error "invalid id"}))}

   "set_blur"
   {:description "Set the blur of a shape. `blur` is a Penpot blur map."
    :schema
    {:type "object"
     :properties {:id {:type "string"} :blur {:type "object"}}
     :required ["id" "blur"]}
    :execute
    (fn [a _state]
      (if-let [id (parse-uuid (:id a))]
        (safe-emit! (dw/update-shape id {:blur (:blur a)}))
        {:ok false :error "invalid id"}))}

   "set_radius"
   {:description
    "Set corner radii of a shape. Pass `radius` to set all four corners equal,
    or r1/r2/r3/r4 individually (top-left, top-right, bottom-right, bottom-left)."
    :schema
    {:type "object"
     :properties {:id {:type "string"}
                  :radius {:type "number"}
                  :r1 {:type "number"} :r2 {:type "number"}
                  :r3 {:type "number"} :r4 {:type "number"}}
     :required ["id"]}
    :execute
    (fn [a _state]
      (if-let [id (parse-uuid (:id a))]
        (let [r (when (some? (:radius a)) (num-or (:radius a) 0))
              attrs (cond-> {}
                      (some? r)           (assoc :r1 r :r2 r :r3 r :r4 r)
                      (some? (:r1 a))     (assoc :r1 (num-or (:r1 a) 0))
                      (some? (:r2 a))     (assoc :r2 (num-or (:r2 a) 0))
                      (some? (:r3 a))     (assoc :r3 (num-or (:r3 a) 0))
                      (some? (:r4 a))     (assoc :r4 (num-or (:r4 a) 0)))]
          (safe-emit! (dw/update-shape id attrs)))
        {:ok false :error "invalid id"}))}

   "set_opacity"
   {:description "Set the opacity of a shape (0..1)."
    :schema
    {:type "object"
     :properties {:id {:type "string"} :opacity {:type "number"}}
     :required ["id" "opacity"]}
    :execute
    (fn [a _state]
      (if-let [id (parse-uuid (:id a))]
        (safe-emit! (dw/update-shape id {:opacity (num-or (:opacity a) 1)}))
        {:ok false :error "invalid id"}))}

   "set_rotation"
   {:description "Set the rotation of a shape in degrees."
    :schema
    {:type "object"
     :properties {:id {:type "string"} :rotation {:type "number"}}
     :required ["id" "rotation"]}
    :execute
    (fn [a _state]
      (if-let [id (parse-uuid (:id a))]
        (safe-emit! (dw/update-shape id {:rotation (num-or (:rotation a) 0)}))
        {:ok false :error "invalid id"}))}

   ;; ── Text / typography ──────────────────────────────────────────────────────
   "set_text"
   {:description
    "Replace the text content of a text shape with a plain string. Existing
    typographic styles are preserved."
    :schema
    {:type "object"
     :properties {:id {:type "string"} :text {:type "string"}}
     :required ["id" "text"]}
    :execute
    (fn [a state]
      (if-let [id (parse-uuid (:id a))]
        (let [text (str (:text a))
              objects (dsh/lookup-page-objects state)
              shape (get objects id)
              content (or (:content shape) (default-text-root))
              new-content (txt/change-text content text)]
          (safe-emit! (dwt/v2-update-text-shape-content id new-content
                       :finalize? true :update-name? false)))
        {:ok false :error "invalid id"}))}

   "set_typography"
   {:description
    "Apply typography attributes to a text shape. Pass Penpot text attr keys:
    font-id, font-variant-id, font-family, font-size, font-weight, font-style,
    line-height, letter-spacing, text-align, text-decoration, text-transform,
    direction."
    :schema
    {:type "object"
     :properties {:id {:type "string"}
                  :font-id {:type "string"}
                  :font-variant-id {:type "string"}
                  :font-family {:type "string"}
                  :font-size {:type "number"}
                  :font-weight {:type "string"}
                  :font-style {:type "string"}
                  :line-height {:type "string"}
                  :letter-spacing {:type "number"}
                  :text-align {:type "string" :enum ["left" "right" "center" "justify"]}
                  :text-decoration {:type "string"}
                  :text-transform {:type "string"}
                  :direction {:type "string"}}
     :required ["id"]}
    :execute
    (fn [a _state]
      (if-let [id (parse-uuid (:id a))]
        (let [attrs (-> a (dissoc :id) (d/without-nils))]
          (safe-emit! (dwt/update-attrs id attrs)))
        {:ok false :error "invalid id"}))}

   "rename_shape"
   {:description "Rename a shape."
    :schema
    {:type "object"
     :properties {:id {:type "string"} :name {:type "string"}}
     :required ["id" "name"]}
    :execute
    (fn [a _state]
      (if-let [id (parse-uuid (:id a))]
        (safe-emit! (dw/update-shape id {:name (:name a)}))
        {:ok false :error "invalid id"}))}

   ;; ── Layout (flex / grid) ───────────────────────────────────────────────────
   "add_flex_layout"
   {:description "Add a flex auto-layout to the current selection. No arguments."
    :schema {:type "object" :properties {}}
    :execute (fn [_a _state] (safe-emit! (dwsl/create-layout :flex)))}

   "add_grid_layout"
   {:description "Add a grid layout to the current selection. No arguments."
    :schema {:type "object" :properties {}}
    :execute (fn [_a _state] (safe-emit! (dwsl/create-layout :grid)))}

   "update_layout"
   {:description
    "Update the layout of the given shapes. `changes` is a Penpot layout patch
    map (e.g. {\"layout-align-items\":\"start\",\"layout-gap\":{\"row\":8,\"column\":8},
    \"layout-padding\":{...}})."
    :schema
    {:type "object"
     :properties {:ids {:type "array" :items {:type "string"}}
                  :changes {:type "object"}}
     :required ["ids" "changes"]}
    :execute
    (fn [a _state]
      (let [ids (parse-uuids (:ids a))]
        (if (seq ids)
          (safe-emit! (dwsl/update-layout ids (:changes a)))
          {:ok false :error "no valid ids"})))}

   "set_child_layout_props"
   {:description
    "Set flex-item / grid-cell props on the given children: layout-item-margin,
    layout-item-padding, layout-item-min-w, layout-item-min-h, layout-item-max-w,
    layout-item-max-h, layout-item-align-self, layout-item-position, absolute
    pin (layout-item-absolute)."
    :schema
    {:type "object"
     :properties {:ids {:type "array" :items {:type "string"}}
                  :props {:type "object"}}
     :required ["ids" "props"]}
    :execute
    (fn [a _state]
      (let [ids (parse-uuids (:ids a))]
        (if (seq ids)
          (safe-emit! (dwsh/update-shapes ids #(merge % (:props a))))
          {:ok false :error "no valid ids"})))}

   "remove_layout"
   {:description "Remove layout from the given shapes."
    :schema
    {:type "object" :properties {:ids {:type "array" :items {:type "string"}}}
     :required ["ids"]}
    :execute
    (fn [a _state]
      (let [ids (parse-uuids (:ids a))]
        (if (seq ids)
          (safe-emit! (dwsl/remove-layout ids))
          {:ok false :error "no valid ids"})))}

   "grid_add_track"
   {:description "Add a row/column track to the grid layout of the given shapes."
    :schema
    {:type "object"
     :properties {:ids {:type "array" :items {:type "string"}}
                  :type {:type "string" :enum ["row" "column"]}
                  :value {:type "string" :description "track size, e.g. '100px' or '1fr'"}}
     :required ["ids" "type"]}
    :execute
    (fn [a _state]
      (let [ids (parse-uuids (:ids a))]
        (if (seq ids)
          (safe-emit! (dwsl/add-layout-track ids (keyword (:type a)) (:value a)))
          {:ok false :error "no valid ids"})))}

   "grid_delete_track"
   {:description "Delete the row/column track at `index` from the grid layout of the given shapes."
    :schema
    {:type "object"
     :properties {:ids {:type "array" :items {:type "string"}}
                  :type {:type "string" :enum ["row" "column"]}
                  :index {:type "number"}}
     :required ["ids" "type" "index"]}
    :execute
    (fn [a _state]
      (let [ids (parse-uuids (:ids a))
            index (int (num-or (:index a) 0))]
        (if (seq ids)
          (safe-emit! (dwsl/remove-layout-track ids (keyword (:type a)) index))
          {:ok false :error "no valid ids"})))}

   "grid_merge_cells"
   {:description "Merge the given grid cells into one. `layout-id` is the grid frame id."
    :schema
    {:type "object"
     :properties {:layout-id {:type "string"}
                  :ids {:type "array" :items {:type "string"}}}
     :required ["layout-id" "ids"]}
    :execute
    (fn [a _state]
      (if-let [layout-id (parse-uuid (:layout-id a))]
        (let [ids (set (parse-uuids (:ids a)))]
          (if (seq ids)
            (safe-emit! (dwsl/merge-cells layout-id ids))
            {:ok false :error "no valid ids"}))
        {:ok false :error "invalid layout-id"}))}

   "grid_create_cell_board"
   {:description "Create a board from merged grid cells. `layout-id` is the grid frame id."
    :schema
    {:type "object"
     :properties {:layout-id {:type "string"}
                  :cell-ids {:type "array" :items {:type "string"}}}
     :required ["layout-id" "cell-ids"]}
    :execute
    (fn [a _state]
      (if-let [layout-id (parse-uuid (:layout-id a))]
        (let [ids (set (parse-uuids (:cell-ids a)))]
          (if (seq ids)
            (safe-emit! (dwsl/create-cell-board layout-id ids))
            {:ok false :error "no valid ids"}))
        {:ok false :error "invalid layout-id"}))}

   ;; ── Components / variants / interactions / prototype ────────────────────────
   "create_component"
   {:description "Create a component from the current selection. No arguments."
    :schema {:type "object" :properties {}}
    :execute (fn [_a _state] (safe-emit! (dwl/add-component)))}

   "create_multiple_components"
   {:description
    "Create one component per selected shape. No arguments."
    :schema {:type "object" :properties {}}
    :execute (fn [_a _state] (safe-emit! (dwl/add-multiple-components)))}

   "combine_as_variants"
   {:description
    "Combine the current selection into a variant set. No arguments."
    :schema {:type "object" :properties {}}
    :execute (fn [_a _state] (safe-emit! (dwv/combine-selected-as-variants {})))}

   "add_variant"
   {:description "Add a new variant to the variant container `shape-id`."
    :schema
    {:type "object" :properties {:shape-id {:type "string"}}
     :required ["shape-id"]}
    :execute
    (fn [a _state]
      (if-let [id (parse-uuid (:shape-id a))]
        (safe-emit! (dwv/add-new-variant id))
        {:ok false :error "invalid shape-id"}))}

   "add_variant_property"
   {:description
    "Add a variant property named `name` of `type` (text, boolean,
    instance-swap, variant, slot) to the variant container `variant-id`."
    :schema
    {:type "object"
     :properties {:variant-id {:type "string"}
                  :name {:type "string"}
                  :type {:type "string"
                         :enum ["text" "boolean" "instance-swap" "variant" "slot"]}}
     :required ["variant-id" "name" "type"]}
    :execute
    (fn [a _state]
      (if-let [id (parse-uuid (:variant-id a))]
        (safe-emit! (dwv/add-new-property id
                    {:name (:name a) :type (keyword (:type a))}))
        {:ok false :error "invalid variant-id"}))}

   "add_interaction"
   {:description
    "Add a prototype interaction to a shape. `interaction` is a Penpot
    interaction map: {event-type, action-type, destination, animation, easing,
    duration, delay, overlay-position-type, overlay-position}."
    :schema
    {:type "object"
     :properties {:shape-id {:type "string"} :interaction {:type "object"}}
     :required ["shape-id" "interaction"]}
    :execute
    (fn [a _state]
      (if-let [id (parse-uuid (:shape-id a))]
        (safe-emit! (dwi/add-interaction nil id (:interaction a)))
        {:ok false :error "invalid shape-id"}))}

   "set_flow_start"
   {:description
    "Mark the current selection (a frame) as the prototype flow start. No args."
    :schema {:type "object" :properties {}}
    :execute (fn [_a _state] (safe-emit! (dwi/add-flow-selected-frame)))}

   "remove_flow"
   {:description "Remove the prototype flow with the given flow-id."
    :schema
    {:type "object" :properties {:flow-id {:type "string"}} :required ["flow-id"]}
    :execute
    (fn [a _state]
      (if-let [id (parse-uuid (:flow-id a))]
        (safe-emit! (dwi/remove-flow id))
        {:ok false :error "invalid flow-id"}))}

   ;; ── Layers / selection / grouping / masking / z-order ───────────────────────
   "select_shapes"
   {:description "Replace the selection with the given shape ids."
    :schema
    {:type "object" :properties {:ids {:type "array" :items {:type "string"}}}
     :required ["ids"]}
    :execute
    (fn [a _state]
      (let [ids (parse-uuids (:ids a))]
        (if (seq ids)
          (safe-emit! (dws/select-shapes (apply d/ordered-set ids)))
          {:ok false :error "no valid ids"})))}

   "select_all"
   {:description "Select all shapes on the current page. No arguments."
    :schema {:type "object" :properties {}}
    :execute (fn [_a _state] (safe-emit! (dws/select-all)))}

   "deselect_all"
   {:description "Clear the selection. No arguments."
    :schema {:type "object" :properties {}}
    :execute (fn [_a _state] (safe-emit! (dws/deselect-all)))}

   "toggle_visibility"
   {:description "Toggle visibility of the current selection. No arguments."
    :schema {:type "object" :properties {}}
    :execute (fn [_a _state] (safe-emit! (dw/toggle-visibility-selected)))}

   "set_flags"
   {:description "Set hidden/blocked flags on the given shapes."
    :schema
    {:type "object"
     :properties {:ids {:type "array" :items {:type "string"}}
                  :hidden {:type "boolean"} :blocked {:type "boolean"}}
     :required ["ids"]}
    :execute
    (fn [a _state]
      (let [ids (parse-uuids (:ids a))
            flags (cond-> {}
                    (some? (:hidden a))  (assoc :hidden (boolean (:hidden a)))
                    (some? (:blocked a)) (assoc :blocked (boolean (:blocked a))))]
        (if (seq ids)
          (safe-emit! (dwsh/update-shape-flags ids flags))
          {:ok false :error "no valid ids"})))}

   "group_selected"
   {:description "Group the current selection. No arguments."
    :schema {:type "object" :properties {}}
    :execute (fn [_a _state] (safe-emit! (dw/group-selected)))}

   "ungroup_selected"
   {:description "Ungroup the current selection. No arguments."
    :schema {:type "object" :properties {}}
    :execute (fn [_a _state] (safe-emit! (dw/ungroup-selected)))}

   "mask_group"
   {:description "Mask the current selection into a mask group. No arguments."
    :schema {:type "object" :properties {}}
    :execute (fn [_a _state] (safe-emit! (dw/mask-group)))}

   "unmask_group"
   {:description "Remove the mask from the current selection. No arguments."
    :schema {:type "object" :properties {}}
    :execute (fn [_a _state] (safe-emit! (dw/unmask-group)))}

   "tidy_up"
   {:description "Tidy up (auto-arrange) the layout of the current selection. No arguments."
    :schema {:type "object" :properties {}}
    :execute (fn [_a _state] (safe-emit! (dw/tidy-up)))}

   "toggle_focus_mode"
   {:description "Toggle focus mode. No arguments."
    :schema {:type "object" :properties {}}
    :execute (fn [_a _state] (safe-emit! (dw/toggle-focus-mode)))}

   ;; ── Clipboard / copy-as ─────────────────────────────────────────────────────
   "copy_css"
   {:description "Copy the CSS of the current selection to the clipboard. No args."
    :schema {:type "object" :properties {}}
    :execute (fn [_a _state] (safe-emit! (dw/copy-selected-css)))}

   "copy_svg"
   {:description "Copy the SVG of the current selection to the clipboard. No args."
    :schema {:type "object" :properties {}}
    :execute (fn [_a _state] (safe-emit! (dw/copy-selected-svg)))}

   "copy_props"
   {:description "Copy the style props of the current selection. No arguments."
    :schema {:type "object" :properties {}}
    :execute (fn [_a _state] (safe-emit! (dw/copy-selected-props)))}

   "paste_props"
   {:description "Paste previously-copied style props onto the current selection. No args."
    :schema {:type "object" :properties {}}
    :execute (fn [_a _state] (safe-emit! (dw/paste-selected-props)))}

   "paste"
   {:description "Paste from the clipboard. No arguments."
    :schema {:type "object" :properties {}}
    :execute (fn [_a _state] (safe-emit! (dw/paste-from-clipboard)))}

   "copy_as_image"
   {:description "Copy the current selection as a PNG image. No arguments."
    :schema {:type "object" :properties {}}
    :execute (fn [_a _state] (safe-emit! (dw/copy-as-image)))}

   "copy_link"
   {:description "Copy a shareable link to the current selection. No arguments."
    :schema {:type "object" :properties {}}
    :execute (fn [_a _state] (safe-emit! (dw/copy-link-to-clipboard)))}

   ;; ── Pages / file / export ───────────────────────────────────────────────────
   "duplicate_page"
   {:description "Duplicate the page with the given page-id."
    :schema
    {:type "object" :properties {:page-id {:type "string"}} :required ["page-id"]}
    :execute
    (fn [a _state]
      (if-let [id (parse-uuid (:page-id a))]
        (safe-emit! (dwp/duplicate-page id))
        {:ok false :error "invalid page-id"}))}

   "delete_page"
   {:description "Delete the page with the given page-id."
    :schema
    {:type "object" :properties {:page-id {:type "string"}} :required ["page-id"]}
    :execute
    (fn [a _state]
      (if-let [id (parse-uuid (:page-id a))]
        (safe-emit! (dwp/delete-page id))
        {:ok false :error "invalid page-id"}))}

   "navigate_page"
   {:description "Navigate to the page with the given page-id."
    :schema
    {:type "object" :properties {:page-id {:type "string"}} :required ["page-id"]}
    :execute
    (fn [a _state]
      (if-let [id (parse-uuid (:page-id a))]
        (safe-emit! (dcm/go-to-workspace :page-id id))
        {:ok false :error "invalid page-id"}))}

   "rename_file"
   {:description "Rename the current file."
    :schema
    {:type "object" :properties {:name {:type "string"}} :required ["name"]}
    :execute
    (fn [a state]
      (if-let [file-id (:current-file-id state)]
        (safe-emit! (dw/rename-file file-id (:name a)))
        {:ok false :error "no current file"}))}

   "export_selection"
   {:description "Open the export dialog for the current selection. No arguments."
    :schema {:type "object" :properties {}}
    :execute (fn [_a _state] (safe-emit! (de/show-workspace-export-dialog)))}

   ;; ── Read / observe (no emit; return data to the model) ─────────────────────
   "get_scene"
   {:description
    "Return a structured, token-bounded snapshot of the current page's scene
    graph (ids, types, names, geometry, fills, typography, content, layout,
    children). Call this after mutations to observe the new state and discover
    freshly-created ids. No arguments."
    :schema {:type "object" :properties {}}
    :execute
    (fn [_a state] {:ok true :scene (dg/serialize-scene state)})}

   "get_selection"
   {:description
    "Return the structured snippet of the current selection (ids, types,
    geometry, content). No arguments."
    :schema {:type "object" :properties {}}
    :execute
    (fn [_a state] {:ok true :selection (or (dg/selection->snippet state) [])})}

   ;; ── AI depth: alternatives, generated image fills, design tokens ──────────
   "create_alternative"
   {:description
    "Duplicate the shape with `selection_id` as a sibling variant. When the
    target is a component main-instance, the duplicate is combined with the
    original into a new variant container (component variants); otherwise a
    plain sibling duplicate is produced. Returns `{:ok true :id <new-id>}`."
    :schema
    {:type "object"
     :properties {:selection_id {:type "string"}}
     :required ["selection_id"]}
    :execute
    (fn [a state]
      (if-let [id (parse-uuid (:selection_id a))]
        (let [objects (dsh/lookup-page-objects state)
              shape (get objects id)]
          (if (nil? shape)
            {:ok false :error "shape not found"}
            (let [return-ref (atom nil)
                  component? (true? (:main-instance shape))]
              (if component?
                ;; Component: duplicate (keep original selected), then combine
                ;; the [original duplicate] pair into a variant container.
                (let [res (safe-emit! (dw/duplicate-shapes
                                        #{id}
                                        :change-selection? false
                                        :return-ref return-ref))
                      new-id @return-ref]
                  (cond
                    (false? (:ok res)) res
                    (nil? new-id) res
                    :else
                    (let [res2 (safe-emit! (dwv/combine-as-variants [id new-id] {}))]
                      (cond-> res2 (true? (:ok res2)) (assoc :id new-id)))))
                ;; Non-component: plain sibling duplicate, select the new shape.
                (let [res (safe-emit! (dw/duplicate-shapes
                                        #{id}
                                        :change-selection? true
                                        :return-ref return-ref))]
                  (cond-> res
                          (and (true? (:ok res)) @return-ref)
                          (assoc :id @return-ref)))))))
        {:ok false :error "invalid selection_id"}))}

   "set_generated_image_fill"
   {:description
    "Apply an AI-generated image (base64 + mime) as the fill of the shape
    with `shape_id`. The fill is built as a Penpot image fill map and
    applied via `dw/update-shape`. NOTE: registering the base64 bytes as a
    backend media object (so the renderer can fetch it) is a separate
    upload step performed outside this tool; here we attach the image-fill
    structure so the shape is marked image-filled. Returns `{:ok true}`."
    :schema
    {:type "object"
     :properties {:shape_id {:type "string"}
                  :image_base64 {:type "string"}
                  :mime {:type "string"}}
     :required ["shape_id" "image_base64"]}
    :execute
    (fn [a _state]
      (if-let [id (parse-uuid (:shape_id a))]
        (let [b64 (str (:image_base64 a))
              mime (or (:mime a) "image/png")
              fill {:type :image
                    :fill-image {:id (uuid/next)
                                 :width 0
                                 :height 0
                                 :mtype mime
                                 :name "ai-generated"}
                    :fill-opacity 1}]
          (if (str/empty? b64)
            {:ok false :error "image_base64 required"}
            (safe-emit! (dw/update-shape id {:fills [fill]}))))
        {:ok false :error "invalid shape_id"}))}

   "apply_color_token"
   {:description
    "Apply a named color token to the fill of the shape with `shape_id`.
    The token must exist in the file's active token sets. Defensive: if the
    tokens feature is unavailable or the token is missing/not a color
    token, returns `{:ok false :error \"token not found\"}`."
    :schema
    {:type "object"
     :properties {:shape_id {:type "string"} :token {:type "string"}}
     :required ["shape_id" "token"]}
    :execute
    (fn [a state]
      (if-let [id (parse-uuid (:shape_id a))]
        (if-let [token (resolve-active-token state (:token a))]
          (if (= :color (:type token))
            (safe-emit! (dwta/apply-token-from-input
                         {:token token :shape-ids [id]}))
            {:ok false :error "token not found"})
          {:ok false :error "token not found"})
        {:ok false :error "invalid shape_id"}))}

   "apply_typography_token"
   {:description
    "Apply a named typography token to the text shape with `shape_id`. The
    token must exist in the file's active token sets. Defensive: if the
    tokens feature is unavailable or the token is missing/not a typography
    token, returns `{:ok false :error \"token not found\"}`."
    :schema
    {:type "object"
     :properties {:shape_id {:type "string"} :token {:type "string"}}
     :required ["shape_id" "token"]}
    :execute
    (fn [a state]
      (if-let [id (parse-uuid (:shape_id a))]
        (if-let [token (resolve-active-token state (:token a))]
          (if (= :typography (:type token))
            (safe-emit! (dwta/apply-token-from-input
                         {:token token :shape-ids [id]}))
            {:ok false :error "token not found"})
          {:ok false :error "token not found"})
        {:ok false :error "invalid shape_id"}))}

   "create_color_token"
   {:description
    "Create a new color token with `name` and a hex/rgba `value` in the
    file's token library. If no token set exists yet, a default 'Global'
    set is created automatically by the tokens event. Returns `{:ok true}`."
    :schema
    {:type "object"
     :properties {:name {:type "string"} :value {:type "string"}}
     :required ["name" "value"]}
    :execute
    (fn [a _state]
      (let [name (str (:name a)) value (str (:value a))]
        (if (str/empty? name)
          {:ok false :error "name required"}
          (let [token (ctob/make-token :type :color :name name :value value)]
            (safe-emit! (dwtl/create-token token))))))}})

;; ── Public API ────────────────────────────────────────────────────────────────

(defn tools-list
  "Build the OpenAI-shaped tools array (CLJS data; `clj->js` before invoke).
  Excludes the `:execute` fns so nothing tries to serialize a function."
  []
  (mapv (fn [[name {:keys [description schema]}]]
          {:type "function"
           :function {:name name
                      :description (or description "")
                      :parameters (or schema {:type "object" :properties {}})}})
        (seq registry)))

(defn tool-names
  "Sorted list of registered tool names (for verification / logging)."
  []
  (sort (keys registry)))

(defn execute-tool
  "Execute a tool by name. `args` is a keywordized CLJS map (from the model's
  JSON tool-call arguments). Returns a plain result map the agent loop
  JSON-stringifies into the tool-result message. Unknown tools / bad args
  return `{:ok false :error ...}` instead of throwing — the loop always
  continues."
  [name args state]
  (let [name (str name)
        tool (get registry name)]
    (cond
      (nil? tool) {:ok false :error (str "unknown tool: " name)}
      :else
      (try ((:execute tool) args state)
           (catch :default e
             {:ok false :error (str (or (some-> e .-message) (str e)))})))))