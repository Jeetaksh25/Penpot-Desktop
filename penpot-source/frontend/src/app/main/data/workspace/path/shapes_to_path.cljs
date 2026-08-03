;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.path.shapes-to-path
  (:require
   [app.common.data :as d]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cph]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.common.types.container :as ctn]
   [app.common.types.path :as path]
   [app.common.types.shape :as cts]
   [app.common.types.text :as txt]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.features :as features]
   [app.render-wasm.api :as wasm.api]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(def ^:private dissoc-attrs
  [:x :y :width :height
   :rx :ry :r1 :r2 :r3 :r4
   :metadata])

(defn convert-selected-to-path
  ([]
   (convert-selected-to-path nil))
  ([ids]
   (ptk/reify ::convert-selected-to-path
     ptk/WatchEvent
     (watch [it state _]
       (if (features/active-feature? state "render-wasm/v1")
         (let [page-id  (:current-page-id state)
               objects  (dsh/lookup-page-objects state)
               selected
               (->> (or ids (dsh/lookup-selected state))
                    (remove #(ctn/has-any-copy-parent? objects (get objects %))))

               children-ids
               (into #{}
                     (mapcat #(cph/get-children-ids objects %))
                     selected)

               changes
               (-> (pcb/empty-changes it page-id)
                   (pcb/with-objects objects)
                   (pcb/update-shapes
                    selected
                    (fn [shape]
                      (let [content (wasm.api/shape-to-path (:id shape))]
                        (-> shape
                            (assoc :type :path)
                            (cond-> (cph/text-shape? shape)
                              (assoc :fills
                                     (->> (txt/node-seq txt/is-text-node? (:content shape))
                                          (map :fills)
                                          (first))))
                            (cond-> (cph/image-shape? shape)
                              (assoc :fill-image (get shape :metadata)))
                            (d/without-keys dissoc-attrs)
                            (path/update-geometry content)))))
                   (pcb/remove-objects children-ids))]
           (rx/of (dch/commit-changes changes)))

         (let [page-id  (:current-page-id state)
               objects  (dsh/lookup-page-objects state)
               selected (->> (or ids (dsh/lookup-selected state))
                             (remove #(ctn/has-any-copy-parent? objects (get objects %))))

               children-ids
               (into #{}
                     (mapcat #(cph/get-children-ids objects %))
                     selected)

               changes
               (-> (pcb/empty-changes it page-id)
                   (pcb/with-objects objects)
                   (pcb/update-shapes selected path/convert-to-path {:with-objects? true})
                   (pcb/remove-objects children-ids))]

           (rx/of (dch/commit-changes changes))))))))

(defn- stroke->fill
  "Converts stroke color properties to fill color properties."
  [stroke]
  (d/without-nils
   {:fill-color           (:stroke-color stroke)
    :fill-opacity         (:stroke-opacity stroke)
    :fill-color-gradient  (:stroke-color-gradient stroke)
    :fill-image           (:stroke-image stroke)
    :fill-color-ref-id    (:stroke-color-ref-id stroke)
    :fill-color-ref-file  (:stroke-color-ref-file stroke)}))

(defn- make-stroke-paths
  "Given a shape with strokes, returns a vector of new path shapes
   created from each stroke. Uses the provided parent-id and frame-id."
  [shape parent-id frame-id]
  (into []
        (keep-indexed
         (fn [idx stroke]
           (let [result (wasm.api/stroke-to-path (:id shape) idx)]
             (when (some? result)
               (cts/setup-shape
                (cond-> {:type      :path
                         :id        (uuid/next)
                         :name      (str (:name shape) " (stroke)")
                         :parent-id parent-id
                         :frame-id  frame-id
                         :content   (:content result)
                         :fills     [(stroke->fill stroke)]
                         :strokes   []}
                  (:even-odd? result)
                  (assoc :svg-attrs {:fillRule "evenodd"})))))))
        (:strokes shape)))

(defn convert-selected-strokes-to-path
  "For each selected shape, converts each stroke into a new sibling
   path shape. When the selected shape is a group/frame with stroked
   descendants, a new group is created as a sibling containing all
   the stroke paths. Strokes are then removed from processed shapes."
  ([]
   (convert-selected-strokes-to-path nil))
  ([ids]
   (ptk/reify ::convert-selected-strokes-to-path
     ptk/WatchEvent
     (watch [it state _]
       (when (features/active-feature? state "render-wasm/v1")
         (let [page-id  (:current-page-id state)
               objects  (dsh/lookup-page-objects state)
               selected (->> (or ids (dsh/lookup-selected state))
                             (remove #(ctn/has-any-copy-parent? objects (get objects %))))

               result
               (reduce
                (fn [acc shape-id]
                  (let [shape (get objects shape-id)]
                    (if (seq (:strokes shape))
                      ;; Shape itself has strokes: create stroke paths as siblings
                      (let [position   (cph/get-position-on-parent objects shape-id)
                            new-shapes (make-stroke-paths shape (:parent-id shape) (:frame-id shape))]
                        (-> acc
                            (update :entries into (map-indexed #(hash-map :new-shape %2 :index (+ (inc position) %1)) new-shapes))
                            (update :updated-ids conj shape-id)))

                      ;; Check descendants for strokes (groups, SVGs, etc.)
                      (let [child-ids  (->> (cph/get-children-ids objects shape-id)
                                            (filter #(seq (:strokes (get objects %)))))
                            group-id   (uuid/next)
                            new-shapes (into []
                                             (mapcat (fn [cid]
                                                       (make-stroke-paths (get objects cid)
                                                                          group-id
                                                                          (:frame-id shape))))
                                             child-ids)]
                        (if (seq new-shapes)
                          ;; Wrap all stroke paths in a new group
                          (let [position (cph/get-position-on-parent objects shape-id)
                                selrect  (gsh/shapes->rect new-shapes)
                                group    (cts/setup-shape
                                          {:id        group-id
                                           :type      :group
                                           :name      (str (:name shape) " (strokes)")
                                           :shapes    (mapv :id new-shapes)
                                           :selrect   selrect
                                           :x         (:x selrect)
                                           :y         (:y selrect)
                                           :width     (:width selrect)
                                           :height    (:height selrect)
                                           :parent-id (:parent-id shape)
                                           :frame-id  (:frame-id shape)})]
                            (-> acc
                                (update :groups conj {:group group :children new-shapes :index (inc position)})
                                (update :updated-ids into child-ids)))
                          acc)))))
                {:entries     []
                 :groups      []
                 :updated-ids []}
                selected)

               new-shape-ids (into []
                                   (concat
                                    (map (comp :id :new-shape) (:entries result))
                                    (map (comp :id :group) (:groups result))))

               changes
               (as-> (pcb/empty-changes it page-id) changes
                 (pcb/with-objects changes objects)

                 ;; Add ungrouped stroke path shapes as siblings
                 (reduce
                  (fn [changes {:keys [new-shape index]}]
                    (pcb/add-object changes new-shape {:index index}))
                  changes
                  (:entries result))

                 ;; Add groups with their stroke path children
                 (reduce
                  (fn [changes {:keys [group children index]}]
                    (as-> changes changes
                      (pcb/add-object changes group {:index index})
                      (reduce
                       (fn [changes child]
                         (pcb/add-object changes child {:parent-id (:id group)}))
                       changes
                       children)))
                  changes
                  (:groups result))

                 ;; Remove strokes from original shapes
                 (pcb/update-shapes changes
                                    (:updated-ids result)
                                    (fn [shape] (assoc shape :strokes []))))]

           (rx/of (dch/commit-changes changes)
                  (dws/select-shapes (into (d/ordered-set) new-shape-ids)))))))))

;; Figma-parity "Outline Stroke" (gap #27). Converts each selected
;; shape's stroke into an editable filled path. The real stroke-offset
;; geometry is provided by the render-wasm path (wasm.api/stroke-to-path,
;; used by convert-selected-strokes-to-path above); on the frontend-SVG
;; renderer (render-wasm/v1 OFF) pure-CLJS stroke expansion is non-trivial
;; and is DEFERRED — the event signature + menu wiring are in place so
;; the feature activates the moment wasm is enabled, and is a safe no-op
;; (returns nil) otherwise. This keeps the change purely additive.
(defn outline-stroke
  "Convert strokes on the selected shapes (or the given ids) into
   sibling filled path shapes — Figma's Outline Stroke."
  ([]
   (outline-stroke nil))
  ([ids]
   (ptk/reify ::outline-stroke
     ptk/WatchEvent
     (watch [_ state _]
       ;; Delegate to the existing stroke->path conversion, which is
       ;; itself gated on render-wasm/v1 (returns nil when inactive, so
       ;; this is a safe no-op on the frontend-SVG renderer).
       (let [selected (or ids (dsh/lookup-selected state))
             objects  (dsh/lookup-page-objects state)
             has-stroke? (some #(seq (:strokes (get objects %))) selected)]
         (when (and (features/active-feature? state "render-wasm/v1")
                    has-stroke?)
           (rx/of (convert-selected-strokes-to-path selected))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; OFFSET VECTOR (Figma-parity #55)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- offset-content
  "Offset a path content by `distance`. Basic implementation: takes the
  path's anchor points and offsets each vertex along the averaged normal
  of its adjacent edges (miter join), then rebuilds the path as line
  segments between the offset anchors. Curves therefore flatten to
  their anchor polyline during offset. Robust clipper-offset geometry
  — self-intersection handling, true curve offset, round/miter/bevel
  join selection, open-path caps — is DEFERRED (needs the render-wasm
  offset primitive or a full polygon-offset port). Returns nil for
  degenerate inputs so the caller can skip the update."
  [content distance]
  (let [points (path/get-points content)
        closed? (some #(= :close-path (:command %)) content)]
    (when (>= (count points) 2)
      (let [n (count points)
            offset-point
            (fn [i p]
              (let [prev (when (pos? i) (nth points (dec i)))
                    nxt (when (< i (dec n)) (nth points (inc i)))
                    v-in (when (and prev (not= prev p))
                           (gpt/unit (gpt/to-vec prev p)))
                    v-out (when (and nxt (not= nxt p))
                           (gpt/unit (gpt/to-vec p nxt)))
                    n-in (some-> v-in gpt/perpendicular)
                    n-out (some-> v-out gpt/perpendicular)
                    summed (cond
                             (and n-in n-out) (gpt/add n-in n-out)
                             n-in n-in
                             n-out n-out
                             :else (gpt/point 0 0))
                    len (gpt/length summed)
                    normal (if (mth/almost-zero? len)
                             (gpt/point 0 0)
                             (gpt/unit summed))
                    offset (gpt/scale normal distance)]
                (gpt/add p offset)))
            offset-points (into [] (map-indexed offset-point) points)]
        (path/points->content offset-points :close closed?)))))

(defn offset-vector
  "Figma-parity Offset Vector (#55). Produces a new path whose outline is
  offset by `distance` from each selected path shape. Basic polyline
  offset is applied (see `offset-content`); robust clipper-offset geometry
  is DEFERRED — the op signature + menu wiring are in place so the feature
  activates the moment a real offset backend lands. Purely additive:
  no-op when no path shapes are selected."
  ([]
   (offset-vector nil 1.0))
  ([ids]
   (offset-vector ids 1.0))
  ([ids distance]
   (ptk/reify ::offset-vector
     ptk/WatchEvent
     (watch [_ state _]
       (let [selected (or ids (dsh/lookup-selected state))
             objects  (dsh/lookup-page-objects state)
             path-ids (into [] (filter #(cph/path-shape? (get objects %))) selected)]
         (when (seq path-ids)
           (rx/of (dwsh/update-shapes
                   path-ids
                   (fn [shape]
                     (let [content (:content shape)
                           offset (offset-content content distance)]
                       (if (some? offset)
                         (path/update-geometry shape offset)
                         shape)))))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SIMPLIFY VECTOR (Figma-parity #56)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- rdp-perp-distance
  "Perpendicular distance from point `p` to the line through `a` and `b`."
  [p a b]
  (if (gpt/close? a b)
    (gpt/distance p a)
    (let [vx (- (dm/get-prop b :x) (dm/get-prop a :x))
          vy (- (dm/get-prop b :y) (dm/get-prop a :y))
          dx (- (dm/get-prop p :x) (dm/get-prop a :x))
          dy (- (dm/get-prop p :y) (dm/get-prop a :y))
          len (mth/sqrt (+ (* vx vx) (* vy vy)))
          ;; |cross(v, d)| / |v|
          cross (mth/abs (- (* vx dy) (* vy dx)))]
      (/ cross len))))

(defn- rdp
  "Ramer-Douglas-Peucker point reduction. `points` is a vector of gpt
  points. Returns a reduced vector preserving the first and last points
  and any point whose perpendicular distance from the simplified segment
  exceeds `epsilon`. Pure algorithm — Figma-parity #56."
  [points epsilon]
  (cond
    (< (count points) 3)
    points

    :else
    (let [fp (first points)
          lp (last points)]
      (loop [i 1 max-d 0.0 max-i 0]
        (if (< i (dec (count points)))
          (let [d (rdp-perp-distance (nth points i) fp lp)]
            (if (> d max-d)
              (recur (inc i) d i)
              (recur (inc i) max-d max-i)))
          (if (> max-d epsilon)
            (let [left (rdp (subvec points 0 (inc max-i)) epsilon)
                  right (rdp (subvec points max-i) epsilon)
                  ;; drop the shared duplicate (last of left == first of right)
                  right-rest (subvec right 1)]
              (into left right-rest))
            [(first points) (last points)]))))))

(defn simplify-vector
  "Figma-parity Simplify Vector (#56). Applies Ramer-Douglas-Peucker
  point reduction to the selected path's anchor points and rebuilds the
  path from the kept points (curves flatten to line segments between kept
  anchors — matches Figma's reduce-point-count behavior). `threshold`
  is the perpendicular-distance epsilon in path units; higher = more
  aggressive reduction. Closed paths stay closed. Purely additive: no-op
  when no path shapes are selected."
  ([]
   (simplify-vector nil 1.0))
  ([ids]
   (simplify-vector ids 1.0))
  ([ids threshold]
   (ptk/reify ::simplify-vector
     ptk/WatchEvent
     (watch [_ state _]
       (let [selected (or ids (dsh/lookup-selected state))
             objects  (dsh/lookup-page-objects state)
             path-ids (into [] (filter #(cph/path-shape? (get objects %))) selected)]
         (when (seq path-ids)
           (rx/of (dwsh/update-shapes
                   path-ids
                   (fn [shape]
                     (let [content (:content shape)
                           points (path/get-points content)
                           closed? (some #(= :close-path (:command %)) content)
                           reduced (rdp points threshold)
                           new-content (path/points->content reduced :close closed?)]
                       (path/update-geometry shape new-content)))))))))))
