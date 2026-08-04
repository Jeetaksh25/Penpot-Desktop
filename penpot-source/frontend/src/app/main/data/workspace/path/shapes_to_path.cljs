;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.path.shapes-to-path
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cph]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.common.types.container :as ctn]
   [app.common.types.path :as path]
   [app.common.types.path.helpers :as helpers]
   [app.common.types.path.impl :as impl]
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
;; shape's stroke into an editable filled path. Implemented as a pure
;; CLJS stroke expansion on the frontend-SVG renderer (no render-wasm
;; needed): for each stroke we offset the shape's path content by
;; +half-width and -half-width (reusing `offset-content` from the
;; Offset Vector section), then join the two offsets with end caps
;; (round / square / butt per the stroke line-cap, defaulting to round)
;; into a single closed filled path per source shape. The render-wasm
;; path (`wasm.api/stroke-to-path`, used by
;; `convert-selected-strokes-to-path`) is kept as a FALLBACK ONLY: it is
;; reached solely when render-wasm/v1 is active AND the frontend impl
;; produced no geometry (e.g. unsupported shape types). Shapes with no
;; stroke or zero-width strokes are left unchanged.
(defn outline-stroke
  "Convert strokes on the selected shapes (or the given ids) into
   sibling filled path shapes — Figma's Outline Stroke."
  ([]
   (outline-stroke nil))
  ([ids]
   (ptk/reify ::outline-stroke
     ptk/WatchEvent
     (watch [it state _]
       (let [selected (or ids (dsh/lookup-selected state))
             objects  (dsh/lookup-page-objects state)
             stroked  (into [] (filter #(seq (:strokes (get objects %)))) selected)]
         (when (seq stroked)
           (let [page-id (:current-page-id state)
                 result
                 (reduce
                  (fn [acc shape-id]
                    (let [shape   (get objects shape-id)
                          strokes (:strokes shape)
                          content (:content (path/convert-to-path shape objects))]
                      (if-let [outline (outline-content-for-shape content strokes)]
                        (let [position (cph/get-position-on-parent objects shape-id)
                              primary  (peek (vec strokes))
                              new-shape
                              (cts/setup-shape
                               (cond-> {:type             :path
                                        :id               (uuid/next)
                                        :name             (str (:name shape) " (outline)")
                                        :parent-id        (:parent-id shape)
                                        :frame-id         (:frame-id shape)
                                        :content          (:content outline)
                                        :fills            [(stroke->fill primary)]
                                        :strokes          []}
                                 (:transform shape)          (assoc :transform (:transform shape))
                                 (:transform-inverse shape)  (assoc :transform-inverse (:transform-inverse shape))
                                 (:flip-x shape)             (assoc :flip-x (:flip-x shape))
                                 (:flip-y shape)             (assoc :flip-y (:flip-y shape))
                                 (:even-odd? outline)        (assoc :svg-attrs {:fillRule "evenodd"})))]
                          (-> acc
                              (update :new-shapes conj {:shape new-shape :index (inc position)})
                              (update :updated-ids conj shape-id)))
                        acc)))
                  {:new-shapes [] :updated-ids []}
                  stroked)]
             (if (and (empty? (:new-shapes result))
                      (features/active-feature? state "render-wasm/v1"))
               ;; Fallback: render-wasm-backed stroke-to-path conversion.
               (rx/of (convert-selected-strokes-to-path stroked))
               (let [new-ids (into [] (map (comp :id :shape)) (:new-shapes result))
                     changes (as-> (pcb/empty-changes it page-id) changes
                               (pcb/with-objects changes objects)
                               (reduce (fn [changes {:keys [shape index]}]
                                         (pcb/add-object changes shape {:index index}))
                                       changes
                                       (:new-shapes result))
                               (pcb/update-shapes changes
                                                  (:updated-ids result)
                                                  (fn [shape] (assoc shape :strokes []))))]
                 (rx/of (dch/commit-changes changes)
                        (dws/select-shapes (into (d/ordered-set) new-ids))))))))))))

;; --- Outline-stroke private helpers -----------------------------------------

(defn- reverse-subpath
  "Reverse a subpath of offset segment maps (each {:type :line/:curve
  :start :end :h1 :h2}). Curves have their handles swapped so the
  reversed curve traces the same geometry."
  [segs]
  (into []
        (map (fn [{:keys [type start end h1 h2]}]
               (case type
                 :line  {:type :line :start end :end start}
                 :curve {:type :curve :start end :end start :h1 h2 :h2 h1})))
        (rseq segs)))

(defn- cap-points
  "Returns the list of points forming a stroke end cap at point `P`,
  where `T` is the path-direction unit tangent (pointing along the
  path from start to end) and `half` is half the stroke width.
  `start?` true means this is the START cap (contour goes -side -> +side);
  false means the END cap (contour goes +side -> -side). `cap-type` is
  :round / :square / :butt."
  [P T half cap-type start?]
  (let [N     (gpt/normal-left T)
        out   (gpt/scale T (if start? -1 1))
        plus  (gpt/add P (gpt/scale N half))
        minus (gpt/subtract P (gpt/scale N half))
        base  (case cap-type
                :butt  [plus minus]
                :square [plus
                         (gpt/add (gpt/add P (gpt/scale out half)) (gpt/scale N half))
                         (gpt/add (gpt/add P (gpt/scale out half)) (gpt/scale N (- half)))
                         minus]
                ;; :round (default) — semicircle from plus to minus through P+out*half
                (let [r        (mth/abs half)
                      ang-plus (mth/atan2 (:y (gpt/scale N half)) (:x (gpt/scale N half)))
                      ang-out  (mth/atan2 (:y out) (:x out))
                      raw      (mod (- ang-out ang-plus) (* 2 mth/PI))
                      to-out   (if (> raw mth/PI) (- raw (* 2 mth/PI)) raw)
                      dir      (if (mth/almost-zero? to-out) 1 (if (neg? to-out) -1 1))
                      steps    6]
                  (into []
                        (for [k (range (inc steps))]
                          (let [ang (+ ang-plus (* dir (/ (* mth/PI k) steps)))]
                            (gpt/add P (gpt/point (* r (mth/cos ang)) (* r (mth/sin ang)))))))))]
    (if start? (into [] (rseq base)) base)))

(defn- build-outline-subpath
  "Build the filled outline contour(s) for a single subpath of the
  source path, given half the stroke width and the cap type. Returns
  a map {:contours [<plain-segs>...] :even-odd? bool}.

  Closed subpath -> two concentric closed contours (offset +half and
  -half) intended to be filled with fill-rule even-odd (a ring).

  Open subpath -> a single closed band contour: forward offset, end
  cap, reversed reverse offset, start cap, close."
  [{:keys [segs closed?]} half cap-type]
  (if closed?
    (let [fwd (cleanup-self-intersection (offset-subpath {:segs segs :closed? true}  half      :miter 4) true)
          rev (cleanup-self-intersection (offset-subpath {:segs segs :closed? true} (- half) :miter 4) true)]
      {:contours  [(segs->plain fwd true) (segs->plain rev true)]
       :even-odd? true})
    (let [fwd      (cleanup-self-intersection (offset-subpath {:segs segs :closed? false}  half      :miter 4) false)
          rev      (cleanup-self-intersection (offset-subpath {:segs segs :closed? false} (- half) :miter 4) false)
          rev-rev  (reverse-subpath rev)
          T-start  (seg-tangent-start (first segs))
          T-end    (seg-tangent-end   (last segs))
          P-start  (:start (first segs))
          P-end    (:end   (last segs))
          end-cap  (cap-points P-end   T-end   half cap-type false)
          start-cap (cap-points P-start T-start half cap-type true)
          cap-segs (fn [pts] (into [] (for [[a b] (partition 2 1 pts)] {:type :line :start a :end b})))
          band     (into [] (concat fwd (cap-segs end-cap) rev-rev (cap-segs start-cap)))]
      {:contours  [(segs->plain band true)]
       :even-odd? false})))

(defn- outline-content-for-shape
  "Given a shape's path `content` and its `strokes` list, build a single
  PathData whose subpaths are the outline contours of every (positive
  width) stroke. Returns {:content <PathData> :even-odd? bool} or nil
  when no stroke produces any geometry."
  [content strokes]
  (let [subs (content->subpaths content)
        cap-for (fn [s]
                  (let [c (or (:stroke-cap-end s) (:stroke-cap-start s) :round)]
                    (if (#{:round :square :butt} c) c :round)))]
    (loop [acc [] even? false ss strokes]
      (if-let [s (first ss)]
        (let [w (:stroke-width s 0)]
          (if (pos? w)
            (let [half (/ w 2.0)
                  cap (cap-for s)
                  built (into [] (mapcat #(get (build-outline-subpath % half cap) :contours)) subs)
                  closed-any (some :closed? subs)]
              (recur (into acc built) (or even? closed-any) (rest ss)))
            (recur acc even? (rest ss))))
        (when (seq acc)
          {:content (impl/from-plain (into [] (mapcat identity) acc))
           :even-odd? even?})))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; OFFSET VECTOR (Figma-parity #55)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Robust pure-CLJS path offset. Curves are offset as CURVES (control
;; polygon offset: each control point is moved along the normal at its
;; end of the segment, approximating a true parallel curve), joins
;; support miter (default, with miter-limit bevel fallback) / round /
;; bevel, and a self-intersection cleanup pass flattens + prunes
;; back-tracking loops when an offset subpath folds back on itself.

(defn- content->subpaths
  "Parse plain path content into a vector of subpaths. Each subpath is
  a map {:segs [<seg-map>...] :closed? bool} where a seg-map is
  {:type :line/:curve :start :end :h1 :h2}. A close-path command emits
  a closing :line seg when the last point differs from the subpath
  start."
  [content]
  (let [segs (vec content)]
    (loop [i 0 cur-start nil prev nil subs [] cur []]
      (if (= i (count segs))
        (let [cur (if (and cur-start prev (not (gpt/close? prev cur-start)))
                    (conj cur {:type :line :start prev :end cur-start}) cur)]
          (if (seq cur) (conj subs {:segs cur :closed? false}) subs))
        (let [{:keys [command params]} (nth segs i)]
          (case command
            :move-to  (let [p (gpt/point (:x params) (:y params))]
                        (recur (inc i) p p
                               (if (seq cur) (conj subs {:segs cur :closed? false}) subs)
                               []))
            :line-to  (let [p (gpt/point (:x params) (:y params))]
                        (recur (inc i) cur-start p subs
                               (conj cur {:type :line :start prev :end p})))
            :curve-to (let [p  (gpt/point (:x params) (:y params))
                            h1 (gpt/point (:c1x params) (:c1y params))
                            h2 (gpt/point (:c2x params) (:c2y params))]
                        (recur (inc i) cur-start p subs
                               (conj cur {:type :curve :start prev :end p :h1 h1 :h2 h2})))
            :close-path (let [cur (if (and cur-start prev (not (gpt/close? prev cur-start)))
                                    (conj cur {:type :line :start prev :end cur-start}) cur)]
                          (recur (inc i) nil nil (conj subs {:segs cur :closed? true}) []))
            (recur (inc i) cur-start prev subs cur)))))))

(defn- seg-tangent-start
  "Unit tangent vector of a seg-map at its start point."
  [seg]
  (case (:type seg)
    :line  (gpt/unit (gpt/to-vec (:start seg) (:end seg)))
    :curve (let [v (gpt/to-vec (:start seg) (:h1 seg))]
             (if (gpt/almost-zero? v)
               (gpt/unit (gpt/to-vec (:start seg) (:end seg)))
               (gpt/unit v)))))

(defn- seg-tangent-end
  "Unit tangent vector of a seg-map at its end point."
  [seg]
  (case (:type seg)
    :line  (gpt/unit (gpt/to-vec (:start seg) (:end seg)))
    :curve (let [v (gpt/to-vec (:h2 seg) (:end seg))]
             (if (gpt/almost-zero? v)
               (gpt/unit (gpt/to-vec (:start seg) (:end seg)))
               (gpt/unit v)))))

(defn- offset-seg-raw
  "Offset a single seg-map by `distance` using control-polygon offset:
  each control point is moved along the normal at its end of the
  segment (start side uses the start tangent's left normal, end side
  uses the end tangent's left normal). Curves stay curves."
  [seg distance]
  (let [ts (seg-tangent-start seg)
        te (seg-tangent-end seg)
        ns (gpt/normal-left ts)
        ne (gpt/normal-left te)
        o  (fn [p n] (gpt/add p (gpt/scale n distance)))]
    (case (:type seg)
      :line  (assoc seg :start (o (:start seg) ns) :end (o (:end seg) ne))
      :curve (assoc seg
                    :start (o (:start seg) ns)
                    :h1    (o (:h1 seg) ns)
                    :h2    (o (:h2 seg) ne)
                    :end   (o (:end seg) ne)))))

(defn- intersect-lines
  "Intersection of two infinite lines, the first through `p1` with unit
  direction `d1`, the second through `p2` with unit direction `d2`.
  Returns the intersection point or nil when the lines are parallel."
  [p1 d1 p2 d2]
  (let [cross-d (- (* (:x d1) (:y d2)) (* (:y d1) (:x d2)))]
    (if (mth/almost-zero? cross-d)
      nil
      (let [dp (gpt/to-vec p1 p2)
            t  (/ (- (* (:x dp) (:y d2)) (* (:y dp) (:x d2))) cross-d)]
        (gpt/add p1 (gpt/scale d1 t))))))

(defn- join-points
  "Compute the offset geometry at a path vertex where an incoming
  segment (tangent `tin`) meets an outgoing segment (tangent `tout`).
  `distance` is the offset, `join` is :miter / :round / :bevel, and
  `miter-limit` caps the miter length (falling back to bevel).

  Returns one of:
    {:single <point>}     — single miter point, shared by both segs
    {:bevel [<a> <b>]}    — incoming ends at a, outgoing starts at b
    {:round [<pt>...]}    — arc from a (incoming end) to b (outgoing start)"
  [vertex tin tout distance join miter-limit]
  (let [nin     (gpt/normal-left tin)
        nout    (gpt/normal-left tout)
        a-end   (gpt/add vertex (gpt/scale nin distance))
        b-start (gpt/add vertex (gpt/scale nout distance))
        cross-d (- (* (:x tin) (:y tout)) (* (:y tin) (:x tout)))
        dot-d   (+ (* (:x tin) (:x tout)) (* (:y tin) (:y tout)))]
    (cond
      (mth/almost-zero? cross-d)
      {:single a-end}

      (= join :bevel)
      {:bevel [a-end b-start]}

      (= join :round)
      (let [sweep  (mth/atan2 cross-d dot-d)
            r      (mth/abs distance)
            ang-a  (mth/atan2 (:y nin) (:x nin))
            steps  (max 2 (int (mth/ceil (/ (mth/abs sweep) (/ mth/PI 8)))))
            arc    (into []
                         (for [k (range (inc steps))]
                           (let [ang (+ ang-a (* sweep (/ k steps)))]
                             (gpt/add vertex (gpt/point (* r (mth/cos ang)) (* r (mth/sin ang)))))))]
        {:round arc})

      :else
      (let [miter (intersect-lines a-end tin b-start tout)]
        (if (nil? miter)
          {:bevel [a-end b-start]}
          (let [ml   (gpt/distance vertex miter)
                half (mth/abs distance)]
            (if (or (mth/almost-zero? half) (> ml (* miter-limit half)))
              {:bevel [a-end b-start]}
              {:single miter})))))))

(defn- offset-subpath
  "Offset a single subpath (map with :segs and :closed?) by `distance`,
  applying `join` (with `miter-limit`) at every interior vertex (and at
  the wrap vertex for closed subpaths). Returns a vector of offset
  seg-maps. Round joins are flattened to line segs along the arc."
  [{:keys [segs closed?]} distance join miter-limit]
  (if (empty? segs)
    []
    (let [n      (count segs)
          raw    (into [] (map #(offset-seg-raw % distance)) segs)
          vcount (if closed? n (dec n))
          joins  (into []
                       (for [vi (range vcount)]
                         (let [ip      (if closed? (mod (inc vi) n) (inc vi))
                               tin     (seg-tangent-end (nth segs vi))
                               tout    (seg-tangent-start (nth segs ip))
                               vertex  (:end (nth raw vi))]
                           (join-points vertex tin tout distance join miter-limit))))
          start-of (fn [i]
                     (cond
                       (and (not closed?) (zero? i)) (:start (nth raw 0))
                       :else (let [pj (if closed? (mod (dec i) n) (dec i))
                                   jr (nth joins pj)]
                               (cond (:single jr) (:single jr)
                                     (:bevel jr)  (second (:bevel jr))
                                     (:round jr)  (peek (:round jr))))))
          end-of (fn [i]
                   (cond
                     (and (not closed?) (= i (dec n))) (:end (nth raw i))
                     :else (let [jr (nth joins i)]
                             (cond (:single jr) (:single jr)
                                   (:bevel jr)  (first (:bevel jr))
                                   (:round jr)  (first (:round jr))))))
          extra-after (fn [i]
                        (when (or closed? (< i (dec n)))
                          (let [jr (nth joins i)]
                            (cond
                              (:round jr) (into [] (for [[a b] (partition 2 1 (:round jr))]
                                                     {:type :line :start a :end b}))
                              (:bevel jr) [{:type :line :start (first (:bevel jr)) :end (second (:bevel jr))}]
                              :else nil))))
      ]
      (loop [acc [] i 0]
        (if (>= i n)
          acc
          (let [seg (-> (nth raw i) (assoc :start (start-of i)) (assoc :end (end-of i)))
                acc (conj acc seg)
                acc (if-let [extra (extra-after i)] (into acc extra) acc)]
            (recur acc (inc i))))))))

(defn- segs->plain
  "Convert a vector of offset seg-maps into a plain segment vector
  (move-to + line-to/curve-to), appending a close-path when `close?`."
  [segs close?]
  (if (empty? segs)
    []
    (let [build (fn [{:keys [type end h1 h2]}]
                  (case type
                    :line  (helpers/make-line-to end)
                    :curve (helpers/make-curve-to end h1 h2)))]
      (cond-> (into [(helpers/make-move-to (:start (first segs)))]
                    (map build)
                    segs)
        close? (conj {:command :close-path :params {}})))))

(defn- flatten-seg
  "Flatten a single seg-map to a list of polyline points (start + each
  sub-line end). Curves use `helpers/curve->lines`."
  [seg]
  (case (:type seg)
    :line  [(:start seg) (:end seg)]
    :curve (let [lines (helpers/curve->lines (:start seg) (:end seg) (:h1 seg) (:h2 seg))]
             (into [(:start seg)] (map second lines)))))

(defn- flatten-subpath
  "Flatten a list of offset seg-maps to a polyline of points. When
  `closed?` the first point is repeated at the end."
  [segs closed?]
  (if (empty? segs)
    []
    (loop [acc [] ss segs]
      (if (empty? ss)
        (if closed? (conj acc (first acc)) acc)
        (let [fp (flatten-seg (first ss))]
          (recur (into acc (if (empty? acc) fp (subvec fp 1))) (rest ss)))))))

(defn- seg-seg-intersect
  "Proper crossing point of segments [p1 p2] and [p3 p4], or nil when
  they don't cross in their interiors."
  [p1 p2 p3 p4]
  (let [d1 (gpt/to-vec p1 p2)
        d2 (gpt/to-vec p3 p4)
        denom (- (* (:x d1) (:y d2)) (* (:y d1) (:x d2)))]
    (if (mth/almost-zero? denom)
      nil
      (let [dp (gpt/to-vec p1 p3)
            t  (/ (- (* (:x dp) (:y d2)) (* (:y dp) (:x d2))) denom)
            u  (/ (- (* (:x dp) (:y d1)) (* (:y dp) (:x d1))) denom)]
        (if (and (> t 1e-9) (< t (- 1 1e-9))
                 (> u 1e-9) (< u (- 1 1e-9)))
          (gpt/add p1 (gpt/scale d1 t))
          nil)))))

(defn- polyline-self-intersects?
  "True when the polyline has any pair of non-adjacent segments that
  cross. For a closed polyline (first==last point) the wrap segment is
  treated as adjacent to the first."
  [pts]
  (let [m   (count pts)
        sgs (for [i (range (dec m))] [(nth pts i) (nth pts (inc i))])
        sm  (count sgs)]
    (if (< sm 4)
      false
      (some?
       (some (fn [[i j]]
               (seg-seg-intersect (first (nth sgs i)) (second (nth sgs i))
                                  (first (nth sgs j)) (second (nth sgs j))))
             (for [i (range sm) j (range sm)
                   :when (and (> j (inc i))
                              (not (and (zero? i) (= j (dec sm)))))]
               [i j]))))))

(defn- prune-loops
  "Greedy back-tracking-loop removal. Walks `pts` left to right,
  accepting each point; when the segment from the last accepted point
  to the candidate crosses an earlier accepted segment, collapses the
  loop back to the crossing point and retries. Returns a cleaned
  polyline with self-intersection loops collapsed."
  [pts]
  (if (< (count pts) 2)
    pts
    (loop [res [(first pts)] idx 1]
      (if (>= idx (count pts))
        res
        (let [cand    (nth pts idx)
              collapse (fn collapse [res]
                         (let [from  (peek res)
                               m     (count res)
                               found (loop [k 0]
                                       (if (>= k (dec m))
                                         nil
                                         (if-let [ip (seg-seg-intersect from cand (nth res k) (nth res (inc k)))]
                                           [k ip]
                                           (recur (inc k)))))]
                           (if found
                             (let [[k ip] found] (collapse (into (subvec res 0 (inc k)) [ip])))
                             res)))
              res' (collapse res)]
          (recur (conj res' cand) (inc idx)))))))

(defn- cleanup-self-intersection
  "If the offset subpath self-intersects, flatten it, prune back-tracking
  loops, and rebuild as line segments (acceptable for the destructive
  Object command — curves are preserved in the common case where no
  self-intersection is detected). Otherwise return the offset segs
  unchanged."
  [offset-segs closed?]
  (let [pts (flatten-subpath offset-segs closed?)]
    (if (not (polyline-self-intersects? pts))
      offset-segs
      (let [pruned (prune-loops pts)
            pruned (if closed? (conj pruned (first pruned)) pruned)]
        (into [] (for [[a b] (partition 2 1 pruned)] {:type :line :start a :end b}))))))

(defn offset-content
  "Offset path `content` by `distance`. Curves offset as curves (control
  polygon offset); joins are miter (default) / round / bevel with a
  miter-limit bevel fallback; self-intersecting offset subpaths are
  cleaned up. Returns a PathData or nil for degenerate input.

  Optional kwargs (defaults): :join :miter, :miter-limit 4, :cap nil.

  When `:cap` is non-nil (one of :butt/:round/:square) OPEN subpaths are
  offset into a closed band: the forward offset + an end cap + the
  reverse of the negative offset + a start cap (reusing the
  outline-stroke band builder with half-width = distance). This is the
  Illustrator 'Offset Path on an open path with caps' behaviour. Closed
  subpaths and the default (`:cap` nil, open subpath stays open) are
  byte-identical to the prior implementation."
  ([content distance]
   (offset-content content distance {}))
  ([content distance {:keys [join miter-limit cap] :or {join :miter miter-limit 4}}]
   (let [subs (content->subpaths content)]
     (when (seq subs)
       (let [result (into []
                          (mapcat
                           (fn [{:keys [closed?] :as sp}]
                             (cond
                               ;; Open subpath + cap requested -> closed band
                               ;; (forward offset + cap + reverse offset + cap).
                               ;; Flatten all band contours into one command stream.
                               (and (not closed?) (some? cap))
                               (mapcat #(segs->plain % true)
                                       (:contours (build-outline-subpath sp distance cap)))

                               :else
                               (segs->plain (cleanup-self-intersection
                                             (offset-subpath sp distance join miter-limit)
                                             closed?)
                                            closed?))))
                          subs)]
         (when (seq result)
           (impl/from-plain result)))))))

(defn offset-vector
  "Figma-parity Offset Vector (#55). Produces a new path whose outline is
  offset by `distance` from each selected path shape. Curves are offset
  as curves, joins default to miter (with miter-limit bevel fallback);
  callers may pass an opts map {:join :miter/:round/:bevel
  :miter-limit <n>} to extend the behavior. Purely additive: no-op when
  no path shapes are selected."
  ([]
   (offset-vector nil 1.0))
  ([ids]
   (offset-vector ids 1.0))
  ([ids distance]
   (offset-vector ids distance {}))
  ([ids distance opts]
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
                           offset (offset-content content distance opts)]
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; LIVE OFFSET PATH EFFECT (ALL_APPS_PARITY P1.32)
;;
;; A non-destructive Offset Path *effect* stored on the shape ALONGSIDE the
;; destructive `offset-vector` command above. The shape keeps its original
;; `:content`; the renderer (`app.main.ui.shapes.path`) applies the offset at
;; draw time when the `:offset-effect` slot is present, so the effect is
;; fully reversible (`clear-offset-effect` restores the original outline)
;; and can be tweaked live (distance/join/miter-limit/cap). `bake-offset-effect`
;; finalizes it into the path data (the pre-effect content is recoverable via
;; undo — there is no live copy kept on the shape).
;;
;; The `:offset-effect` map: {:distance <num> :join :miter/:round/:bevel
;;                           :miter-limit <num> :cap nil/:butt/:round/:square}
;; (`:cap` nil leaves open subpaths open — the default; a non-nil cap thickens
;; open subpaths into a closed band, reusing the outline-stroke builder).
;;
;; Byte-identical when absent: legacy shapes have no `:offset-effect` slot, so
;; `path-shape` takes the legacy branch and the SVG is unchanged.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn set-offset-effect
  "Store (or, when `effect` is nil, clear) the live Offset Path effect on
  each selected path shape. Purely additive: no-op when no path shapes
  are selected."
  ([effect]
   (set-offset-effect nil effect))
  ([ids effect]
   (ptk/reify ::set-offset-effect
     ptk/WatchEvent
     (watch [_ state _]
       (let [selected (or ids (dsh/lookup-selected state))
             objects  (dsh/lookup-page-objects state)
             path-ids (into [] (filter #(cph/path-shape? (get objects %))) selected)]
         (when (seq path-ids)
           (rx/of (dwsh/update-shapes
                   path-ids
                   (fn [shape]
                     (if (some? effect)
                       (assoc shape :offset-effect effect)
                       (dissoc shape :offset-effect)))))))))))

(defn clear-offset-effect
  "Remove the live Offset Path effect from each selected path shape,
  restoring the original outline. No-op when a selected path has no
  `:offset-effect`."
  ([]
   (clear-offset-effect nil))
  ([ids]
   (set-offset-effect ids nil)))

(defn bake-offset-effect
  "Finalize the live Offset Path effect on each selected path shape:
  applies the offset to `:content` (destructive path-data rewrite) and
  removes the `:offset-effect` slot. The pre-effect content is NOT kept
  on the shape — recover it via undo. No-op when a selected path has no
  `:offset-effect`."
  ([]
   (bake-offset-effect nil))
  ([ids]
   (ptk/reify ::bake-offset-effect
     ptk/WatchEvent
     (watch [_ state _]
       (let [selected (or ids (dsh/lookup-selected state))
             objects  (dsh/lookup-page-objects state)
             path-ids (into [] (filter #(cph/path-shape? (get objects %))) selected)]
         (when (seq path-ids)
           (rx/of (dwsh/update-shapes
                   path-ids
                   (fn [shape]
                     (let [effect (:offset-effect shape)]
                       (if (nil? effect)
                         shape
                         (let [{:keys [distance join miter-limit cap]
                                :or {join :miter miter-limit 4}} effect
                               content (:content shape)
                               offset  (offset-content content distance
                                        {:join join :miter-limit miter-limit :cap cap})]
                           (if (some? offset)
                             (-> shape
                                 (dissoc :offset-effect)
                                 (path/update-geometry offset))
                             (dissoc shape :offset-effect))))))))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; EXPAND / EXPAND APPEARANCE (ALL_APPS_PARITY P2.17)
;;
;; A unified command that finalizes selected shapes into editable anchor
;; paths. It reuses the canonical common `convert-to-path` (already wired
;; through `convert-selected-to-path`) for the shape-tier expansion and
;; adds a live-effect bake pass for the Offset Path effect:
;;   - :bool  -> flattened single path (boolean engine) + children removed
;;   - :rect / :circle / :group / :frame / :image / :text -> converted to
;;     a path via the common shape->path transform
;;   - :path with a live :offset-effect -> the offset is baked into the
;;     path data and the effect slot removed
;;   - any other shape is left as-is
;;
;; The pre-expand shape state is recoverable via undo: both sub-events
;; (`convert-selected-to-path` and `bake-offset-effect`) record their
;; changes through `pcb` / `dwsh/update-shapes`, so the history stack holds
;; the pre-expand shape even though the path data is rewritten in place.
;; Full Expand Appearance (baking ALL live appearance effects — stroke-to-
;; fill, blur, shadows, gradients-as-stroke, etc.) is DEFERRED: it waits on
;; a live-effect stack architecture that does not yet exist; this command
;; covers the path/bool/offset cases that DO exist today.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- partition-for-expand
  "Splits the selected shape ids into the two expansion buckets:
  `:path-effect-ids` (paths carrying a live Offset Path effect to bake)
  and `:convert-ids` (bool/rect/circle/group/frame/image/text to convert
  to an editable path via the common converter). Everything else is
  dropped (left untouched)."
  [objects selected]
  (reduce (fn [acc id]
            (let [shape (get objects id)]
              (cond
                (nil? shape) acc
                (and (= :path (:type shape))
                     (some? (:offset-effect shape)))
                (update acc :path-effect-ids conj id)
                (#{:bool :rect :circle :group :frame :image :text}
                 (:type shape))
                (update acc :convert-ids conj id)
                :else acc)))
          {:path-effect-ids [] :convert-ids []}
          selected))

(defn expand-selection
  "ALL_APPS_PARITY P2.17 — Expand / Expand Appearance (path-tier). Finalize
  the selected shapes into editable anchor paths: booleans are flattened,
  rects/ellipses/groups/frames/images/text are turned into paths, and
  live Offset Path effects are baked. The pre-expand state is recoverable
  via undo. Purely additive: no-op when nothing selected needs expanding."
  ([]
   (expand-selection nil))
  ([ids]
   (ptk/reify ::expand-selection
     ptk/WatchEvent
     (watch [_ state _]
       (let [selected (or ids (dsh/lookup-selected state))
             objects  (dsh/lookup-page-objects state)
             {:keys [path-effect-ids convert-ids]}
             (partition-for-expand objects selected)
             events (cond-> []
                      (seq path-effect-ids)
                      (conj (bake-offset-effect path-effect-ids))
                      (seq convert-ids)
                      (conj (convert-selected-to-path convert-ids)))]
         (when (seq events)
           (apply rx/of events)))))))
