;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.types.design-spec
  "Foundation F3 — the DesignSpec contract between the closed AI layer
  (Foundation F4 `src-tauri/src/llm.rs`, which returns a DesignSpec JSON) and
  the canvas (Feature 3 `apply-design-spec`).

  A DesignSpec describes a Penpot design AND, optionally, a runnable
  prototype. It is a CONSTRAINED subset of the full shape model: the LLM emits
  this small shape; `spec->shape-tree` expands each entry into a valid Penpot
  shape map via `cts/setup-shape`, mints UUIDs, wires parent/children +
  interactions + flows. The frontend commits the whole tree as ONE undo
  transaction.

  Pure cljc (no store/IO) so it is unit-testable on the JVM."
  (:require
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.common.schema :as sm]
   [app.common.types.shape :as cts]
   [app.common.types.shape.interactions :as ctsi]
   [app.common.types.text :as txt]
   [app.common.uuid :as uuid]))

;; ── Schema ──────────────────────────────────────────────────────────────────
;;
;; Permissive on purpose: the model output varies, so the schema documents the
;; expected keys but does not reject extras (Malli `:map` is open by default).
;; Per-shape validity comes from construction via `cts/setup-shape`, which
;; always yields a valid shape; interactions are validated with
;; `ctsi/check-interaction` and dropped if structurally wrong.

(def schema:fill
  [:map
   [:fill-color :string]
   [:fill-opacity {:optional true} number?]])

(def schema:shape
  [:map
   [:id :string]
   [:type :string]
   [:name {:optional true} :string]
   [:x {:optional true} number?]
   [:y {:optional true} number?]
   [:width {:optional true} number?]
   [:height {:optional true} number?]
   [:fills {:optional true} [:vector schema:fill]]
   [:r1 {:optional true} number?]
   [:r2 {:optional true} number?]
   [:r3 {:optional true} number?]
   [:r4 {:optional true} number?]
   ;; text-only
   [:content {:optional true} :string]
   [:font-family {:optional true} :string]
   [:font-weight {:optional true} :any]
   [:font-style {:optional true} :any]
   [:font-size {:optional true} :any]
   [:line-height {:optional true} :any]
   [:letter-spacing {:optional true} :any]
   [:text-align {:optional true} :any]
   ;; group/frame children
   [:shapes {:optional true} [:vector :any]]])

(def schema:frame
  [:map
   [:id :string]
   [:name {:optional true} :string]
   [:x {:optional true} number?]
   [:y {:optional true} number?]
   [:width {:optional true} number?]
   [:height {:optional true} number?]
   [:fills {:optional true} [:vector schema:fill]]
   [:shapes {:optional true} [:vector schema:shape]]])

(def schema:interaction
  [:map
   [:shape :string]
   [:frame {:optional true} :string]
   [:event-type {:optional true} :any]
   [:action-type {:optional true} :any]
   [:destination {:optional true} :string]
   [:delay {:optional true} number?]
   [:overlay-position {:optional true} :any]
   [:url {:optional true} :string]
   [:animation {:optional true} :any]])

(def schema:flow
  [:map
   [:id :string]
   [:name {:optional true} :string]
   [:starting-frame :string]])

;; Forward declaration: `schema:design-spec` references `schema:site` (optional
;; `:site` key) and `schema:site` → `schema:site-page` → `schema:design-spec`
;; closes the mutual recursion. Declared here so the design-spec def resolves.
(declare schema:site)

(def schema:design-spec
  [:map
   [:target {:optional true} :string]
   [:frames [:vector schema:frame]]
   [:interactions {:optional true} [:vector schema:interaction]]
   [:flows {:optional true} [:vector schema:flow]]
   ;; P0.03 — multi-page site generation. Optional; absent for the classic
   ;; single-page DesignSpec the AI bar has always emitted. When present,
   ;; `app.main.data.workspace.site-gen/apply-site-spec` fans the `:pages`
   ;; out across real Penpot pages (one page per site-page).
   [:site {:optional true} schema:site]])

;; ── Site (multi-page) ───────────────────────────────────────────────────────
;;
;; A Site bundles multiple DesignSpec pages + optional nav links. Each
;; site-page carries its own `:spec` (a recursive DesignSpec). The mutual
;; recursion (design-spec → site → site-page → design-spec) is broken by the
;; `declare` above. Nav links are schema-only at this stage — cross-page
;; interaction wiring is deferred (no canvas nav-graph is built yet).

(def schema:seo
  [:map
   [:title {:optional true} string?]
   [:description {:optional true} string?]
   [:og-image {:optional true} string?]
   [:keywords {:optional true} [:vector string?]]])

(def schema:nav-link
  [:map
   [:label string?]
   [:page-slug string?]])

(def schema:site-page
  [:map
   [:id {:optional true} uuid?]
   [:name string?]
   [:slug string?]
   [:seo {:optional true} schema:seo]
   [:spec schema:design-spec]])

(def schema:site
  [:map {:title "Site" :closed true}
   [:pages [:vector schema:site-page]]
   [:nav {:optional true} [:vector schema:nav-link]]])

(def check-design-spec
  (sm/check-fn schema:design-spec))

(def check-site
  (sm/check-fn schema:site))

;; ── Coercion helpers ────────────────────────────────────────────────────────

(defn- kw [x default]
  (cond
    (nil? x)      default
    (keyword? x)  x
    (string? x)   (keyword x)
    :else         default))

(defn- num-or [x default]
  (if (number? x) x default))

(defn- ->str [x]
  (cond
    (nil? x)      nil
    (string? x)   x
    (keyword? x)  (name x)
    :else         (str x)))

(defn- fill->map [f]
  (cond
    (map? f) {:fill-color   (or (get f :fill-color) (get f "fill-color") "#000000")
              :fill-opacity (or (get f :fill-opacity) (get f "fill-opacity") 1)}
    :else {:fill-color "#000000" :fill-opacity 1}))

(defn- fills
  "Normalize the spec's :fills into a Penpot fills vector, defaulting to
  `default-color` when absent/empty."
  [spec default-color]
  (let [fs (seq (get spec :fills))]
    (if (seq fs)
      (mapv fill->map fs)
      [{:fill-color default-color :fill-opacity 1}])))

;; ── Animation ───────────────────────────────────────────────────────────────

(defn- spec->animation [anim]
  (let [type     (kw (get anim :type) :dissolve)
        duration (num-or (get anim :duration) 300)
        easing   (kw (get anim :easing) :ease)
        base     {:animation-type type :duration duration :easing easing}]
    (case type
      :dissolve base
      :slide    (assoc base
                       :way (kw (get anim :way) :in)
                       :direction (kw (get anim :direction) :left)
                       :offset-effect false)
      :push     (assoc base :direction (kw (get anim :direction) :left))
      ;; Unknown → fall back to a valid dissolve so the interaction still
      ;; validates.
      {:animation-type :dissolve :duration duration :easing easing})))

;; ── Interaction ─────────────────────────────────────────────────────────────

(defn spec->interaction
  "Convert one LLM interaction spec into a valid Penpot interaction map.
  `id-map` resolves spec ids (e.g. \"f2\") to the real shape UUIDs minted by
  `spec->shape-tree`. Returns nil if the source shape is unknown or the
  resulting interaction fails `ctsi/check-interaction` (so the caller can
  drop it instead of crashing the whole generation)."
  [spec id-map]
  (let [shape-uuid (get id-map (get spec :shape))]
    (when shape-uuid
      (let [event-type  (kw (get spec :event-type) :click)
            action-type (kw (get spec :action-type) :navigate)
            dest-spec   (get spec :destination)
            dest-uuid   (some-> dest-spec (get id-map))
            candidate   (cond-> (-> ctsi/default-interaction
                                   (assoc :event-type event-type :action-type action-type))
                          (= event-type :after-delay)                    (assoc :delay (num-or (get spec :delay) ctsi/default-delay))
                          (#{:navigate :close-overlay} action-type)      (assoc :destination dest-uuid)
                          (#{:open-overlay :toggle-overlay} action-type) (assoc :destination dest-uuid
                                                                                     :overlay-pos-type (kw (get spec :overlay-position) :center)
                                                                                     :overlay-position (gpt/point 0 0))
                          (= action-type :open-url)                      (assoc :url (or (get spec :url) ""))
                          (get spec :animation)                          (assoc :animation (spec->animation (get spec :animation))))]
        ;; `check-interaction` returns falsy on invalid; the try is belt-and-
        ;; braces so a thrown explanation never aborts the whole generation.
        (when (try (ctsi/check-interaction candidate) (catch :default _ false))
          candidate)))))

;; ── Shape construction ──────────────────────────────────────────────────────
;;
;; `cts/setup-shape` merges minimal attrs (which set :frame-id/:parent-id to
;; uuid/zero and generate :id) over our props, so we override
;; :id/:parent-id/:frame-id explicitly. It supports :rect, :circle, :text,
;; :group, :frame, :path, :bool, :image, :svg-raw.

(defn- text-styles
  ;; Penpot stores text style scalars as strings (see default-text-attrs), so
  ;; stringify numeric/keyword model output to match. :fills is the text fill
  ;; list.
  [props]
  (cond-> {:fills (fills props "#000000")}
    (get props :font-family)    (assoc :font-family (->str (get props :font-family)))
    (get props :font-weight)    (assoc :font-weight (->str (get props :font-weight)))
    (get props :font-style)     (assoc :font-style (->str (get props :font-style)))
    (get props :font-size)      (assoc :font-size (->str (get props :font-size)))
    (get props :line-height)    (assoc :line-height (->str (get props :line-height)))
    (get props :letter-spacing) (assoc :letter-spacing (->str (get props :letter-spacing)))
    (get props :text-align)     (assoc :text-align (->str (get props :text-align)))))

(defn- build-text-shape [props]
  ;; Mirrors `app.plugins.api/createText`: setup-shape :text → change-text
  ;; with style overrides → dissoc :position-data. Wrapped because change-text
  ;; touches the text content tree and we'd rather drop a bad text shape than
  ;; abort the whole generation.
  (try
    (let [content (or (get props :content) "")
          styles  (text-styles props)]
      (-> (cts/setup-shape {:type      :text
                            :id        (get props :id)
                            :name      (or (get props :name) "Text")
                            :x         (num-or (get props :x) 0)
                            :y         (num-or (get props :y) 0)
                            :width     (num-or (get props :width) 1)
                            :height    (num-or (get props :height) 1)
                            :grow-type :auto-width})
          (update :content txt/change-text content styles)
          (dissoc :position-data)))
    (catch :default _
      nil)))

(defn- build-rect-shape [props]
  (cts/setup-shape {:type      :rect
                    :id        (get props :id)
                    :name      (or (get props :name) "Rectangle")
                    :x         (num-or (get props :x) 0)
                    :y         (num-or (get props :y) 0)
                    :width     (num-or (get props :width) 100)
                    :height    (num-or (get props :height) 100)
                    :r1        (num-or (get props :r1) 0)
                    :r2        (num-or (get props :r2) 0)
                    :r3        (num-or (get props :r3) 0)
                    :r4        (num-or (get props :r4) 0)
                    :fills     (fills props "#cccccc")
                    :parent-id (get props :parent-id)
                    :frame-id  (get props :frame-id)}))

(defn- build-circle-shape [props]
  (cts/setup-shape {:type      :circle
                    :id        (get props :id)
                    :name      (or (get props :name) "Ellipse")
                    :x         (num-or (get props :x) 0)
                    :y         (num-or (get props :y) 0)
                    :width     (num-or (get props :width) 100)
                    :height    (num-or (get props :height) 100)
                    :fills     (fills props "#cccccc")
                    :parent-id (get props :parent-id)
                    :frame-id  (get props :frame-id)}))

(defn- build-image-placeholder
  "Image shapes need a backend media-upload flow (see Feature 5
  `PenpotUtils.importImage`). v1 renders image specs as neutral placeholder
  rects so the model's layout still lands; true image embedding is a Feature 3
  Phase 6 polish item."
  [props]
  (cts/setup-shape {:type      :rect
                    :id        (get props :id)
                    :name      (or (get props :name) "Image")
                    :x         (num-or (get props :x) 0)
                    :y         (num-or (get props :y) 0)
                    :width     (num-or (get props :width) 100)
                    :height    (num-or (get props :height) 100)
                    :r1        (num-or (get props :r1) 0)
                    :r2        (num-or (get props :r2) 0)
                    :r3        (num-or (get props :r3) 0)
                    :r4        (num-or (get props :r4) 0)
                    :fills     [{:fill-color "#e8ddd8" :fill-opacity 1}]
                    :parent-id (get props :parent-id)
                    :frame-id  (get props :frame-id)}))

(defn- build-group-shape [props]
  (cts/setup-shape {:type      :group
                    :id        (get props :id)
                    :name      (or (get props :name) "Group")
                    :x         (num-or (get props :x) 0)
                    :y         (num-or (get props :y) 0)
                    :width     (num-or (get props :width) 0)
                    :height    (num-or (get props :height) 0)
                    :shapes    []
                    :fills     []
                    :parent-id (get props :parent-id)
                    :frame-id  (get props :frame-id)}))

(defn- build-shape
  "Build a single Penpot shape map from a spec entry, stamped with the
  resolved :id/:parent-id/:frame-id. Returns nil only if construction throws
  (text). Unknown types fall back to a rect so the model's layout still lands."
  [spec id parent-id frame-id]
  (let [props (assoc spec
                     :id        id
                     :parent-id parent-id
                     :frame-id  frame-id)
        type  (kw (get spec :type) :rect)]
    (case type
      :text   (build-text-shape props)
      :rect   (build-rect-shape props)
      :circle (build-circle-shape props)
      :image  (build-image-placeholder props)
      :group  (build-group-shape props)
      (build-rect-shape props))))

;; ── Tree assembly ───────────────────────────────────────────────────────────
;;
;; A single recursive walk. `acc` is {:objects <ordered map> :order [uuid]
;; :id-map {spec-id uuid}}. Parents are emitted before children (pre-order),
;; which is what the changes builder needs: `add-object` looks up the parent
;; in the local objects map before inserting the child.

(declare walk-node)

(defn walk-children [children-spec parent-id frame-id acc]
  (reduce (fn [acc spec] (walk-node spec parent-id frame-id acc))
          acc
          children-spec))

(defn walk-node
  "Build `spec` (a group or leaf shape) and, for groups, recurse into its
  children and stamp the group's :shapes with the child UUIDs. Top-level
  frames are handled by `spec->shape-tree`; this fn is for everything nested
  inside a frame."
  [spec parent-id frame-id acc]
  (let [id    (uuid/next)
        type  (kw (get spec :type) :rect)
        shape (build-shape spec id parent-id frame-id)]
    (if (nil? shape)
      acc
      (let [acc           (-> acc
                              (assoc-in [:id-map] (assoc (:id-map acc) (get spec :id) id))
                              (assoc-in [:objects id] shape)
                              (update :order conj id))
            children-spec (or (get spec :shapes) [])]
        (if (= type :group)
          (let [acc       (walk-children children-spec id frame-id acc)
                child-ids (into []
                                (keep #(-> acc :id-map (get (get % :id))))
                                children-spec)]
            (assoc-in acc [:objects id :shapes] child-ids))
          ;; Leaf shape. Penpot leaves don't carry :shapes; if the model
          ;; mistakenly nested children under a leaf, flatten them onto the
          ;; same parent so nothing is lost.
          (if (seq children-spec)
            (walk-children children-spec parent-id frame-id acc)
            acc))))))

(defn- build-frame [frame-spec]
  (let [id (uuid/next)]
    [id (cts/setup-shape {:type      :frame
                          :id        id
                          :name      (or (get frame-spec :name) "Board")
                          :x         (num-or (get frame-spec :x) 0)
                          :y         (num-or (get frame-spec :y) 0)
                          :width     (num-or (get frame-spec :width) 1440)
                          :height    (num-or (get frame-spec :height) 900)
                          :fills     (fills frame-spec "#ffffff")
                          :shapes    []
                          :parent-id uuid/zero
                          :frame-id  uuid/zero})]))

(defn spec->shape-tree
  "Expand a DesignSpec (Clojure data, keywordized keys) into a structure ready
  for `apply-design-spec`:

      {:objects      <ordered map uuid->shape>}  ;; parent emitted before child
      :order        [uuid ...]                   ;; insertion order, parent-first
      :id-map       {spec-id uuid}               ;; spec ids → real uuids
      :interactions [{:shape-id uuid :interaction <valid interaction>}]
      :flows        [{:id uuid :name :starting-frame uuid}]}

  Pure: mints uuids, validates interactions, and drops anything invalid
  rather than throwing."
  [spec]
  (let [frames (or (get spec :frames) [])
        acc0   {:objects (array-map) :order [] :id-map {}}
        acc    (reduce
                (fn [acc frame-spec]
                  (let [[frame-id frame-shape] (build-frame frame-spec)
                        acc           (-> acc
                                         (assoc-in [:id-map] (assoc (:id-map acc) (get frame-spec :id) frame-id))
                                         (assoc-in [:objects frame-id] frame-shape)
                                         (update :order conj frame-id))
                        children-spec (or (get frame-spec :shapes) [])
                        acc           (walk-children children-spec frame-id frame-id acc)
                        child-ids     (into []
                                            (keep #(-> acc :id-map (get (get % :id))))
                                            children-spec)]
                    (assoc-in acc [:objects frame-id :shapes] child-ids)))
                acc0
                frames)
        id-map       (:id-map acc)
        interactions (into []
                           (keep (fn [i]
                                   (when-let [interaction (spec->interaction i id-map)]
                                     {:shape-id (get id-map (get i :shape))
                                      :interaction interaction})))
                           (or (get spec :interactions) []))
        flows        (into []
                           (keep (fn [f]
                                   (let [start (get id-map (get f :starting-frame))]
                                     (when start
                                       {:id            (uuid/next)
                                        :name          (or (get f :name) "Flow")
                                        :starting-frame start}))))
                           (or (get spec :flows) []))]
    {:objects      (:objects acc)
     :order        (:order acc)
     :id-map       id-map
     :interactions interactions
     :flows        flows}))