;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.shapes.path.editor
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as pcb]
   [app.common.geom.point :as gpt]
   [app.common.math :as mth]
   [app.common.types.path :as path]
   [app.common.types.path.arrangement :as arrangement]
   [app.common.types.path.bool :as bool]
   [app.common.types.path.helpers :as path.helpers]
   [app.common.types.path.subpath :as subpath]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.path :as drp]
   [app.main.data.workspace.selection :as dws]
   [app.main.snap :as snap]
   [app.main.store :as st]
   [app.main.streams :as ms]
   [app.main.ui.css-cursors :as cur]
   [app.main.ui.hooks :as hooks]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [beicon.v2.core :as rx]
   [clojure.set :refer [map-invert]]
   [goog.events :as events]
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf]))

(def point-radius 5)
(def point-radius-selected 4)
(def point-radius-active-area 15)
(def point-radius-stroke-width 1)

(def handler-side 6)
(def handler-stroke-width 1)

(def path-preview-dasharray 4)
(def path-snap-stroke-width 1)

(def accent-color "var(--color-accent-tertiary)")
(def secondary-color "var(--color-accent-quaternary)")
(def black-color "var(--app-black)")
(def white-color "var(--app-white)")
(def gray-color "var(--df-secondary)")

;; Figma-parity Shape Builder (#28) / Paint Bucket (#29). Coral is the
;; Ovion AI accent (matches var(--ai-coral) / #f28b82 used by the AI
;; surfaces); used for the face hover-fill, the drag union outline and
;; the erase indicator. No CSS transitions are applied — the highlight
;; and outline are pure state (re-render on hover/drag), so there is no
;; motion to guard against prefers-reduced-motion.
(def coral-color "#f28b82")

(mf/defc path-point*
  {::mf/private true}
  [{:keys [position zoom edit-mode is-hover is-selected is-preview is-start-path is-last is-new is-curve]}]
  (let [{:keys [x y]} position

        is-draw (= edit-mode :draw)
        is-move (= edit-mode :move)

        is-active
        (or ^boolean is-selected
            ^boolean is-hover)

        on-enter
        (mf/use-fn
         (fn [_]
           (st/emit! (drp/path-pointer-enter position))))

        on-leave
        (mf/use-fn
         (fn [_]
           (st/emit! (drp/path-pointer-leave position))))

        on-pointer-down
        (fn [event]
          (when (dom/left-mouse? event)
            ;; In vector-lasso mode (#57) let the pointer-down bubble to the
            ;; root path-editor group so the lasso can start on a node; the
            ;; node-click branches below are all no-ops in lasso mode.
            (when-not (= edit-mode :vector-lasso)
              (dom/stop-propagation event))
            (dom/prevent-default event)

            ;; When clicking on a hover point that lies on a segment (has metadata with
            ;; split params), only insert the node — don't also run draw-mode actions which
            ;; would add the same position as an extra endpoint, corrupting the path order
            ;; and misplacing stroke caps.
            ;; FIXME: revisit this, using meta here breaks equality checks
            (if (and is-new (some? (meta position)))
              (st/emit! (drp/create-node-at-position (meta position)))
              (let [is-shift (kbd/shift? event)
                    is-mod   (kbd/mod? event)]
                (cond
                  is-last
                  (st/emit! (drp/reset-last-handler))

                  (and is-move is-mod (not is-curve))
                  (st/emit! (drp/make-curve position))

                  (and is-move is-mod is-curve)
                  (st/emit! (drp/make-corner position))

                  is-move
                  ;; If we're dragging a selected item we don't change the selection
                  (st/emit! (drp/start-move-path-point position is-shift))

                  (and is-draw is-start-path)
                  (st/emit! (drp/start-path-from-point position))

                  (and is-draw (not is-start-path))
                  (st/emit! (drp/close-path-drag-start position)))))))]

    [:g.path-point
     [:circle.path-point
      {:cx x
       :cy y
       :r (if ^boolean is-active
            (/ point-radius zoom)
            (/ point-radius-selected zoom))
       :style {:stroke-width (/ point-radius-stroke-width zoom)
               :stroke (cond ^boolean is-active black-color
                             ^boolean is-preview secondary-color
                             :else accent-color)
               :fill (cond is-selected accent-color
                           :else white-color)}}]
     [:circle {:cx x
               :cy y
               :r (/ point-radius-active-area zoom)
               :on-pointer-down on-pointer-down
               :on-pointer-enter on-enter
               :on-pointer-leave on-leave
               :pointer-events (when-not ^boolean is-preview "visible")
               :class (cond ^boolean is-draw (cur/get-static "pen-node")
                            ^boolean is-move (cur/get-static "pointer-node"))
               :style {:stroke-width 0
                       :fill "none"}}]]))

;; FIXME: is-selected prop looks unused

(mf/defc path-handler*
  {::mf/private true}
  [{:keys [index prefix point handler zoom is-selected is-hover edit-mode snap-angle]}]
  (let [x       (dm/get-prop handler :x)
        y       (dm/get-prop handler :y)
        is-draw (= edit-mode :draw)
        is-move (= edit-mode :move)

        is-active
        (or ^boolean is-selected
            ^boolean is-hover)

        on-enter
        (mf/use-fn
         (mf/deps index prefix)
         (fn [_] (st/emit! (drp/path-handler-enter index prefix))))

        on-leave
        (mf/use-fn
         (mf/deps index prefix)
         (fn [_] (st/emit! (drp/path-handler-leave index prefix))))

        on-pointer-down
        (mf/use-fn
         (mf/deps index prefix is-move)
         (fn [event]
           (when (dom/left-mouse? event)
             (dom/stop-propagation event)
             (dom/prevent-default event)

             (when ^boolean is-move
               (st/emit! (drp/start-move-handler index prefix))))))]

    [:g.handler {:pointer-events (if ^boolean is-draw "none" "visible")}
     [:line
      {:x1 (:x point)
       :y1 (:y point)
       :x2 x
       :y2 y
       :style {:stroke (if ^boolean is-hover
                         black-color
                         gray-color)
               :stroke-width (/ point-radius-stroke-width zoom)}}]

     (when ^boolean snap-angle
       [:line
        {:x1 (:x point)
         :y1 (:y point)
         :x2 x
         :y2 y
         :style {:stroke secondary-color
                 :stroke-width (/ point-radius-stroke-width zoom)}}])

     [:rect
      {:x (- x (/ handler-side 2 zoom))
       :y (- y (/ handler-side 2 zoom))
       :width (/ handler-side zoom)
       :height (/ handler-side zoom)

       :style {:stroke-width (/ handler-stroke-width zoom)
               :stroke (cond ^boolean is-active black-color
                             :else accent-color)
               :fill (cond ^boolean is-selected accent-color
                           :else white-color)}}]
     [:circle {:cx x
               :cy y
               :r (/ point-radius-active-area zoom)
               :on-pointer-down on-pointer-down
               :on-pointer-enter on-enter
               :on-pointer-leave on-leave
               :class (when ^boolean is-move
                        (cur/get-static "pointer-move"))
               :style {:fill "none"
                       :stroke-width 0}}]]))

;; Figma-parity variable-width stroke width handle (gap #53). Renders a
;; draggable handle perpendicular to the incoming segment at a node,
;; offset by half the node's width. Pure render — guarded on a width
;; data map being present (see path-editor* below); when no width data
;; is present nothing renders and the editor is byte-identical. The
;; renderer conversion to a filled outline path (SVG has no native
;; variable-width stroke) is DEFERRED.
(mf/defc path-width-handle*
  {::mf/private true}
  [{:keys [node position width zoom]}]
  (let [nx (dm/get-prop node :x)
        ny (dm/get-prop node :y)
        px (dm/get-prop position :x)
        py (dm/get-prop position :y)]
    [:g.width-handle {:pointer-events "none"}
     [:line
      {:x1 nx :y1 ny :x2 px :y2 py
       :style {:stroke secondary-color
               :stroke-width (/ point-radius-stroke-width zoom)}}]
     [:circle
      {:cx px :cy py
       :r (/ handler-side zoom)
       :style {:stroke-width (/ handler-stroke-width zoom)
               :stroke black-color
               :fill secondary-color}}]]))

;; Figma-parity vector lasso (#57). Local point-in-polygon helper (ray
;; casting) used to select vector nodes inside a freehand lasso polygon.
;; Conceptually reuses the canvas-level lasso logic but is self-contained
;; here so snap.cljs (owned by another group) is not touched.
(defn- point-in-polygon?
  [point poly]
  (let [px (dm/get-prop point :x)
        py (dm/get-prop point :y)
        n (count poly)]
    (if (< n 3)
      false
      (loop [i 0 j (dec n) inside false]
        (if (< i n)
          (let [pi (nth poly i)
                pj (nth poly j)
                iy (dm/get-prop pi :y)
                ix (dm/get-prop pi :x)
                jy (dm/get-prop pj :y)
                jx (dm/get-prop pj :x)
                ;; edge spans the horizontal ray at y=py (one endpoint
                ;; strictly above, the other not)
                spans? (not= (> iy py) (> jy py))
                ;; x-intersection of the edge with that ray
                x-int (if (mth/almost-zero? (- jy iy))
                        ix
                        (/ (+ (* ix (- jy py)) (* jx (- py iy))) (- jy iy)))
                inside (if (and spans? (< px x-int))
                         (not inside)
                         inside)]
            (recur (inc i) i inside))
          inside)))))

;; ---------------------------------------------------------------------------
;; Figma-parity Shape Builder (#28) + Paint Bucket (#29)
;; ---------------------------------------------------------------------------
;;
;; The path editor edits ONE shape. Shape Builder / Paint Bucket operate
;; on the SUB-PATHS of that one edited (compound) path: every sub-path is
;; fed to `arrangement/compute-arrangement` as a separate input shape so
;; crossing/overlapping sub-paths produce a planar subdivision of FACES.
;; The user then hovers a face (coral highlight), clicks to ISOLATE it
;; (Divide-equivalent), drags across faces to UNITE them
;; (`bool/calculate-content :union`), or holds Alt and clicks to ERASE a
;; face/edge (union of the remaining faces). Paint Bucket clicks a face
;; to replace the content with that filled closed region.
;;
;; All geometry is computed from the pure CLJC arrangement engine; the
;; commit goes through a LOCAL ptk/reify WatchEvent (defined here, not in
;; data/workspace/*) that reuses pcb/dch/dws exactly like
;; data/workspace/bool.cljs.

(defn- content->arrangement-shapes
  "Splits a path `content` into a vector of `{:id idx :content subpath-data}`
  maps — one per sub-path — the input format `arrangement/compute-arrangement`
  expects. Each sub-path's `:data` is a plain vector of command maps."
  [content]
  (let [subs (subpath/get-subpaths content)]
    (into []
          (map-indexed
           (fn [idx sp]
             {:id idx :content (:data sp)}))
          subs)))

(defn- build-arrangement
  "Builds the planar arrangement for `content` (or nil when the content
  has no sub-paths). Open sub-paths are treated as closed regions
  (`:open-as-closed true`) so filled open paths still subdivide the
  plane — matching how Penpot renders them."
  [content]
  (let [shapes (content->arrangement-shapes content)]
    (when (seq shapes)
      (arrangement/compute-arrangement shapes :open-as-closed true))))

(defn- bounded-faces
  "Returns the bounded faces of an arrangement (excluding the unbounded
  outer face)."
  [arr]
  (filterv :bounded? (:faces arr)))

(defn- commit-shape-content
  "LOCAL WatchEvent (Figma-parity #28/#29). Replaces the edited path
  shape's content with `new-content` (a PathData or plain content
  vector) and re-selects the shape so path-edit mode continues. Mirrors
  the commit pattern in data/workspace/bool.cljs (pcb/empty-changes ->
  pcb/with-objects -> pcb/update-shapes -> dch/commit-changes +
  dws/select-shapes) but is defined here so no shared data file is
  touched."
  [shape-id new-content]
  (ptk/reify ::commit-shape-builder-content
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id (:current-page-id state)
            objects (dsh/lookup-page-objects state page-id)]
        (when (some? (get objects shape-id))
          (let [changes
                (-> (pcb/empty-changes it page-id)
                    (pcb/with-objects objects)
                    (pcb/update-shapes [shape-id]
                                       (fn [shape]
                                         (path/update-geometry shape new-content))))]
            (rx/of (dch/commit-changes changes)
                   (dws/select-shapes (d/ordered-set shape-id)))))))))

(mf/defc path-preview*
  {::mf/private true}
  [{:keys [zoom segment from]}]

  (let [path
        (when (not= :move-to (:command segment))
          (let [segments [{:command :move-to
                           :params from}]
                segments (conj segments segment)]
            (path/content segments)))

        position
        (mf/with-memo [segment]
          ;; FIXME: use a helper from common for this
          (gpt/point (:params segment)))]

    [:g.preview {:style {:pointer-events "none"}}
     (when (some? path)
       [:path {:style {:fill "none"
                       :stroke black-color
                       :stroke-width (/ handler-stroke-width zoom)
                       :stroke-dasharray (/ path-preview-dasharray zoom)}
               :d (str path)}])

     [:> path-point* {:position position
                      :is-preview true
                      :zoom zoom}]]))

(mf/defc path-snap*
  {::mf/private true}
  [{:keys [selected points zoom]}]
  (let [ranges
        (mf/with-memo [selected points]
          (snap/create-ranges points selected))

        snap-matches
        (snap/get-snap-delta-match selected ranges (/ 1 zoom))

        matches
        (concat (second (:x snap-matches)) (second (:y snap-matches)))]

    [:g.snap-paths
     (for [[idx [from to]] (d/enumerate matches)]
       [:line {:key (dm/str "snap-" idx "-" from "-" to)
               :x1 (:x from)
               :y1 (:y from)
               :x2 (:x to)
               :y2 (:y to)
               :style {:stroke secondary-color
                       :stroke-width (/ path-snap-stroke-width zoom)}}])]))

(defn- matching-handler? [content node handlers]
  (when (= 2 (count handlers))
    (let [[[i1 p1] [i2 p2]] handlers
          p1 (path/get-handler-point content i1 p1)
          p2 (path/get-handler-point content i2 p2)

          v1 (gpt/to-vec node p1)
          v2 (gpt/to-vec node p2)

          angle (gpt/angle-with-other v1 v2)]
      (<= (- 180 angle) 0.1))))

(mf/defc path-editor*
  [{:keys [shape zoom state]}]
  (let [hover-point   (mf/use-state nil)
        editor-ref    (mf/use-ref nil)

        {:keys [edit-mode
                drag-handler
                prev-handler
                preview
                content-modifiers
                last-point
                selected-points
                moving-nodes
                moving-handler
                hover-handlers
                hover-points
                snap-toggled
                ;; Figma-parity variable-width (#53): optional per-node
                ;; width map {segment-index width}. Absent = uniform stroke.
                segment-widths]}
        state

        selected-points
        (or selected-points #{})

        base-content
        (get shape :content)

        base-points
        (mf/with-memo [base-content]
          (path/get-points base-content))

        content
        (mf/with-memo [base-content content-modifiers]
          (path/apply-content-modifiers base-content content-modifiers))

        content-points
        (mf/with-memo [content]
          (path/get-points content))

        point->base (->> (map hash-map content-points base-points) (reduce merge))
        base->point (map-invert point->base)

        points
        (mf/with-memo [content-points]
          (into #{} content-points))

        last-p
        (->> content last path.helpers/segment->point)

        handlers
        (mf/with-memo [content]
          (path/get-handlers content))

        is-path-start
        (not (some? last-point))

        show-snap?
        (and ^boolean snap-toggled
             (or (some? drag-handler)
                 (some? preview)
                 (some? moving-handler)
                 moving-nodes))

        ;; Figma-parity vector lasso (#57). Local capture state: nil when
        ;; inactive, a vector of gpt points while a lasso is being drawn.
        ;; Active only in the :vector-lasso edit-mode (Q shortcut).
        lasso-points (mf/use-state nil)
        lasso-mode? (= edit-mode :vector-lasso)

        ;; Figma-parity Shape Builder (#28) + Paint Bucket (#29). The
        ;; arrangement is the planar subdivision of the edited path's
        ;; sub-paths; it is recomputed whenever the committed content
        ;; changes (base-content is the store-backed content, so it
        ;; updates after every commit-shape-content). hover-hit holds the
        ;; current point-in-face result; drag-faces accumulates the face
        ;; ids touched during a press-drag; dragging? marks an in-flight
        ;; unite drag; alt-held? tracks the Alt/Option modifier so the
        ;; erase cursor + indicator can render. last-pos is a ref (no
        ;; re-render) keeping the latest cursor position in content
        ;; coordinates for the pointer-down handler.
        builder-or-bucket?
        (or (= edit-mode :shape-builder)
            (= edit-mode :paint-bucket))

        ;; Figma-parity Scissors tool (ALL_APPS_PARITY P2.32). A dedicated
        ;; path edit-mode where clicking near a segment splits it at the
        ;; nearest point (inserts a node). The split reuses the existing
        ;; `path/closest-point` + `drp/create-node-at-position` primitives
        ;; (the same ones the hover-preview stream / preview-node click
        ;; already use), so this mode is purely a discoverable "click to
        ;; cut" affordance + a wide invisible hit path (see the render
        ;; below) so the user need not hit the tiny preview node precisely.
        scissors-mode? (= edit-mode :scissors)

        builder-arr
        (mf/with-memo [base-content builder-or-bucket?]
          (when builder-or-bucket?
            (build-arrangement base-content)))

        face-content-map
        (mf/with-memo [builder-arr]
          (when (some? builder-arr)
            (into {}
                  (map (fn [r] [(:face-id r) (:content r)]))
                  (arrangement/divide-into-faces builder-arr))))

        hover-hit  (mf/use-state nil)
        drag-faces (mf/use-state nil)
        dragging?  (mf/use-state nil)
        alt-held?  (mf/use-state false)
        last-pos   (mf/use-ref nil)

        ;; Figma-parity variable-width width handles (#53). Computed only
        ;; when a non-empty segment-widths map is present; nil otherwise
        ;; (the render guard below skips the whole group).
        width-handles
        (mf/with-memo [content segment-widths]
          (path/width-handles content segment-widths))

        on-lasso-pointer-down
        (mf/use-fn
         (mf/deps lasso-mode?)
         (fn [event]
           (when lasso-mode?
             (dom/stop-propagation event)
             (dom/prevent-default event)
             ;; start a fresh lasso capture
             (reset! lasso-points []))))

        on-lasso-finish
        (mf/use-fn
         (mf/deps lasso-mode? content zoom)
         (fn []
           (when (and lasso-mode? (some? @lasso-points))
             (let [poly @lasso-points]
               ;; keep nodes whose anchor lies inside the lasso polygon
               (when (>= (count poly) 3)
                 (let [inside (seq (filter #(point-in-polygon? % poly) points))]
                   (when (some? inside)
                     (let [base-pts (map #(get point->base % %) inside)
                           ;; first replaces the selection, the rest are
                           ;; added (shift) — reuses the existing select-node
                           ;; event (selection.cljs, not owned by this group)
                           events (cons (drp/select-node (first base-pts) false)
                                        (map #(drp/select-node % true)
                                             (rest base-pts)))]
                       (apply st/emit! events)))))
               (reset! lasso-points nil)))))]

        ;; Figma-parity Scissors tool (ALL_APPS_PARITY P2.32). On
        ;; pointer-down in :scissors mode, find the nearest point on the
        ;; committed path (`base-content`) to the cursor and, if it is
        ;; within the same 10px/zoom threshold the hover-preview uses,
        ;; emit `create-node-at-position` with the closest-point meta
        ;; `{:t :from-p :to-p}` — that event splits the segment at param
        ;; `:t` and commits via `save-path-content` (edition.cljs). Uses
        ;; `last-pos` (the latest cursor position in content coords, kept
        ;; current by the mouse-position stream) exactly like
        ;; `on-builder-pointer-down`. Byte-identical to the prior behavior
        ;; for every non-scissors mode (the fn is a no-op unless
        ;; `scissors-mode?`).
        on-scissors-pointer-down
        (mf/use-fn
         (mf/deps edit-mode base-content zoom scissors-mode?)
         (fn [event]
           (when scissors-mode?
             (when (dom/left-mouse? event)
               (dom/stop-propagation event)
               (dom/prevent-default event)
               (let [pos (mf/ref-val last-pos)]
                 (when (some? pos)
                   (let [point (path/closest-point base-content pos (/ 0.01 zoom))]
                     (when (and (some? point)
                                (< (gpt/distance pos point) (/ 10 zoom)))
                       (st/emit! (drp/create-node-at-position (meta point)))))))))))

        ;; Figma-parity Shape Builder (#28) + Paint Bucket (#29).
        ;; pointer-down dispatches by mode + modifier:
        ;;   paint-bucket + face  -> fill (commit the face's closed content)
        ;;   shape-builder + alt  -> erase the clicked face / edge
        ;;   shape-builder + face -> start a unite drag
        on-builder-pointer-down
        (mf/use-fn
         (mf/deps edit-mode builder-arr face-content-map shape zoom)
         (fn [event]
           (when builder-or-bucket?
             (when (dom/left-mouse? event)
               (dom/stop-propagation event)
               (dom/prevent-default event)
               (let [pos  (mf/ref-val last-pos)
                     hit  (if (some? builder-arr)
                            (arrangement/point-in-face builder-arr pos)
                            {:type :outside})
                     sid  (:id shape)
                     bounded (bounded-faces builder-arr)]
                 (cond
                   ;; Paint Bucket — click a face to fill it: replace the
                   ;; edited content with that face's closed region.
                   (and (= edit-mode :paint-bucket)
                        (= :face (:type hit)))
                   (let [fc (get face-content-map (:id (:face hit)))]
                     (when fc
                       (st/emit! (commit-shape-content sid (path/content fc)))))

                   ;; Shape Builder + Alt + face — erase: union of every
                   ;; other bounded face.
                   (and (= edit-mode :shape-builder)
                        (kbd/alt? event)
                        (= :face (:type hit)))
                   (let [erase-id (:id (:face hit))
                         kept     (remove #(= (:id %) erase-id) bounded)
                         kept-c   (keep #(get face-content-map (:id %)) kept)]
                     (st/emit!
                      (commit-shape-content
                       sid
                       (path/content
                        (if (seq kept-c)
                          (bool/calculate-content :union kept-c)
                          [])))))

                   ;; Shape Builder + Alt + edge — erase: remove every
                   ;; face that borders the clicked edge.
                   (and (= edit-mode :shape-builder)
                        (kbd/alt? event)
                        (= :edge (:type hit)))
                   (let [edge-id (:id (:edge hit))
                         kept    (remove #(contains? (set (:edges %)) edge-id) bounded)
                         kept-c   (keep #(get face-content-map (:id %)) kept)]
                     (st/emit!
                      (commit-shape-content
                       sid
                       (path/content
                        (if (seq kept-c)
                          (bool/calculate-content :union kept-c)
                          [])))))

                   ;; Shape Builder — start a unite drag on a face.
                   (and (= edit-mode :shape-builder)
                        (= :face (:type hit)))
                   (do
                     (reset! dragging? true)
                     (reset! drag-faces #{(:id (:face hit))}))

                   :else nil))))))

        ;; pointer-up finalises a Shape Builder drag: a single touched
        ;; face isolates (Divide); multiple touched faces unite.
        on-builder-pointer-up
        (mf/use-fn
         (mf/deps edit-mode builder-arr face-content-map shape zoom)
         (fn [_]
           (when (= edit-mode :shape-builder)
             (let [dfaces @drag-faces]
               (reset! dragging? false)
               (reset! drag-faces nil)
               (when (seq dfaces)
                 (let [contents (keep #(get face-content-map %) dfaces)]
                   (cond
                     (= (count contents) 1)
                     (st/emit! (commit-shape-content
                                (:id shape)
                                (path/content (first contents))))
                     (> (count contents) 1)
                     (st/emit! (commit-shape-content
                                (:id shape)
                                (path/content
                                 (bool/calculate-content :union contents))))
                     :else nil)))))))

    (mf/with-layout-effect [edit-mode]
      (let [key (events/listen (dom/get-root) "dblclick"
                               #(when (= edit-mode :move)
                                  (st/emit! :interrupt)))]
        #(events/unlistenByKey key)))

    (hooks/use-stream
     ms/mouse-position
     (mf/deps base-content zoom)
     (fn [position]
       (when-let [point (path/closest-point base-content position (/ 0.01 zoom))]
         (reset! hover-point (when (< (gpt/distance position point) (/ 10 zoom)) point)))))

    ;; Figma-parity vector lasso (#57). While a lasso is active, append each
    ;; mouse position (throttled by distance) to the capture polygon.
    (hooks/use-stream
     ms/mouse-position
     (mf/deps lasso-mode? zoom)
     (fn [position]
       (when (and lasso-mode? (some? @lasso-points))
         (let [pts @lasso-points
               last-p (peek pts)]
           (when (or (nil? last-p)
                     (> (gpt/distance last-p position) (/ 2 zoom)))
             (reset! lasso-points (conj pts position)))))))

    ;; Figma-parity vector lasso (#57). Finish the lasso on pointer-up
    ;; anywhere (so dragging outside the editor still completes). Only
    ;; armed in lasso mode; cleaned up on mode change.
    (mf/with-layout-effect [lasso-mode? on-lasso-finish]
      (if lasso-mode?
        (let [key (events/listen (dom/get-root) "pointerup" on-lasso-finish)]
          #(events/unlistenByKey key))
        (constantly nil)))

    ;; Figma-parity Shape Builder (#28) / Paint Bucket (#29). Track the
    ;; Alt/Option modifier at document level so the erase cursor + edge
    ;; indicator can render while Alt is held (without a pointer event).
    (mf/with-layout-effect [builder-or-bucket?]
      (if builder-or-bucket?
        (let [kd (events/listen js/document "keydown"
                                (fn [e] (when (kbd/alt? e) (reset! alt-held? true))))
              ku (events/listen js/document "keyup"
                                (fn [e] (when (= (.-key e) "Alt")
                                          (reset! alt-held? false))))]
          #(do (events/unlistenByKey kd)
               (events/unlistenByKey ku)))
        (constantly nil)))

    ;; Figma-parity Shape Builder (#28). Finish a unite drag on pointer-up
    ;; anywhere (so dragging outside the editor still completes). Armed
    ;; only while Shape Builder / Paint Bucket is active.
    (mf/with-layout-effect [builder-or-bucket? on-builder-pointer-up]
      (if builder-or-bucket?
        (let [key (events/listen (dom/get-root) "pointerup" on-builder-pointer-up)]
          #(events/unlistenByKey key))
        (constantly nil)))

    ;; Figma-parity Shape Builder (#28) / Paint Bucket (#29). Track the
    ;; cursor against the arrangement: update the hover highlight, keep
    ;; last-pos current for the pointer-down handler, and accumulate
    ;; touched faces during a unite drag.
    (hooks/use-stream
     ms/mouse-position
     (mf/deps builder-arr edit-mode zoom)
     (fn [position]
       (mf/set-ref-val! last-pos position)
       (when (and builder-or-bucket? (some? builder-arr))
         (let [hit (arrangement/point-in-face builder-arr position)]
           (reset! hover-hit hit)
           (when (and @dragging? (= :face (:type hit)))
             (swap! drag-faces conj (:id (:face hit))))))))

    [:g.path-editor {:ref editor-ref
                     ;; Pointer-down dispatch: vector lasso (#57) starts a
                     ;; freehand capture; Shape Builder / Paint Bucket
                     ;; (#28/#29) commit/erase/start-drag; otherwise nil so
                     ;; node handlers (path-point*) receive the event.
                     :on-pointer-down (cond
                                        lasso-mode?        on-lasso-pointer-down
                                        builder-or-bucket? on-builder-pointer-down
                                        scissors-mode?     on-scissors-pointer-down
                                        :else nil)}
     ;; The base path outline. In Shape Builder / Paint Bucket mode the
     ;; path is given `pointer-events: all` so the whole shape interior
     ;; (not just the stroke) captures clicks — the group's
     ;; :on-pointer-down then fires for every face hit. With fill none
     ;; the default would let interior clicks pass straight through.
     [:path {:d (.toString content)
             :style (cond-> {:fill "none"
                             :stroke accent-color
                             :strokeWidth (/ 1 zoom)}
                      builder-or-bucket? (assoc :pointer-events "all"))}]

     ;; Figma-parity Scissors (ALL_APPS_PARITY P2.32). A wide TRANSPARENT
     ;; stroke over the path makes the whole segment click-targetable in
     ;; scissors mode, so the user can click anywhere on a segment (within
     ;; the 10px/zoom hit radius) to cut it — without needing to hit the
     ;; tiny hover-preview node precisely. `pointer-events "stroke"` means
     ;; only the (wide) stroke is hit-testable; clicks bubble to the root
     ;; group's `:on-pointer-down` -> `on-scissors-pointer-down`, which
     ;; snaps to the nearest point and splits. strokeWidth 20/zoom = 20px
     ;; screen diameter = 10px radius each side, matching the closest-point
     ;; threshold. Additive: rendered ONLY in :scissors edit-mode, so the
     ;; editor is byte-identical in every other mode.
     (when scissors-mode?
       [:path {:d (.toString content)
               :style {:fill "none"
                       :stroke "transparent"
                       :strokeWidth (/ 20 zoom)
                       :pointer-events "stroke"}}])

     ;; Figma-parity variable-width stroke width handles (#53). Rendered
     ;; only when a non-empty per-node segment-widths map is present in the
     ;; edit-path state; absent map = nothing renders (byte-identical). The
     ;; renderer conversion to a filled outline path is DEFERRED.
     (when (some? width-handles)
       [:g.path-width-handles {:pointer-events "none"}
        (for [{:keys [point position width]} width-handles]
          [:> path-width-handle* {:key (dm/str "w-" (:x point) "-" (:y point))
                                  :node point
                                  :position position
                                  :width width
                                  :zoom zoom}])])

     ;; Figma-parity vector lasso (#57). Renders the freehand capture
     ;; polygon while a lasso is being drawn (active only in lasso mode).
     (when (and lasso-mode? (some? @lasso-points) (>= (count @lasso-points) 2))
       (let [lasso-path
             (path/content
              (into [{:command :move-to
                      :params {:x (dm/get-prop (first @lasso-points) :x)
                               :y (dm/get-prop (first @lasso-points) :y)}}]
                    (map #(hash-map :command :line-to
                                    :params {:x (dm/get-prop % :x)
                                             :y (dm/get-prop % :y)}))
                    (rest @lasso-points)))]
         [:g.path-lasso {:pointer-events "none"}
          [:path {:d (.toString lasso-path)
                  :style {:fill secondary-color
                          :fill-opacity 0.08
                          :stroke secondary-color
                          :strokeWidth (/ 1 zoom)
                          :stroke-dasharray (/ 4 zoom)}}]]))

     ;; Figma-parity Shape Builder (#28) + Paint Bucket (#29). Interactive
     ;; planar-arrangement overlay. Renders ONLY under one of these modes
     ;; (opt-in), so the editor is byte-identical in :draw / :move / lasso
     ;; modes. The overlay is pointer-events none — gestures are handled by
     ;; the root path-editor group's :on-pointer-down + the global
     ;; pointer-up / mouse-position listeners above. No CSS transitions:
     ;; the hover fill and drag outline are pure state re-renders, so there
     ;; is no motion to gate on prefers-reduced-motion.
     (when builder-or-bucket?
       (let [hit          @hover-hit
             hovered-face (when (= :face (:type hit)) (:face hit))
             hovered-edge (when (= :edge (:type hit)) (:edge hit))
             erase?       (and (= edit-mode :shape-builder) @alt-held?)
             badge-pos    (-> content first path.helpers/segment->point)
             ;; Live union outline while a drag is in progress.
             drag-outline
             (when (and @dragging? (seq @drag-faces))
               (let [dcontents (keep #(get face-content-map %) @drag-faces)]
                 (when (seq dcontents)
                   (path/content
                    (if (= (count dcontents) 1)
                      (first dcontents)
                      (bool/calculate-content :union dcontents))))))]
         [:g.path-builder-overlay {:pointer-events "none"}
          ;; Mode badge (top-left of the path).
          [:text {:x (:x badge-pos)
                  :y (- (:y badge-pos) (/ 14 zoom))
                  :style {:fill (if erase? coral-color secondary-color)
                          :font-size (/ 11 zoom)
                          :font-weight 600}}
           (str (if (= edit-mode :shape-builder)
                  (tr "workspace.path.mode.shape-builder")
                  (tr "workspace.path.mode.paint-bucket"))
                (when erase? " · erase"))]

          ;; Hovered face highlight — coral translucent fill. In erase
          ;; mode the fill is more transparent and a stronger outline is
          ;; drawn to signal deletion.
          (when (and hovered-face
                     (not @dragging?)
                     (some? (get face-content-map (:id hovered-face))))
            (let [fc (get face-content-map (:id hovered-face))]
              [:path {:d (.toString (path/content fc))
                      :style {:fill coral-color
                              :fill-opacity (if erase? 0.12 0.28)
                              :stroke coral-color
                              :strokeWidth (/ (if erase? 1.5 1) zoom)
                              :stroke-dasharray (when erase? (/ 4 zoom))}}]))

          ;; Drag union-in-progress outline — coral dashed stroke +
          ;; faint fill over the united faces.
          (when (some? drag-outline)
            [:path {:d (.toString drag-outline)
                    :style {:fill coral-color
                            :fill-opacity 0.15
                            :stroke coral-color
                            :strokeWidth (/ 1.5 zoom)
                            :stroke-dasharray (/ 4 zoom)}}])

          ;; Hovered edge indicator (Shape Builder only). A coral dashed
          ;; stroke along the edge; thicker + dashed in erase mode.
          (when (and hovered-edge (= edit-mode :shape-builder))
            [:line {:x1 (:x (:from-point hovered-edge))
                    :y1 (:y (:from-point hovered-edge))
                    :x2 (:x (:to-point hovered-edge))
                    :y2 (:y (:to-point hovered-edge))
                    :style {:stroke coral-color
                            :strokeWidth (/ (if erase? 2.5 1.5) zoom)
                            :stroke-dasharray (/ 3 zoom)}}])

          ;; Erase cursor — a small Lucide "minus-circle" inline SVG
          ;; (stroke-width 2, currentColor) floating at the cursor when
          ;; Alt is held in Shape Builder. Rendered only when a hit is
          ;; present so it doesn't sit at the origin.
          (when (and erase? (some? hit) (not= :outside (:type hit)))
            (let [pos (mf/ref-val last-pos)
                  cx  (dm/get-prop pos :x)
                  cy  (dm/get-prop pos :y)
                  r   (/ 9 zoom)]
              [:g.erase-cursor
               {:transform (dm/str "translate(" cx " " cy ")")
                :stroke coral-color
                :fill "none"
                :stroke-width (/ 2 zoom)}
               [:circle {:cx 0 :cy 0 :r r}]
               [:line {:x1 (- r) :y1 0 :x2 r :y2 0}]]))]))

     (when (and preview (not drag-handler))
       [:> path-preview* {:segment preview
                          :from last-p
                          :zoom zoom}])

     (when (and drag-handler last-p)
       [:g.drag-handler {:pointer-events "none"}
        [:> path-handler* {:point last-p
                           :handler drag-handler
                           :edit-mode edit-mode
                           :zoom zoom}]])

     ;; Figma-parity Shape Builder / Paint Bucket (#28/#29): hide the
     ;; node hover-point and the editable node/handler circles in those
     ;; modes — the user interacts with faces, not nodes, so a clean
     ;; face-only overlay is shown (see path-builder-overlay above).
     (when (and @hover-point (not builder-or-bucket?))
       [:g.hover-point
        [:> path-point* {:position @hover-point
                         :edit-mode edit-mode
                         :is-new true
                         :is-start-path is-path-start
                         :zoom zoom}]])

     (when-not builder-or-bucket?
       (for [position points]
         (let [pos-x (dm/get-prop position :x)
               pos-y (dm/get-prop position :y)

             show-handler?
             (fn [[index prefix]]
               ;; FIXME: get-handler-point is executed twice for each
               ;; render, this can be optimized
               (let [handler-position (path/get-handler-point content index prefix)]
                 (not= position handler-position)))

             position-handlers
             (->> (get handlers position)
                  (filter show-handler?)
                  (not-empty))

             point-selected?
             (contains? selected-points (get point->base position))

             point-hover?
             (contains? hover-points (get point->base position))

             is-last
             (= last-point (get point->base position))

             is-curve
             (boolean position-handlers)]

         [:g.path-node {:key (dm/str pos-x "-" pos-y)}
          [:g.point-handlers {:pointer-events (when (= edit-mode :draw) "none")}
           (for [[hindex prefix] position-handlers]
             (let [handler-position  (path/get-handler-point content hindex prefix)
                   handler-hover?    (contains? hover-handlers [hindex prefix])
                   moving-handler?   (= handler-position moving-handler)
                   matching-handler? (matching-handler? content position position-handlers)]

               (when (and position handler-position)
                 [:> path-handler*
                  {:key (dm/str hindex "-" (d/name prefix))
                   :point position
                   :handler handler-position
                   :index hindex
                   :prefix prefix
                   :zoom zoom
                   :is-hover handler-hover?
                   :snap-angle (and moving-handler? matching-handler?)
                   :edit-mode edit-mode}])))]

          [:> path-point* {:position position
                           :zoom zoom
                           :edit-mode edit-mode
                           :is-selected point-selected?
                           :is-hover point-hover?
                           :is-last is-last
                           :is-start-path is-path-start
                           :is-curve is-curve}]])))

     (when (and prev-handler last-p)
       [:g.prev-handler {:pointer-events "none"}
        [:> path-handler*
         {:point last-p
          :edit-mode edit-mode
          :handler prev-handler
          :zoom zoom}]])

     (when ^boolean show-snap?
       (let [[snap-selected snap-points]
             (cond
               (some? drag-handler) [#{drag-handler} points]
               (some? preview) [#{(path.helpers/segment->point preview)} points]
               (some? moving-handler) [#{moving-handler} points]
               :else
               [(->> selected-points (map base->point) (into #{}))
                (->> points (remove selected-points) (into #{}))])]
         [:g.path-snap {:pointer-events "none"}
          [:> path-snap* {:selected snap-selected
                          :points snap-points
                          :zoom zoom}]]))]))

