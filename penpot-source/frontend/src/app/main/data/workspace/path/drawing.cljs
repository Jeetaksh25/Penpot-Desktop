;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.path.drawing
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.flex-layout :as gsl]
   [app.common.types.container :as ctn]
   [app.common.types.path :as path]
   [app.common.types.path.helpers :as path.helpers]
   [app.common.types.path.subpath :as psub]
   [app.common.types.shape :as cts]
   [app.common.types.shape-tree :as ctst]
   [app.common.types.shape.layout :as ctl]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.drawing.common :as dwdc]
   [app.main.data.workspace.edition :as dwe]
   [app.main.data.workspace.pages :as-alias dwpg]
   [app.main.data.workspace.path.changes :as changes]
   [app.main.data.workspace.path.common :as common]
   [app.main.data.workspace.path.helpers :as helpers]
   [app.main.data.workspace.path.state :as st]
   [app.main.data.workspace.path.streams :as streams]
   [app.main.data.workspace.path.undo :as undo]
   [app.main.data.workspace.shapes :as dwsh]
   [app.util.mouse :as mse]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(declare close-path-drag-end)
(declare check-changed-content)
(declare change-edit-mode)

(defn- end-path-event?
  [event]
  (let [type (ptk/type event)]
    (or
     (= type ::common/finish-path)
     (= type :app.main.data.workspace.path.shortcuts/esc-pressed)
     (= type :app.main.data.workspace.common/clear-edition-mode)
     (= type :app.main.data.workspace.edition/clear-edition-mode)
     (= type ::dwpg/finalize-page)
     (= event :interrupt) ;; ESC
     (and ^boolean (mse/mouse-event? event)
          ^boolean (mse/mouse-double-click-event? event)))))

(defn preview-next-point
  [{:keys [x y shift?]}]
  (ptk/reify ::preview-next-point
    ptk/UpdateEvent
    (update [_ state]
      (let [id         (st/get-path-id state)
            fix-angle? shift?
            last-point (get-in state [:workspace-local :edit-path id :last-point])
            position   (cond-> (gpt/point x y)
                         fix-angle? (path.helpers/position-fixed-angle last-point))
            content    (st/get-path state :content)

            {:keys [last-point prev-handler]}
            (get-in state [:workspace-local :edit-path id])

            segment (path/next-node content position last-point prev-handler)]
        (assoc-in state [:workspace-local :edit-path id :preview] segment)))))

(defn add-node
  [{:keys [x y shift?]}]
  (ptk/reify ::add-node
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)
            fix-angle? shift?
            {:keys [last-point prev-handler]} (get-in state [:workspace-local :edit-path id])
            position (cond-> (gpt/point x y)
                       fix-angle? (path.helpers/position-fixed-angle last-point))]
        (if-not (= last-point position)
          (-> state
              (assoc-in  [:workspace-local :edit-path id :last-point] position)
              (update-in [:workspace-local :edit-path id] dissoc :prev-handler)
              (update-in [:workspace-local :edit-path id] dissoc :preview)
              (update-in (st/get-path-location state) helpers/append-node position last-point prev-handler))
          state)))))

(defn drag-handler
  ([position]
   (drag-handler nil nil :c1 position))
  ([position index prefix {:keys [x y alt? shift?]}]
   (ptk/reify ::drag-handler
     ptk/UpdateEvent
     (update [_ state]
       (let [id (st/get-path-id state)
             content (st/get-path state :content)

             index (or index (count content))
             prefix (or prefix :c1)
             position (or position (path.helpers/segment->point (nth content (dec index))))

             old-handler (path/get-handler-point content index prefix)

             handler-position (cond-> (gpt/point x y)
                                shift? (path.helpers/position-fixed-angle position))

             {dx :x dy :y} (if (some? old-handler)
                             (gpt/add (gpt/to-vec old-handler position)
                                      (gpt/to-vec position handler-position))
                             (gpt/to-vec position handler-position))

             match-opposite? (not alt?)

             modifiers (helpers/move-handler-modifiers content index prefix match-opposite? match-opposite? dx dy)]
         (-> state
             (update-in [:workspace-local :edit-path id :content-modifiers] merge modifiers)
             (assoc-in [:workspace-local :edit-path id :drag-handler] handler-position)))))))

(defn finish-drag []
  (ptk/reify ::finish-drag
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)

            modifiers (get-in state [:workspace-local :edit-path id :content-modifiers])
            content (-> (st/get-path state :content)
                        (path/apply-content-modifiers modifiers))

            handler (get-in state [:workspace-local :edit-path id :drag-handler])]
        (-> state
            (st/set-content content)
            (update-in [:workspace-local :edit-path id] dissoc :drag-handler)
            (update-in [:workspace-local :edit-path id] dissoc :content-modifiers)
            (assoc-in  [:workspace-local :edit-path id :prev-handler] handler)
            (update-in (st/get-path-location state) path/update-geometry))))

    ptk/WatchEvent
    (watch [_ state _]
      (let [id (st/get-path-id state)
            handler (get-in state [:workspace-local :edit-path id :prev-handler])]
        ;; Update the preview because can be outdated after the dragging
        (rx/of (preview-next-point handler)
               (undo/merge-head))))))

(defn close-path-drag-start
  [position]
  (ptk/reify ::close-path-drag-start
    ptk/WatchEvent
    (watch [_ state stream]
      (let [content  (st/get-path state :content)
            handlers (-> (path/get-handlers content)
                         (get position))

            [idx prefix] (when (= (count handlers) 1)
                           (first handlers))

            ;; Figma-parity vector networks (ALL_APPS_PARITY P1.33). When
            ;; the user is drawing and clicks an existing node mid-subpath
            ;; (the `(and is-draw (not is-start-path))` branch in
            ;; path-point*), two intents must be distinguished by whether
            ;; the clicked node IS the current subpath's start node:
            ;;
            ;;   * start node  -> CLOSE the current subpath into a loop and
            ;;     finish drawing. This is the pre-existing behavior and is
            ;;     preserved byte-identically in the `closing?` branch below
            ;;     (same add-node + close-drag + close-path-drag-end +
            ;;     finish-path sequence).
            ;;
            ;;   * any OTHER existing node -> CONNECT the current subpath to
            ;;     that node as a branch and KEEP drawing from the junction
            ;;     (the Figma "vector network" pen behavior). `add-node`
            ;;     appends a segment whose endpoint equals the existing
            ;;     node's coordinate (a line-to/curve-to, because
            ;;     `last-point` is non-nil), and the editor's
            ;;     coordinate-coincident drag (segment/point-indices +
            ;;     handler-indices, used by edition/modify-content-point)
            ;;     already moves EVERY segment that shares that endpoint, so
            ;;     the junction is a real shared node -- dragging it later
            ;;     moves the whole branch. No finish-path is emitted, so
            ;;     drawing resumes from the junction (add-node has set
            ;;     :last-point to it); a click makes the next segment a
            ;;     corner line from the junction, and a drag pulls the
            ;;     outgoing c1 handle of the NEXT segment the user is about
            ;;     to draw from the junction, making that next segment a
            ;;     smooth curve (the same pattern `start-path-from-point`
            ;;     uses to shape a fresh segment from an existing node; see
            ;;     the connect-drag-events comment below for the nil-index
            ;;     `(count content)` virtual-handle convention).
            ;;
            ;; No data-model or renderer change is needed: the flat
            ;; PathData content already permits >2 segments to share a node
            ;; coordinate (multiple subpaths with coincident endpoints), and
            ;; `PathData.toString` renders that as ordinary SVG subpaths --
            ;; branching is purely an editing affordance, not a render case.
            ;; The current subpath's start is the :from of the last subpath
            ;; produced by `psub/get-subpaths` (the subpath currently being
            ;; drawn); `pt=` matches it against the clicked position with
            ;; the same 0.1px tolerance the rest of the path code uses.
            subpaths      (psub/get-subpaths content)
            current-start (some-> subpaths peek :from)
            closing?      (and (some? current-start)
                               (psub/pt= current-start position))

            stopper (rx/merge
                     (mse/drag-stopper stream)
                     (rx/filter end-path-event? stream))

            close-drag-events
            (->> (streams/position-stream state)
                 (rx/map #(drag-handler position idx prefix %))
                 (rx/take-until stopper))

            ;; For a connect we use drag-handler's 1-arity form (nil index,
            ;; default :c1 prefix), the SAME convention `start-path-from-point`
            ;; and `make-drag-stream` use to shape a fresh segment from an
            ;; existing node. With nil index, drag-handler falls back to
            ;; `(count content)` -- the virtual "next segment to be drawn"
            ;; index -- so a DRAG pulls the outgoing c1 handle of the segment
            ;; the user is about to draw from the junction (making the next
            ;; segment a smooth curve from J), and a pure CLICK emits no drag
            ;; events so finish-drag leaves :prev-handler nil (making the next
            ;; segment a corner line from J). The just-appended branch segment
            ;; itself is a line-to/curve-to with no editable handle here; its
            ;; handle is edited later in :move mode via the shared-node
            ;; handler-indices mechanism. This is byte-identical to the
            ;; start-path-from-point stream shape (the only difference is that
            ;; add-node emits a line-to/curve-to here because :last-point is
            ;; non-nil, vs a move-to in start-path-from-point where it is nil).
            ;;
            ;; Known limitation (inherent to Penpot's flat-subpath SVG model,
            ;; NOT a regression): a junction where two SEPARATE subpaths meet
            ;; renders with a stroke seam, because SVG stroke-linejoin does
            ;; not apply across an `M` (subpath) boundary -- only Figma's
            ;; graph renderer joins branching strokes seamlessly. close-subpaths
            ;; (run at finish-path) merges endpoint-coincident subpaths into
            ;; one subpath (fixing the seam for connect-to-start/end cases),
            ;; but an interior-node connect leaves a seam. Shared-node DRAG is
            ;; unaffected either way (point-indices + the implicit-prev-start
            ;; property keep all arms attached to J).
            connect-drag-events
            (->> (streams/position-stream state)
                 (rx/map #(drag-handler %))
                 (rx/take-until stopper))]

        (if closing?
          ;; Close-and-finish: pre-existing behavior, unchanged.
          (rx/concat
           (rx/of (add-node position))
           (streams/drag-stream
            (rx/concat
             close-drag-events
             (rx/of (finish-drag))
             (rx/of (close-path-drag-end))))
           (rx/of (common/finish-path)))

          ;; Connect-and-continue (P1.33): append the branch segment to the
          ;; existing node, pull an optional handle, and resume drawing from
          ;; the junction. `add-node` has set :last-point to the junction
          ;; coordinate so the next click continues from it; no finish-path
          ;; is emitted, so the path stays open for further branches.
          (rx/concat
           (rx/of (add-node position))
           (streams/drag-stream
            (rx/concat
             connect-drag-events
             (rx/of (finish-drag))))))))))

(defn close-path-drag-end []
  (ptk/reify ::close-path-drag-end
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)]
        (update-in state [:workspace-local :edit-path id] dissoc :prev-handler)))))

(defn start-path-from-point [position]
  (ptk/reify ::start-path-from-point
    ptk/WatchEvent
    (watch [_ state stream]
      (let [stopper (rx/merge
                     (mse/drag-stopper stream)
                     (rx/filter end-path-event? stream))

            drag-events (->> (streams/position-stream state)
                             (rx/map #(drag-handler %))
                             (rx/take-until stopper))]
        (rx/concat
         (rx/of (add-node position))
         (streams/drag-stream
          (rx/concat
           drag-events
           (rx/of (finish-drag)))))))))

(defn make-node-events-stream
  [stream]
  (->> stream
       (rx/filter (ptk/type? ::close-path-drag-start))
       (rx/take 1)
       (rx/merge-map #(rx/empty))))

(defn make-drag-stream
  [state stream down-event]

  (assert (gpt/point? down-event)
          "should be a point instance")

  (let [stopper (rx/merge
                 (mse/drag-stopper stream)
                 (rx/filter end-path-event? stream))

        drag-events
        (->> (streams/position-stream state)
             (rx/map #(drag-handler %))
             (rx/take-until stopper))]
    (rx/concat
     (rx/of (add-node down-event))
     (streams/drag-stream
      (rx/concat
       drag-events
       (rx/of (finish-drag)))))))

(defn- start-edition
  [_id]
  (ptk/reify ::start-edition
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)]
        (assoc-in state [:workspace-local :edit-path id :edit-mode] :draw)))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [mouse-down
            (->> stream
                 (rx/filter mse/mouse-event?)
                 (rx/filter mse/mouse-down-event?))

            end-stream
            (->> stream
                 (rx/filter end-path-event?)
                 (rx/share))

            stoper-stream
            (->> stream
                 (rx/filter (ptk/type? ::start-edition))
                 (rx/merge end-stream))

            ;; Mouse move preview
            mousemove-events
            (->> (streams/position-stream state)
                 (rx/map #(preview-next-point %)))

            ;; From mouse down we can have: click, drag and double click
            mousedown-events
            (->> mouse-down
                 ;; We just ignore the mouse event and stream down the
                 ;; last position event
                 (rx/with-latest-from #(-> %2) (streams/position-stream state))
                 ;; We change to the stream that emits the first event
                 (rx/switch-map
                  #(rx/race (make-node-events-stream stream)
                            (make-drag-stream state stream %)))
                 (rx/take-until end-stream))]

        (->> (rx/concat
              (rx/of (undo/start-path-undo))
              (->> (rx/merge mousemove-events
                             mousedown-events)
                   (rx/take-until stoper-stream))
              (rx/of (ptk/data-event ::end-edition))))))))

(defn setup-frame
  []
  (ptk/reify ::setup-frame
    ptk/UpdateEvent
    (update [_ state]
      (let [objects      (dsh/lookup-page-objects state)
            content      (get-in state [:workspace-drawing :object :content] [])

            ;; FIXME: use native operation for retrieve the first position
            position     (-> (nth content 0)
                             (get :params)
                             (gpt/point))

            frame-id     (->> (ctst/top-nested-frame objects position)
                              (ctn/get-first-valid-parent objects) ;; We don't want to change the structure of component copies
                              :id)
            flex-layout? (ctl/flex-layout? objects frame-id)
            drop-index   (when flex-layout? (gsl/get-drop-index frame-id objects position))]

        (update-in state [:workspace-drawing :object]
                   (fn [object]
                     (-> object
                         (assoc :frame-id frame-id)
                         (assoc :parent-id frame-id)
                         (cond-> (some? drop-index)
                           (with-meta {:index drop-index})))))))))

(defn- handle-drawing-end
  [shape-id]
  (ptk/reify ::handle-drawing-end
    ptk/UpdateEvent
    (update [_ state]
      (let [content (some-> (dm/get-in state [:workspace-drawing :object :content])
                            (path/check-content))]
        (if (> (count content) 1)
          (assoc-in state [:workspace-drawing :object :initialized?] true)
          state)))

    ptk/WatchEvent
    (watch [_ state _]
      (when-let [content (dm/get-in state [:workspace-drawing :object :content])]
        (if (> (count content) 1)
          (rx/of (setup-frame)
                 (dwdc/handle-finish-drawing)
                 (dwe/start-edition-mode shape-id)
                 (change-edit-mode :draw))
          (rx/of (dwdc/handle-finish-drawing)))))))

(defn handle-drawing
  "Hanndle the start of drawing new path shape"
  []
  (ptk/reify ::handle-new-shape
    ptk/UpdateEvent
    (update [_ state]
      (let [shape (cts/setup-shape {:type :path})]
        (update state :workspace-drawing assoc :object shape)))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [shape-id (dm/get-in state [:workspace-drawing :object :id])]
        (rx/concat
         (rx/of (start-edition shape-id))
         (->> stream
              (rx/filter (ptk/type? ::end-edition))
              (rx/take 1)
              (rx/observe-on :async)
              (rx/map (partial handle-drawing-end shape-id))))))))

(declare start-draw-mode*)

(defn start-draw-mode
  []
  (ptk/reify ::start-draw-mode
    ptk/UpdateEvent
    (update [_ state]
      (let [id      (dm/get-in state [:workspace-local :edition])
            objects (dsh/lookup-page-objects state)
            content (dm/get-in objects [id :content])]
        (if content
          (update-in state [:workspace-local :edit-path id] assoc :old-content content)
          state)))

    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (start-draw-mode*)))))

(defn start-draw-mode*
  []
  (ptk/reify ::start-draw-mode*
    ptk/WatchEvent
    (watch [_ state stream]
      (let [local (get state :workspace-local)
            id    (get local :edition)
            mode  (dm/get-in local [:edit-path id :edit-mode])]

        (if (= :draw mode)
          (rx/concat
           (rx/of (dwsh/update-shapes [id] path/convert-to-path))
           (rx/of (start-edition id))
           (->> stream
                (rx/filter (ptk/type? ::end-edition))
                (rx/take 1)
                (rx/mapcat (fn [_]
                             (rx/of (check-changed-content)
                                    (start-draw-mode*))))))
          (rx/empty))))))

(defn change-edit-mode
  [mode]
  (ptk/reify ::change-edit-mode
    ptk/UpdateEvent
    (update [_ state]
      (if-let [id (dm/get-in state [:workspace-local :edition])]
        (d/update-in-when state [:workspace-local :edit-path id] assoc :edit-mode mode)
        state))

    ptk/WatchEvent
    (watch [_ state _]
      (when-let [id (dm/get-in state [:workspace-local :edition])]
        (let [mode (dm/get-in state [:workspace-local :edit-path id :edit-mode])]
          (case mode
            :move (rx/of (common/finish-path))
            :draw (rx/of (start-draw-mode))
            (rx/empty)))))))

(defn reset-last-handler
  []
  (ptk/reify ::reset-last-handler
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)]
        (assoc-in state [:workspace-local :edit-path id :prev-handler] nil)))))

(defn check-changed-content
  []
  (ptk/reify ::check-changed-content
    ptk/WatchEvent
    (watch [_ state _]
      (let [id (st/get-path-id state)
            content (st/get-path state :content)
            old-content (get-in state [:workspace-local :edit-path id :old-content])
            mode (get-in state [:workspace-local :edit-path id :edit-mode])
            empty-content? (empty? content)]

        (cond
          (and (not= content old-content) (not empty-content?))
          (rx/of (changes/save-path-content))

          (= mode :draw)
          (rx/of :interrupt)

          :else
          (rx/of
           (common/finish-path)
           (dwdc/clear-drawing)))))))
