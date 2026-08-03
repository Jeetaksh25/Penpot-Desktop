;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.viewer.shapes
  "The main container for a frame in viewer mode"
  (:require
   [app.common.data :as d]
   [app.common.expressions :as cexpr]
   [app.common.files.helpers :as cfh]
   [app.common.geom.shapes :as gsh]
   [app.common.types.shape.interactions :as ctsi]
   [app.common.types.tokens-lib :as ctob]
   [app.common.uuid :as uuid]
   [app.main.data.viewer :as dv]
   [app.main.refs :as refs]
   [app.main.router :as rt]
   [app.main.store :as st]
   [app.main.ui.shapes.bool :as bool]
   [app.main.ui.shapes.circle :as circle]
   [app.main.ui.shapes.frame :as frame]
   [app.main.ui.shapes.group :as group]
   [app.main.ui.shapes.image :as image]
   [app.main.ui.shapes.path :as path]
   [app.main.ui.shapes.rect :as rect]
   [app.main.ui.shapes.shape :refer [shape-container]]
   [app.main.ui.shapes.svg-raw :as svg-raw]
   [app.main.ui.shapes.text :as text]
   [app.util.dom :as dom]
   [app.util.object :as obj]
   [app.util.timers :as tm]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def base-frame-ctx (mf/create-context nil))
(def frame-offset-ctx (mf/create-context nil))

(def ^:private ref:viewer-show-interactions
  (l/derived :show-interactions refs/viewer-local))

;; P2.09/P2.21: runtime style overrides + error-state are top-level potok slices
;; written by `dv/set-style` / `dv/set-error-state`. Derived here so the generic
;; wrapper re-renders reactively when a :set-style / :set-error-state action
;; mutates them — otherwise the action fires but the canvas never reflects the
;; change (the slices were read only for the `["error-state" id]` condition
;; predicate, not for rendering).
(def ^:private ref:viewer-style-overrides
  (l/derived :viewer-style-overrides st/state))

(def ^:private ref:viewer-error-state
  (l/derived :viewer-error-state st/state))

(defn- find-relative-to-base-frame
  [shape objects overlays-ids base-frame]
  (cond
    (cfh/frame-shape? shape) shape
    (or (empty? overlays-ids) (nil? shape) (cfh/root? shape)) base-frame
    :else (find-relative-to-base-frame (cfh/get-parent objects (:id shape)) objects overlays-ids base-frame)))

(defn- ignore-frame-shape
  [shape objects manual?]
  (let [shape (cond-> shape ;; When the the interaction is not manual and its origin is a frame,
                ;; we need to ignore it on all the find-frame calculations
                (and (:frame-id shape) (not manual?))
                (assoc :type :rect))
        objects (assoc objects (:id shape) shape)]
    [shape objects]))

;; P0.16/P1.36/P2.21 prototype logic runtime: build the cexpr lookup closure
;; from @st/state. The closure resolves the three reference-node kinds per the
;; C2 shared contract:
;;   ["get" name]        -> variable value (token by name, then override)
;;   ["prop" id prop]    -> shape property (keyword or string key)
;;   ["error-state" id]  -> boolean membership in :viewer-error-state
;; `objects` is the current frame's object map (for ["prop" ...]). The variable
;; and error-state slices are read live from the store. Nil-safe throughout.
(defn- runtime-lookup
  [objects]
  (fn [node]
    (when (and (vector? node) (seq node))
      (let [kind (first node)]
        (cond
          (= kind "get")
          (let [var-name (second node)]
            (when (string? var-name)
              (let [state      @st/state
                    file       (get-in state [:viewer :file])
                    tokens-lib (some-> file :data :tokens-lib)]
                (when (and tokens-lib (ctob/tokens-lib? tokens-lib))
                  (let [token (get (ctob/get-all-tokens-map tokens-lib) var-name)]
                    (when (map? token)
                      (let [tid (:id token)]
                        (when (uuid? tid)
                          (or (get-in state [:viewer-variables tid])
                              (:value token))))))))))

          (= kind "prop")
          (let [raw-id (second node)
                prop   (nth node 2 nil)]
            (let [sid (cond
                        (uuid? raw-id) raw-id
                        (string? raw-id) (uuid/parse* raw-id)
                        :else nil)
                  prop-kw (cond
                            (keyword? prop) prop
                            (string? prop) (keyword prop)
                            :else nil)]
              (when (and sid prop-kw)
                (let [shape (get objects sid)]
                  (when (map? shape)
                    (get shape prop-kw))))))

          (= kind "error-state")
          (let [raw-id (second node)]
            (let [sid (cond
                        (uuid? raw-id) raw-id
                        (string? raw-id) (uuid/parse* raw-id)
                        :else nil)]
              (when sid
                (contains? (:viewer-error-state @st/state) sid))))

          :else nil)))))

;; Resolve the target shape id for :set-style / :set-error-state / similar
;; actions. :this -> the source shape's id; :by-id -> :target-shape-id. Falls
;; back to the source shape id when the by-id target is missing.
(defn- resolve-target-id
  [interaction shape]
  (let [target (:target interaction)]
    (cond
      (= target :by-id) (or (:target-shape-id interaction) (:id shape))
      :else (:id shape))))

;; P2.09: apply a per-shape runtime style override map (`{property -> value}`,
;; or nil) to a shape BEFORE rendering so a :set-style interaction is actually
;; visible on the canvas. Each authored property maps onto the shape's render
;; fields. Nil-safe + additive — an empty/nil override returns the shape
;; byte-identical, so behaviour is unchanged when :set-style is inactive. Values
;; are authored in the panel: colors for :fill/:border-color, a 0..1 number for
;; :opacity, px numbers for :border-width/:radius/:typography-size. Strokes are
;; mapped in place when present (preserving the other stroke fields) or a
;; minimal solid stroke is introduced when bordering a borderless shape.
(defn- apply-style-overrides
  [shape overrides]
  (if (or (nil? overrides) (empty? overrides))
    shape
    (reduce-kv
     (fn [sh prop value]
       (case prop
         :opacity
         (assoc sh :opacity value)
         :fill
         (assoc sh :fills [{:fill-color (str value) :fill-opacity 1}])
         :border-color
         (let [strokes (or (:strokes sh)
                           [{:stroke-style :solid :stroke-color "#000000" :stroke-width 1}])
               strokes (mapv #(assoc % :stroke-color (str value)) strokes)]
           (assoc sh :strokes strokes))
         :border-width
         (let [strokes (or (:strokes sh)
                           [{:stroke-style :solid :stroke-color "#000000" :stroke-width 1}])
               strokes (mapv #(assoc % :stroke-width value) strokes)]
           (assoc sh :strokes strokes))
         :radius
         (assoc sh :r1 value :r2 value :r3 value :r4 value)
         :typography-size
         (assoc-in sh [:typography :font-size] value)
         sh))
     shape
     overrides)))

(defn activate-interaction
  ([interaction shape base-frame frame-offset objects overlays]
   (activate-interaction interaction shape base-frame frame-offset objects overlays 0))
  ([interaction shape base-frame frame-offset objects overlays depth]
   ;; Figma #73: a disabled interaction does nothing at runtime. Absent
   ;; :disabled = enabled, so existing behaviour is byte-identical when the
   ;; feature is inactive.
   (when-not (:disabled interaction)
    (case (:action-type interaction)
      :navigate
      (when-let [frame-id (:destination interaction)]
        (let [viewer-section (dom/get-element "viewer-section")
              scroll (if (:preserve-scroll interaction)
                       (dom/get-scroll-pos viewer-section)
                       0)]
          (st/emit! (dv/set-nav-scroll scroll)
                    (dv/go-to-frame frame-id (:animation interaction)))))

    :open-overlay
    (let [manual?                    (= :manual (:overlay-pos-type interaction))
          [shape objects]            (ignore-frame-shape shape objects manual?)
          dest-frame-id              (:destination interaction)
          dest-frame                 (get objects dest-frame-id)
          relative-to-id             (if manual?
                                       (if (= (:type shape) :frame) ;; manual interactions are always from "self"
                                         (:frame-id shape)
                                         (:id shape))
                                       (:position-relative-to interaction))
          relative-to-shape          (or (get objects relative-to-id) base-frame)
          close-click-outside        (:close-click-outside interaction)
          background-overlay         (:background-overlay interaction)
          overlays-ids               (set (map :id overlays))
          relative-to-base-frame     (find-relative-to-base-frame relative-to-shape objects overlays-ids base-frame)
          fixed-base?                (cfh/fixed? objects relative-to-id)
          [position snap-to]         (ctsi/calc-overlay-position interaction
                                                                 shape
                                                                 objects
                                                                 relative-to-shape
                                                                 relative-to-base-frame
                                                                 dest-frame
                                                                 frame-offset)]
      (when dest-frame-id
        (st/emit! (dv/open-overlay dest-frame-id
                                   position
                                   snap-to
                                   close-click-outside
                                   background-overlay
                                   (:animation interaction)
                                   fixed-base?))))

    :toggle-overlay
    (let [manual?                    (= :manual (:overlay-pos-type interaction))
          [shape objects]            (ignore-frame-shape shape objects manual?)
          dest-frame-id              (:destination interaction)
          dest-frame                 (get objects dest-frame-id)
          relative-to-id             (if (= :manual (:overlay-pos-type interaction))
                                       (if (= (:type shape) :frame) ;; manual interactions are always from "self"
                                         (:frame-id shape)
                                         (:id shape))
                                       (:position-relative-to interaction))
          relative-to-shape          (or (get objects relative-to-id) base-frame)
          overlays-ids               (set (map :id overlays))
          relative-to-base-frame     (find-relative-to-base-frame relative-to-shape objects overlays-ids base-frame)
          fixed-base?                (cfh/fixed? objects (:id base-frame))
          [position snap-to]         (ctsi/calc-overlay-position interaction
                                                                 shape
                                                                 objects
                                                                 relative-to-shape
                                                                 relative-to-base-frame
                                                                 dest-frame
                                                                 frame-offset)

          close-click-outside        (:close-click-outside interaction)
          background-overlay         (:background-overlay interaction)]
      (when dest-frame-id
        (st/emit! (dv/toggle-overlay dest-frame-id
                                     position
                                     snap-to
                                     close-click-outside
                                     background-overlay
                                     (:animation interaction)
                                     fixed-base?))))

    :close-overlay
    (let [dest-frame-id (or (:destination interaction)
                            (if (and (= (:type shape) :frame)
                                     (some #(= (:id %) (:id shape)) overlays))
                              (:id shape)
                              (:frame-id shape)))]
      (st/emit! (dv/close-overlay dest-frame-id (:animation interaction))))

    :prev-screen
    (st/emit! (rt/nav-back-local))

    :open-url
    (st/emit! (dom/open-new-window (:url interaction)))

    ;; Figma #10: change-to variant action (interactive components).
    ;; v1 DEFERRED at runtime: swapping an instance's variant on trigger
    ;; requires resolving the target component-instance by id, finding the
    ;; matching variant in its component set by property values, and emitting
    ;; an instance swap through the existing change pipeline. That path is
    ;; high blast-radius (touches core component render/sync) and cannot be
    ;; verified without a build, so it is intentionally a no-op here. The
    ;; schema + UI authoring surface is fully wired; see Figma_Parity.md #10.
    :change-to
    nil

    ;; Figma #73: swap-overlay replaces the currently-open overlay with
    ;; another overlay frame, reusing the overlay positioning settings.
    ;; v1 DEFERRED at runtime: it needs to look up which overlay is currently
    ;; open for this interaction's source and close it before opening the new
    ;; one (reuse dv/close-overlay + dv/open-overlay). The "which overlay is
    ;; open" resolution depends on the viewer overlay state shape and is not
    ;; safely additive without a build, so it is a no-op here. Schema + UI
    ;; authoring is wired; see Figma_Parity.md #73.
    :swap-overlay
    nil

    ;; Figma #73: scroll-to scrolls the #viewer-section viewport to bring the
    ;; target object into view. The target id is taken from :scroll-to-target
    ;; (a non-frame shape) or :destination (a frame). We locate the rendered
    ;; DOM node by its `shape-<id>` id (set by shape-container) and compute the
    ;; scroll offset from its bounding rect relative to the viewer-section.
    ;; Nil-safe when the target or its node is missing.
    :scroll-to
    (let [raw-target (or (:scroll-to-target interaction) (:destination interaction))
          tid        (cond
                       (uuid? raw-target)    raw-target
                       (string? raw-target)  (uuid/parse* raw-target)
                       :else                 nil)]
      (when-let [tid tid]
        (let [viewer-section (dom/get-element "viewer-section")
              shape-node     (when viewer-section
                               (dom/query viewer-section (str "#shape-" (str tid))))]
          (when (and viewer-section shape-node)
            (let [section-rect (dom/get-bounding-rect viewer-section)
                  node-rect    (dom/get-bounding-rect shape-node)]
              (when (and section-rect node-rect)
                (let [cur-top   (.-scrollTop ^js viewer-section)
                      delta-y   (- (:top node-rect) (:top section-rect))
                      section-h (:height section-rect)
                      node-h    (:height node-rect)
                      offset    (/ (max 0 (- section-h node-h)) 2)
                      new-top   (+ cur-top delta-y (- offset))]
                  (dom/set-scroll-pos! viewer-section new-top))))))))

    ;; P0.16: set-variable writes a runtime variable override. :expression is
    ;; evaluated through the cexpr evaluator with the runtime-lookup closure;
    ;; :value is the plain fallback. Nil-safe when :variable-id is absent.
    :set-variable
    (let [variable-id (:variable-id interaction)
          lookup      (runtime-lookup objects)
          v           (if (:expression interaction)
                        (cexpr/eval (:expression interaction) lookup)
                        (:value interaction))]
      ;; Idempotent: skip when the value is unchanged. Breaks a
      ;; :variable-changed -> :set-variable feedback loop at the source for
      ;; convergent writes (a value that settles). A divergent `x = x+1` is
      ;; bounded by the rate limit in viewer.cljs's :variable-changed effect
      ;; so the viewer never freezes.
      (when (and variable-id (uuid? variable-id))
        (let [current (get-in @st/state [:viewer-variables variable-id] ::absent)]
          (when (not= current v)
            (st/emit! (dv/set-variable variable-id v))))))

    ;; P0.16: set-variable-mode switches a token collection's active mode and
    ;; re-resolves every token's value under :mode-name. The viewer tokens-lib
    ;; snapshot exposes only the single already-resolved :value (for the
    ;; active mode), not per-mode values, and switching a collection's active
    ;; mode + re-resolving would mutate the read-only snapshot. Intentionally
    ;; a NON-DESTRUCTIVE no-op: writing each token's current :value would
    ;; silently reset mode-specific overrides to the default-mode value, which
    ;; is worse than not switching. The :variable-changed rate limit +
    ;; idempotent :set-variable keep this safe. Per-mode resolution is deferred
    ;; to a tokens-lib active-mode re-resolution API.
    :set-variable-mode
    nil

    ;; P0.06: conditional evaluates :condition through cexpr and dispatches
    ;; :then-actions or :else-actions. Recursion is depth-guarded (max 8) to
    ;; prevent infinite loops in mutually-referential conditionals.
    :conditional
    (when (< depth 8)
      (let [lookup        (runtime-lookup objects)
            condition     (or (:condition interaction) ["and"])
            then-actions  (:then-actions interaction)
            else-actions  (:else-actions interaction)]
        (if (cexpr/truthy? condition lookup)
          (doseq [a then-actions]
            (activate-interaction a shape base-frame frame-offset objects overlays (inc depth)))
          (doseq [a else-actions]
            (activate-interaction a shape base-frame frame-offset objects overlays (inc depth))))))

    ;; P2.09: set-style mutates a runtime style property on the target shape.
    ;; Target id resolves from :this (source shape) or :by-id (:target-shape-id).
    ;; :expression is evaluated when present, else :value is used directly.
    :set-style
    (let [target-id (resolve-target-id interaction shape)
          lookup    (runtime-lookup objects)
          property  (:property interaction)
          v         (if (:expression interaction)
                      (cexpr/eval (:expression interaction) lookup)
                      (:value interaction))]
      (when (and target-id (keyword? property))
        (st/emit! (dv/set-style target-id property v))))

    ;; P2.21: set-error-state sets or clears a shape's error-state flag.
    ;; Absent :error? defaults to true (set the error state).
    :set-error-state
    (let [target-id (resolve-target-id interaction shape)
          error?    (true? (get interaction :error? true))]
      (when target-id
        (st/emit! (dv/set-error-state target-id error?))))

    ;; P0.17: scroll-animate is a continuous scroll-driven animation binding.
    ;; It is applied by the scroll handler on each scroll event (see
    ;; viewer.cljs), not on a discrete interaction trigger, so it is a no-op
    ;; in the discrete activate path.
    :scroll-animate
    nil

    nil))))

;; Perform the opposite action of an interaction, if possible
(defn- deactivate-interaction
  [interaction shape base-frame frame-offset objects overlays]
  ;; Figma #73: a disabled interaction does nothing at runtime (mirrors
  ;; activate-interaction). Absent :disabled = enabled.
  (when-not (:disabled interaction)
    (case (:action-type interaction)
      :open-overlay
      (let [frame-id (or (:destination interaction)
                         (if (= (:type shape) :frame)
                           (:id shape)
                           (:frame-id shape)))]
        (st/emit! (dv/close-overlay frame-id)))

    :toggle-overlay
    (let [manual?                    (= :manual (:overlay-pos-type interaction))
          [shape objects]            (ignore-frame-shape shape objects manual?)
          dest-frame-id              (:destination interaction)
          dest-frame                 (get objects dest-frame-id)
          relative-to-id             (if (= :manual (:overlay-pos-type interaction))
                                       (if (= (:type shape) :frame) ;; manual interactions are always from "self"
                                         (:frame-id shape)
                                         (:id shape))
                                       (:position-relative-to interaction))
          relative-to-shape          (or (get objects relative-to-id) base-frame)
          overlays-ids               (set (map :id overlays))
          relative-to-base-frame     (find-relative-to-base-frame relative-to-shape objects overlays-ids base-frame)
          fixed-base?                (cfh/fixed? objects (:id base-frame))
          [position snap-to]         (ctsi/calc-overlay-position interaction
                                                                 shape
                                                                 objects
                                                                 relative-to-shape
                                                                 relative-to-base-frame
                                                                 dest-frame
                                                                 frame-offset)

          close-click-outside        (:close-click-outside interaction)
          background-overlay         (:background-overlay interaction)]
      (when dest-frame-id
        (st/emit! (dv/toggle-overlay dest-frame-id
                                     position
                                     snap-to
                                     close-click-outside
                                     background-overlay
                                     (:animation interaction)
                                     fixed-base?))))


    :close-overlay
    (let [manual?                    (= :manual (:overlay-pos-type interaction))
          [shape objects]            (ignore-frame-shape shape objects manual?)
          dest-frame-id              (:destination interaction)
          dest-frame                 (get objects dest-frame-id)
          relative-to-id             (if (= :manual (:overlay-pos-type interaction))
                                       (if (= (:type shape) :frame) ;; manual interactions are always from "self"
                                         (:frame-id shape)
                                         (:id shape))
                                       (:position-relative-to interaction))
          relative-to-shape          (or (get objects relative-to-id) base-frame)
          close-click-outside        (:close-click-outside interaction)
          background-overlay         (:background-overlay interaction)
          overlays-ids               (set (map :id overlays))
          relative-to-base-frame     (find-relative-to-base-frame relative-to-shape objects overlays-ids base-frame)
          fixed-base?                (cfh/fixed? objects (:id base-frame))
          [position snap-to]         (ctsi/calc-overlay-position interaction
                                                                 shape
                                                                 objects
                                                                 relative-to-shape
                                                                 relative-to-base-frame
                                                                 dest-frame
                                                                 frame-offset)]
      (when dest-frame-id
        (st/emit! (dv/open-overlay dest-frame-id
                                   position
                                   snap-to
                                   close-click-outside
                                   background-overlay
                                   (:animation interaction)
                                   fixed-base?))))
    nil)))

(defn- on-pointer-down
  [event shape base-frame frame-offset objects overlays]
  (let [interactions (->> (:interactions shape)
                          (filter #(or (= (:event-type %) :click)
                                       (= (:event-type %) :mouse-press))))]
    (when (seq interactions)
      (dom/stop-propagation event)
      (doseq [interaction interactions]
        (activate-interaction interaction shape base-frame frame-offset objects overlays)))))

(defn- on-pointer-up
  [event shape base-frame frame-offset objects overlays]
  (let [interactions (->> (:interactions shape)
                          (filter #(= (:event-type %) :mouse-press)))]
    (when (seq interactions)
      (dom/stop-propagation event)
      (doseq [interaction interactions]
        (deactivate-interaction interaction shape base-frame frame-offset objects overlays)))))

(defn- on-pointer-enter
  [event shape base-frame frame-offset objects overlays]
  (let [interactions (->> (:interactions shape)
                          (filter #(or (= (:event-type %) :mouse-enter)
                                       (= (:event-type %) :mouse-over))))]
    (when (seq interactions)
      (dom/stop-propagation event)
      (doseq [interaction interactions]
        (activate-interaction interaction shape base-frame frame-offset objects overlays)))))

(defn- on-pointer-leave
  [event shape base-frame frame-offset objects overlays]
  (let [interactions     (->> (:interactions shape)
                              (filter #(= (:event-type %) :mouse-leave)))
        interactions-inv (->> (:interactions shape)
                              (filter #(= (:event-type %) :mouse-over)))]
    (when (or (seq interactions) (seq interactions-inv))
      (dom/stop-propagation event)
      (doseq [interaction interactions]
        (activate-interaction interaction shape base-frame frame-offset objects overlays))
      (doseq [interaction interactions-inv]
        (deactivate-interaction interaction shape base-frame frame-offset objects overlays)))))

(defn- on-load
  [shape base-frame frame-offset objects overlays]
  (let [interactions (->> (:interactions shape)
                          (filter #(= (:event-type %) :after-delay)))]
    (loop [interactions (seq interactions)
           sems []]
      (if-let [interaction (first interactions)]
        (let [sem (tm/schedule (:delay interaction)
                               #(activate-interaction interaction shape base-frame frame-offset objects overlays))]
          (recur (next interactions)
                 (conj sems sem)))
        sems))))

(mf/defc interaction
  [{:keys [shape interactions show-interactions]}]
  (let [{:keys [x y width height]} (:selrect shape)]
    (when-not (empty? interactions)
      [:rect {:x (- x 1)
              :y (- y 1)
              :width (+ width 2)
              :height (+ height 2)
              :fill "var(--color-accent-tertiary)"
              :stroke "var(--color-accent-tertiary)"
              :stroke-width (if show-interactions 1 0)
              :fill-opacity (if show-interactions 0.2 0)
              :pointer-events "none"
              :transform (gsh/transform-str shape)}])))

;; P2.21: error-state visual effect. When a shape is flagged in
;; `:viewer-error-state` (via a :set-error-state action), render a clear error
;; outline around its bounds so the error is VISIBLE — not merely usable as a
;; `["error-state" id]` condition predicate. A dashed rose-red 2px stroke; pure
;; static geometry (no motion), so the reduced-motion guard is satisfied
;; trivially. Shares the wrapper's coordinate space + transform, mirroring the
;; `interaction` hotspot rect above.
(mf/defc error-highlight*
  [{:keys [shape]}]
  (let [{:keys [x y width height]} (:selrect shape)]
    [:rect {:x (- x 1)
            :y (- y 1)
            :width (+ width 2)
            :height (+ height 2)
            :fill "none"
            :stroke "#ef4444"
            :stroke-width 2
            :stroke-dasharray "4 3"
            :pointer-events "none"
            :transform (gsh/transform-str shape)}]))

;; --- WASM viewer hotspots ---
;; In WASM viewer mode the frame pixels come from a WASM snapshot, so we don't
;; render the SVG visuals at all. We only render the actionable areas (hotspots)
;; on top of the image: a transparent hit/highlight rect per interactive shape,
;; wired to the same interaction handlers as the regular SVG tree.

(mf/defc hotspot*
  [{:keys [shape all-objects]}]
  (let [base-frame   (mf/use-ctx base-frame-ctx)
        frame-offset (mf/use-ctx frame-offset-ctx)
        show-interactions (mf/deref ref:viewer-show-interactions)
        overlays     (mf/deref refs/viewer-overlays)
        interactions (:interactions shape)
        {:keys [x y width height]} (:selrect shape)

        on-pd (mf/use-fn (mf/deps shape base-frame frame-offset all-objects overlays)
                         #(on-pointer-down % shape base-frame frame-offset all-objects overlays))
        on-pu (mf/use-fn (mf/deps shape base-frame frame-offset all-objects overlays)
                         #(on-pointer-up % shape base-frame frame-offset all-objects overlays))
        on-pe (mf/use-fn (mf/deps shape base-frame frame-offset all-objects overlays)
                         #(on-pointer-enter % shape base-frame frame-offset all-objects overlays))
        on-pl (mf/use-fn (mf/deps shape base-frame frame-offset all-objects overlays)
                         #(on-pointer-leave % shape base-frame frame-offset all-objects overlays))]

    (mf/with-effect []
      (let [sems (on-load shape base-frame frame-offset all-objects overlays)]
        (partial run! tm/dispose! sems)))

    [:g {:style {:cursor (when (ctsi/actionable? interactions) "pointer")}
         :on-pointer-down on-pd
         :on-pointer-up on-pu
         :on-pointer-enter on-pe
         :on-pointer-leave on-pl}
     [:rect {:x (- x 1)
             :y (- y 1)
             :width (+ width 2)
             :height (+ height 2)
             :fill "var(--color-accent-tertiary)"
             :stroke "var(--color-accent-tertiary)"
             :stroke-width (if show-interactions 1 0)
             :fill-opacity (if show-interactions 0.2 0)
             ;; This rect is the only hit target, so it must always capture
             ;; pointer events even when fully transparent.
             :pointer-events "all"
             :transform (gsh/transform-str shape)}]]))

(mf/defc frame-hotspots*
  "Renders interaction hotspots for a frame subtree (WASM viewer mode).
  `objects` must be the prepared (vbox-space) objects and `frame` the prepared
  frame; only shapes with interactions produce a hotspot.

  Optional `shape-filter` is a predicate that receives the shape id and returns
  true when it should be included (used to split fixed-scroll vs normal layers)."
  [{:keys [objects all-objects shape-filter frame]}]
  (let [all-objects (or all-objects objects)
        frame-id    (:id frame)
        ids         (cond->> (cons frame-id (cfh/get-children-ids objects frame-id))
                      shape-filter (filter shape-filter))
        hotspots    (->> ids
                         (keep #(get objects %))
                         (filter (fn [s] (and (not (:hidden s))
                                              (seq (:interactions s))))))]
    [:g {}
     (for [shape hotspots]
       [:> hotspot* {:key (str (:id shape))
                     :shape shape
                     :all-objects all-objects}])]))


;; TODO: use-memo use-fn

(defn generic-wrapper-factory
  "Wrap some svg shape and add interaction controls"
  [component]
  (mf/fnc generic-wrapper
    {::mf/wrap-props false}
    [props]
    (let [shape              (unchecked-get props "shape")
          childs             (unchecked-get props "childs")
          frame              (unchecked-get props "frame")
          objects            (unchecked-get props "objects")
          all-objects        (or (unchecked-get props "all-objects") objects)
          base-frame         (mf/use-ctx base-frame-ctx)
          frame-offset       (mf/use-ctx frame-offset-ctx)
          show-interactions  (mf/deref ref:viewer-show-interactions)
          overlays           (mf/deref refs/viewer-overlays)
          interactions       (:interactions shape)
          svg-element?       (and (= :svg-raw (:type shape))
                                  (not= :svg (get-in shape [:content :tag])))

          ;; P2.09/P2.21: reactive reads of the runtime style-override + error
          ;; slices so the wrapper re-renders when a :set-style / :set-error
          ;; action mutates them. `effective-shape` carries the overridden render
          ;; props; `in-error?` drives the error outline. Pointer handlers keep
          ;; closing over the ORIGINAL `shape` (interactions don't depend on the
          ;; visual overrides, and the id is unchanged).
          style-overrides    (mf/deref ref:viewer-style-overrides)
          error-state        (mf/deref ref:viewer-error-state)
          effective-shape    (apply-style-overrides shape (get style-overrides (:id shape)))
          in-error?          (contains? error-state (:id shape))

          ;; The objects parameter has the shapes that we must draw. It may be a subset of
          ;; all-objects in some cases (e.g. if there are fixed elements). But for interactions
          ;; handling we need access to all objects inside the page.

          on-pointer-down
          (mf/use-fn (mf/deps shape base-frame frame-offset all-objects)
                     #(on-pointer-down % shape base-frame frame-offset all-objects overlays))

          on-pointer-up
          (mf/use-fn (mf/deps shape base-frame frame-offset all-objects)
                     #(on-pointer-up % shape base-frame frame-offset all-objects overlays))

          on-pointer-enter
          (mf/use-fn (mf/deps shape base-frame frame-offset all-objects)
                     #(on-pointer-enter % shape base-frame frame-offset all-objects overlays))

          on-pointer-leave
          (mf/use-fn (mf/deps shape base-frame frame-offset all-objects)
                     #(on-pointer-leave % shape base-frame frame-offset all-objects overlays))]

      (mf/with-effect []
        (let [sems (on-load shape base-frame frame-offset objects overlays)]
          (partial run! tm/dispose! sems)))

      (if-not svg-element?
        [:> shape-container {:shape effective-shape
                             :cursor (when (ctsi/actionable? interactions) "pointer")
                             ;; P0.17: data-shape-id lets the scroll-driven
                             ;; animation handler locate this shape's DOM node
                             ;; to apply interpolated keyframe props.
                             :data-shape-id (str (:id shape))
                             :on-pointer-down on-pointer-down
                             :on-pointer-up on-pointer-up
                             :on-pointer-enter on-pointer-enter
                             :on-pointer-leave on-pointer-leave}

         [:& component {:shape effective-shape
                        :frame frame
                        :childs childs
                        :is-child-selected? true
                        :objects objects}]

         [:& interaction {:shape shape
                          :interactions interactions
                          :show-interactions show-interactions}]

         ;; P2.21: error-state outline (rendered only when the shape is flagged).
         (when in-error?
           [:& error-highlight* {:shape shape}])]

        ;; Don't wrap svg elements inside a <g> otherwise some can break
        [:*
         [:& component {:shape effective-shape
                        :frame frame
                        :childs childs
                        :objects objects}]
         ;; P2.21: error-state outline for svg-raw shapes too.
         (when in-error?
           [:& error-highlight* {:shape shape}])]))))

(defn frame-wrapper
  [shape-container]
  (generic-wrapper-factory (frame/frame-shape shape-container)))

(defn group-wrapper
  [shape-container]
  (generic-wrapper-factory (group/group-shape shape-container)))

(defn bool-wrapper
  [shape-container]
  (generic-wrapper-factory (bool/bool-shape shape-container)))

(defn svg-raw-wrapper
  [shape-container]
  (generic-wrapper-factory (svg-raw/svg-raw-shape shape-container)))

(defn rect-wrapper
  []
  (generic-wrapper-factory rect/rect-shape))

(defn image-wrapper
  []
  (generic-wrapper-factory image/image-shape))

(defn path-wrapper
  []
  (generic-wrapper-factory path/path-shape))

(defn text-wrapper
  []
  (generic-wrapper-factory text/text-shape))

(defn circle-wrapper
  []
  (generic-wrapper-factory circle/circle-shape))

(declare shape-container-factory)

(defn frame-container-factory
  [objects all-objects]
  (let [shape-container (shape-container-factory objects all-objects)
        frame-wrapper   (frame-wrapper shape-container)
        lookup-xf       (keep (d/getf objects))]
    (mf/fnc frame-container
      {::mf/wrap-props false}
      [props]
      (let [shape  (unchecked-get props "shape")
            childs (into [] lookup-xf (:shapes shape))
            props  (obj/merge props
                              #js {:childs childs
                                   :objects objects
                                   :all-objects all-objects})]
        [:> frame-wrapper props]))))

(defn group-container-factory
  [objects all-objects]
  (let [shape-container (shape-container-factory objects all-objects)
        group-wrapper (group-wrapper shape-container)]
    (mf/fnc group-container
      {::mf/wrap-props false}
      [props]
      (let [childs   (mapv #(get objects %) (:shapes (unchecked-get props "shape")))
            props    (obj/merge! #js {} props
                                 #js {:childs childs
                                      :objects objects
                                      :all-objects all-objects})]
        (when (not-empty childs)
          [:> group-wrapper props])))))

(defn bool-container-factory
  [objects all-objects]
  (let [shape-container (shape-container-factory objects all-objects)
        bool-wrapper (bool-wrapper shape-container)]
    (mf/fnc bool-container
      {::mf/wrap-props false}
      [props]
      (let [childs (->> (cfh/get-children-ids objects (:id (unchecked-get props "shape")))
                        (select-keys objects))
            props  (obj/merge! #js {} props
                               #js {:childs childs
                                    :objects objects})]
        [:> bool-wrapper props]))))

(defn svg-raw-container-factory
  [objects all-objects]
  (let [shape-container (shape-container-factory objects all-objects)
        svg-raw-wrapper (svg-raw-wrapper shape-container)]
    (mf/fnc svg-raw-container
      {::mf/wrap-props false}
      [props]
      (let [childs (mapv #(get objects %) (:shapes (unchecked-get props "shape")))
            props  (obj/merge! #js {} props
                               #js {:childs childs
                                    :objects objects})]
        [:> svg-raw-wrapper props]))))

(defn shape-container-factory
  [objects all-objects]
  (let [path-wrapper   (path-wrapper)
        text-wrapper   (text-wrapper)
        rect-wrapper   (rect-wrapper)
        image-wrapper  (image-wrapper)
        circle-wrapper (circle-wrapper)]
    (mf/fnc shape-container
      {::mf/wrap-props false
       ::mf/wrap [mf/memo]}
      [props]
      (let [shape   (unchecked-get props "shape")
            frame   (unchecked-get props "frame")

            group-container
            (mf/with-memo [objects]
              (group-container-factory objects all-objects))

            frame-container
            (mf/with-memo [objects]
              (frame-container-factory objects all-objects))

            bool-container
            (mf/with-memo [objects]
              (bool-container-factory objects all-objects))

            svg-raw-container
            (mf/with-memo [objects]
              (svg-raw-container-factory objects all-objects))]
        (when (and shape (not (:hidden shape)))
          (let [shape (if frame
                        (gsh/translate-to-frame shape frame)
                        shape)

                opts #js {:shape shape
                          :objects objects
                          :all-objects all-objects}]
            (case (:type shape)
              :frame   [:> frame-container opts]
              :text    [:> text-wrapper opts]
              :rect    [:> rect-wrapper opts]
              :path    [:> path-wrapper opts]
              :image   [:> image-wrapper opts]
              :circle  [:> circle-wrapper opts]
              :group   [:> group-container {:shape shape :frame frame :objects objects}]
              :bool    [:> bool-container {:shape shape :frame frame :objects objects}]
              :svg-raw [:> svg-raw-container {:shape shape :frame frame :objects objects}])))))))
