;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.shapes.path.editor
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.point :as gpt]
   [app.common.math :as mth]
   [app.common.types.path :as path]
   [app.common.types.path.helpers :as path.helpers]
   [app.main.data.workspace.path :as drp]
   [app.main.snap :as snap]
   [app.main.store :as st]
   [app.main.streams :as ms]
   [app.main.ui.css-cursors :as cur]
   [app.main.ui.hooks :as hooks]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [clojure.set :refer [map-invert]]
   [goog.events :as events]
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

    [:g.path-editor {:ref editor-ref
                     ;; Figma-parity vector lasso (#57): start the lasso
                     ;; capture on pointer-down only in lasso mode.
                     :on-pointer-down (when lasso-mode? on-lasso-pointer-down)}
     [:path {:d (.toString content)
             :style {:fill "none"
                     :stroke accent-color
                     :strokeWidth (/ 1 zoom)}}]

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

     ;; Figma-parity vector-network tools (gaps #28/#29). The
     ;; :shape-builder and :paint-bucket edit-modes are REGISTERED here
     ;; (selectable from the path secondary toolbar / shortcuts) but their
     ;; interactive geometry is DEFERRED:
     ;;   #28 — drag merge/extract/subtract via the boolean engine
     ;;         (app.common.types.path.bool) is not yet wired.
     ;;   #29 — enclosed-region (graph-cycle on path nodes/segments)
     ;;         detection and filled sub-path creation is not yet wired.
     ;; This guarded badge is the only visual surface; it renders solely
     ;; under one of the new modes (opt-in), so the editor is
     ;; byte-identical when edit-mode is :draw or :move.
     (when (or (= edit-mode :shape-builder)
               (= edit-mode :paint-bucket))
       [:g.path-mode-badge {:pointer-events "none"}
        [:text {:x (-> content first path.helpers/segment->point :x)
                :y (- (-> content first path.helpers/segment->point :y)
                      (/ 14 zoom))
                :style {:fill secondary-color
                        :font-size (/ 11 zoom)
                        :font-weight 600}}
         (if (= edit-mode :shape-builder)
           (tr "workspace.path.mode.shape-builder")
           (tr "workspace.path.mode.paint-bucket"))]])

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

     (when @hover-point
       [:g.hover-point
        [:> path-point* {:position @hover-point
                         :edit-mode edit-mode
                         :is-new true
                         :is-start-path is-path-start
                         :zoom zoom}]])

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
                           :is-curve is-curve}]]))

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

