;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.types.path.arrangement
  "Planar arrangement / subdivision engine for the Shape Builder tool.

  Given a collection of overlapping paths, this namespace computes all
  pairwise segment intersections, splits every crossing segment into
  non-overlapping EDGES, and builds a half-edge (DCEL) planar
  subdivision into FACES.  Each face is a closed region of the plane
  bounded by edges and records which original sub-paths bound/contain
  it.

  This is the geometric foundation for an Illustrator-style Shape
  Builder edit mode: a later UI can hover-highlight a face and
  click/drag to merge or erase regions.

  Pure CLJC data logic -- no rendering, no IO.  It reuses the same
  segment-intersection helpers from `app.common.types.path.helpers`
  that power `app.common.types.path.bool` (line/line, line/curve and
  curve/curve intersection + the `split-*-ranges` splitters), and the
  `close-paths` / `add-previous` preparatory steps from
  `app.common.types.path.bool` so behaviour stays byte-identical with
  the existing boolean engine.

  Input  :: a collection of shapes, each a map `{:id any? :content
  path-content}` where `:content` is a plain vector of Penpot path
  command maps (the same format `bool.cljc` consumes).

  Output :: a map `{:vertices :edges :half-edges :faces}` produced by
  `compute-arrangement`."
  (:require
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.math :as mth]
   [app.common.types.path.bool :as bool]
   [app.common.types.path.helpers :as helpers]
   [app.common.types.path.subpath :as subpath]))

#?(:clj (set! *warn-on-reflection* true))

;; ---------------------------------------------------------------------------
;; Configuration
;; ---------------------------------------------------------------------------

(def ^:private default-gap-tolerance
  "Default vertex-snapping distance in pixels.  Imperfect pen-tool
  output often leaves sub-pixel gaps between endpoints that should
  close a region; endpoints within this distance are merged into a
  single arrangement vertex."
  0.5)

(def ^:private area-epsilon
  "Cycles with an absolute chord-signed area below this value are
  considered degenerate (e.g. a dangling edge traversed forward then
  immediately back) and are discarded as faces."
  1e-6)

(def ^:private interior-eps
  "Small offset used to nudge a representative point off an edge so it
  lies strictly inside a face for containment tests."
  0.01)

;; ---------------------------------------------------------------------------
;; Path preparation
;; ---------------------------------------------------------------------------

(defn- close-open-subpaths
  "Adds a closing `line-to` to every open subpath so the arrangement
  treats open filled paths as closed regions.  Closed subpaths are
  left untouched.  Operates on plain content (a seq of command maps)."
  [content]
  (let [subpaths (subpath/get-subpaths content)]
    (into
     []
     (mapcat
      (fn [sp]
        (let [data (:data sp)]
          (if (subpath/is-closed? sp)
            data
            (conj data (helpers/make-line-to (:from sp)))))))
     subpaths)))

(defn- prepare-content
  "Normalises a single shape's content for arrangement processing:
  optionally closes open subpaths, converts `close-path` commands to
  explicit `line-to` (via `bool/close-paths`) and attaches a `:prev`
  point to every command (via `bool/add-previous`).  The result is a
  vector of commands each carrying its start point."
  [content open-as-closed]
  (-> content
      (cond-> open-as-closed close-open-subpaths)
      (bool/close-paths)
      (bool/add-previous)))

;; ---------------------------------------------------------------------------
;; Source segments
;; ---------------------------------------------------------------------------

(defn- collect-source-segments
  "Walks every input shape and returns a flat vector of drawable
  segment maps, one per `line-to`/`curve-to` command.  Each map is

    {:id          <int>           ;; index in the returned vector
     :shape-id    <any?>          ;; the originating shape's :id
     :subpath-id  {:shape-id .. :idx ..}
     :command     :line-to/:curve-to
     :params      <params map>
     :prev        <gpt/point>}    ;; start point (set by add-previous)

  These are the atomic units that get split at intersections."
  [shapes open-as-closed]
  (letfn [(shape->segs [{:keys [id content]}]
            (let [prepared (prepare-content content open-as-closed)
                  subpaths (subpath/get-subpaths prepared)]
              (into
               []
               (mapcat
                (fn [idx sp]
                  (let [sub-id {:shape-id id :idx idx}]
                    (into
                     []
                     (keep
                      (fn [cmd]
                        (when (bool/is-segment? cmd)
                          {:command    (:command cmd)
                           :params     (:params cmd)
                           :prev       (:prev cmd)
                           :shape-id   id
                           :subpath-id sub-id})))
                     (:data sp)))))
                (d/enumerate subpaths))))]
    (let [all (into [] (mapcat shape->segs) shapes)]
      (mapv (fn [[i seg]] (assoc seg :id i)) (d/enumerate all)))))

;; ---------------------------------------------------------------------------
;; Intersections (reuses helpers/line-line, line-curve, curve-curve)
;; ---------------------------------------------------------------------------

(defn- split-ts
  "Dispatch wrapper around the public helpers used by `bool.cljc`'s
  private `split-ts`.  Given two source segment maps, returns a pair
  `[ts-1 ts-2]` where each element is either nil or a vector of
  parametric t-values on the respective segment.  Mirrors the case
  dispatch of `app.common.types.path.bool/split-ts` so intersection
  semantics are identical to the boolean engine."
  [seg-1 seg-2]
  (let [cmd-1 (:command seg-1)
        cmd-2 (:command seg-2)]
    (cond
      (and (= :line-to cmd-1) (= :line-to cmd-2))
      (helpers/line-line-intersect (helpers/command->line seg-1)
                                   (helpers/command->line seg-2))

      (and (= :line-to cmd-1) (= :curve-to cmd-2))
      (helpers/line-curve-intersect (helpers/command->line seg-1)
                                    (helpers/command->bezier seg-2))

      (and (= :curve-to cmd-1) (= :line-to cmd-2))
      ;; Arguments are reversed -- swap the result components back so
      ;; the first vector is still ts for seg-1 (the curve).
      (let [[line-ts curve-ts]
            (helpers/line-curve-intersect (helpers/command->line seg-2)
                                          (helpers/command->bezier seg-1))]
        [curve-ts line-ts])

      (and (= :curve-to cmd-1) (= :curve-to cmd-2))
      (helpers/curve-curve-intersect (helpers/command->bezier seg-1)
                                     (helpers/command->bezier seg-2))

      :else [nil nil])))

(defn- interior-t?
  "True for a t-value strictly inside (0,1), with a small guard so
  endpoint coincidences (t=0 / t=1) are not recorded as splits -- the
  split-*-ranges helpers already preserve segment endpoints."
  [t]
  (and (number? t)
       (> t 1e-6)
       (< t 0.999999)))

(defn- collect-intersection-ts
  "Computes every pairwise intersection across `segs` (including
  self-intersections within the same subpath) and returns a map
  `{seg-id -> sorted-set of interior t-values}`."
  [segs]
  (let [n (count segs)]
    (loop [i       0
           tvals   (into {} (map (fn [s] [(:id s) (sorted-set)])) segs)]
      (if (>= i n)
        tvals
        (let [seg-i (nth segs i)]
          (recur
           (inc i)
           (loop [j      (inc i)
                  tvals  tvals]
             (if (>= j n)
               tvals
               (let [seg-j   (nth segs j)
                     [ts-i ts-j] (split-ts seg-i seg-j)]
                 (recur
                  (inc j)
                  (cond-> tvals
                    (some? ts-i)
                    (update (:id seg-i) (fnil into (sorted-set))
                            (filterv interior-t? ts-i))
                    (some? ts-j)
                    (update (:id seg-j) (fnil into (sorted-set))
                            (filterv interior-t? ts-j)))))))))))))

;; ---------------------------------------------------------------------------
;; Splitting source segments into raw edges
;; ---------------------------------------------------------------------------

(defn- attach-prev
  "Given the sub-segments produced by `split-*-ranges` (which carry
  only `:params`) and the start point of the original segment, returns
  the sub-segments with a `:prev` point attached to each, in order."
  [subsegs start-point]
  (loop [result   []
         prev     start-point
         remaining subsegs]
    (if-let [s (first remaining)]
      (let [s  (assoc s :prev prev)
            np (helpers/segment->point s)]
        (recur (conj result s) np (rest remaining)))
      result)))

(defn- split-source-segment
  "Splits a single source segment at every collected t-value using
  the same helpers as the boolean engine, returning a list of
  sub-segment command maps (each with `:prev` attached)."
  [seg tvals]
  (let [prev (:prev seg)]
    (case (:command seg)
      :line-to  (attach-prev
                 (helpers/split-line-to-ranges prev seg tvals) prev)
      :curve-to (attach-prev
                 (helpers/split-curve-to-ranges prev seg tvals) prev)
      [(assoc seg :prev prev)])))

(defn- sub-segment->raw-edge
  "Converts a split sub-segment command into a 'raw edge' map
  carrying its endpoint points, curve handlers (when applicable) and
  provenance (subpath / source-segment id)."
  [sub-seg source-seg]
  (let [from-p  (:prev sub-seg)
        to-p    (helpers/segment->point sub-seg)
        params  (:params sub-seg)]
    {:command     (:command sub-seg)
     :from-point  from-p
     :to-point    to-p
     :h1          (when (= :curve-to (:command sub-seg))
                    (gpt/point (:c1x params) (:c1y params)))
     :h2          (when (= :curve-to (:command sub-seg))
                    (gpt/point (:c2x params) (:c2y params)))
     :subpath-id  (:subpath-id source-seg)
     :source-id   (:id source-seg)}))

(defn- split-all-segments
  "Splits every source segment at its intersections and returns a
  flat vector of raw-edge maps."
  [segs tvals]
  (into
   []
   (mapcat
    (fn [seg]
      (let [ts (or (seq (get tvals (:id seg))) [])]
        (->> (split-source-segment seg ts)
             (mapv #(sub-segment->raw-edge % seg))))))
   segs))

;; ---------------------------------------------------------------------------
;; Vertices (gap-tolerance snapping via union-find)
;; ---------------------------------------------------------------------------

(defn- build-vertices
  "Clusters all edge endpoint points within `gap-tolerance` into
  arrangement vertices using a simple union-find.  Returns

    {:point->vid  <point -> vertex-id>
     :vid->point  <vertex-id -> canonical point>}

  `point->vid` maps any endpoint point (by value) to its cluster id;
  `vid->point` maps each cluster id to a canonical representative
  point (the first point that joined the cluster)."
  [points gap-tolerance]
  (let [n      (count points)
        parent (volatile! (vec (range n)))

        find-root
        (fn find-root [x]
          (loop [x x]
            (let [p (nth @parent x)]
              (if (= p x) x (recur p)))))

        union
        (fn union [a b]
          (let [ra (find-root a) rb (find-root b)]
            (when (not= ra rb)
              (vswap! parent assoc ra rb))))]
    ;; O(n^2) clustering -- fine for Shape Builder scale (hundreds of
    ;; vertices).  Spatial hashing could be added later if needed.
    (doseq [i (range n)
            :let  [pi (nth points i)]
            j (range (inc i) n)
            :let  [pj (nth points j)]
            :when (< (gpt/distance pi pj) gap-tolerance)]
      (union i j))
    (let [vid->point
          (into {}
                (for [i (range n)]
                  (let [r (find-root i)]
                    [r (nth points r)])))
          point->vid
          (into {}
                (for [i (range n)]
                  [(nth points i) (find-root i)]))]
      {:point->vid point->vid
       :vid->point vid->point})))

;; ---------------------------------------------------------------------------
;; Edges
;; ---------------------------------------------------------------------------

(defn- reverse-raw-edge
  "Returns a raw edge oriented in the opposite direction (endpoints
  swapped and curve handlers exchanged).  Used to canonicalise edges
  so they always point from the lower vertex id to the higher."
  [raw]
  (-> raw
      (assoc :from-point (:to-point raw)
             :to-point   (:from-point raw)
             :h1         (:h2 raw)
             :h2         (:h1 raw))))

(defn- build-edges
  "Deduplicates raw edges into the final arrangement EDGES.  Each
  edge is canonicalised to point from the lower vertex id to the
  higher; collinear/coincident edges from different sub-paths are
  merged (their `:subpath-ids` and `:source-ids` sets are unioned),
  recording every original sub-path that borders the edge.

  Returns a vector of edge maps:

    {:id            <keyword>
     :from :to      <vertex-id>
     :from-point :to-point <gpt/point>
     :command       :line-to/:curve-to
     :h1 :h2        <gpt/point or nil>
     :subpath-ids   #{:subpath-id ...}
     :source-ids    #{int ...}}"
  [raw-edges point->vid gap-tolerance]
  (let [tagged
        (keep
         (fn [raw]
           (let [vf (get point->vid (:from-point raw))
                 vt (get point->vid (:to-point   raw))]
             ;; Skip edges that collapse to a single vertex after
             ;; gap-tolerance snapping (degenerate / zero-length).
             (when (and (some? vf) (some? vt) (not= vf vt))
               [vf vt raw])))
         raw-edges)

        ;; Key by the undirected vertex pair + command so overlapping
        ;; segments from different sub-paths fold into one edge.
        dedup
        (reduce
         (fn [acc [vf vt raw]]
           (let [forward?   (< vf vt)
                 min-v      (if forward? vf vt)
                 max-v      (if forward? vt vf)
                 canon      (if forward? raw (reverse-raw-edge raw))
                 key        [min-v max-v (:command raw)]
                 existing   (get acc key)]
             (assoc acc key
                    (if existing
                      (-> existing
                          (update :subpath-ids conj (:subpath-id raw))
                          (update :source-ids   conj (:source-id raw)))
                      {:id           (keyword (str "e" (count acc)))
                       :from         min-v
                       :to           max-v
                       :from-point   (:from-point canon)
                       :to-point     (:to-point canon)
                       :command      (:command canon)
                       :h1           (:h1 canon)
                       :h2           (:h2 canon)
                       :subpath-ids  #{(:subpath-id raw)}
                       :source-ids   #{(:source-id raw)}}))))
         {} tagged)]
    (vec (vals dedup))))

;; ---------------------------------------------------------------------------
;; Half-edges (DCEL)
;; ---------------------------------------------------------------------------

(defn- make-half-edges
  "Creates the two twin half-edges for every edge.  Returns a map
  `{he-id -> half-edge}` where each half-edge is

    {:id :edge :from :to :twin :next nil}

  `:next` is filled in later by `link-next`."
  [edges]
  (into
   {}
   (mapcat
    (fn [{:keys [id from to]}]
      (let [f-id (keyword (str (name id) "-f"))
            b-id (keyword (str (name id) "-b"))]
        [{f-id {:id f-id :edge id :from from :to to :twin b-id :next nil}}
         {b-id {:id b-id :edge id :from to  :to from :twin f-id :next nil}}]))
    edges)))

(defn- half-edge-angle
  "Polar angle (radians, -pi..pi) of a half-edge's direction.  Used
  to sort outgoing half-edges counter-clockwise around a vertex."
  [he vid->point]
  (let [f (vid->point (:from he))
        t (vid->point (:to   he))]
    (mth/atan2 (- (:y t) (:y f))
               (- (:x t) (:x f)))))

(defn- link-next
  "Wires up the DCEL `:next` pointers that chain half-edges into face
  cycles.  At each vertex the outgoing half-edges are sorted CCW by
  direction; applying the standard rule

      next(twin(out[i])) = out[(i - 1) mod k]

  yields consistently-oriented face cycles: bounded faces come out
  counter-clockwise (positive signed area) and the single unbounded
  face comes out clockwise (negative signed area)."
  [half-edges vid->point]
  (let [by-from (group-by :from (vals half-edges))
        sorted  (into {}
                  (for [[v hes] by-from]
                    [v (sort-by #(half-edge-angle % vid->point) hes)]))]
    (reduce
     (fn [hes [v out]]
       (let [k (count out)]
         (reduce
          (fn [hes i]
            (let [he   (nth out i)
                  twin (get hes (:twin he))
                  nxt  (nth out (mod (dec i) k))]
              (assoc hes (:id twin) (assoc twin :next (:id nxt)))))
          hes
          (range k))))
     half-edges
     sorted)))

;; ---------------------------------------------------------------------------
;; Cycle walking + classification
;; ---------------------------------------------------------------------------

(defn- walk-cycles
  "Follows `:next` pointers from every unvisited half-edge until
  returning to the start, collecting all distinct face-boundary
  cycles.  Returns a vector of cycles, each a vector of half-edge ids
  in traversal order."
  [half-edges]
  (letfn [(walk-one [start]
            ;; Walk a single face cycle from `start` until we loop
            ;; back to a visited half-edge (or hit a dangling edge
            ;; with no :next).  Returns the cycle as a vector of
            ;; half-edge ids.
            (loop [path    []
                   he-id   start
                   visited #{start}]
              (let [he  (get half-edges he-id)
                    nxt (:next he)]
                (cond
                  (nil? nxt)              (conj path he-id)
                  (contains? visited nxt) (conj path he-id)
                  :else                   (recur (conj path he-id)
                                                  nxt
                                                  (conj visited nxt))))))
          (remove-cycle [unvisited cycle]
            (reduce disj unvisited cycle))]
    (loop [unvisited (into #{} (keys half-edges))
           cycles    []]
      (if (empty? unvisited)
        cycles
        (let [cycle (walk-one (first unvisited))]
          (recur (remove-cycle unvisited cycle)
                 (conj cycles cycle)))))))

;; ---------------------------------------------------------------------------
;; Geometry helpers for cycles
;; ---------------------------------------------------------------------------

(defn- edge->oriented-geom
  "Returns the geometric primitive for a half-edge, oriented along
  its traversal direction.  For a line-to this is `[from to]`; for a
  curve-to it is `[start end h1 h2]` with handlers swapped when the
  half-edge runs opposite to the edge's canonical orientation."
  [he edge vid->point]
  (let [forward? (= (:from he) (:from edge))
        f (vid->point (:from he))
        t (vid->point (:to   he))]
    (case (:command edge)
      :line-to  [f t]
      :curve-to (if forward?
                  [f t (:h1 edge) (:h2 edge)]
                  [f t (:h2 edge) (:h1 edge)]))))

(defn- geom->selrect
  "Bounding selrect for an oriented line/curve geom (used by the
  winding-number ray cast)."
  [geom]
  (let [command (if (= 4 (count geom)) :curve-to :line-to)]
    (case command
      :line-to  (grc/points->rect geom)
      :curve-to (let [[f e h1 h2] geom]
                  (grc/points->rect
                   (into [f e]
                         (map #(helpers/curve-values geom %)
                              (helpers/curve-extremities geom))))))))

(defn- cycle->geom-data
  "Builds the `geom-data` vector expected by
  `helpers/is-point-in-geom-data?` for a cycle: one entry per
  half-edge with `:command`, `:geom` (oriented along traversal) and
  `:selrect`.  Because every half-edge is traversed consistently
  around the cycle, the non-zero winding sum correctly reports
  inside/outside."
  [cycle half-edges edges-by-id vid->point]
  (mapv
   (fn [he-id]
     (let [he   (get half-edges he-id)
           edge (get edges-by-id (:edge he))
           geom (edge->oriented-geom he edge vid->point)]
       {:command (:command edge)
        :geom    geom
        :selrect (geom->selrect geom)}))
   cycle))

(defn- point-in-cycle?
  "True when `point` lies inside the bounded region enclosed by
  `cycle` (non-zero winding rule)."
  [point cycle half-edges edges-by-id vid->point]
  (let [geom-data (cycle->geom-data cycle half-edges edges-by-id vid->point)]
    (helpers/is-point-in-geom-data? point geom-data)))

(defn- cycle-signed-area
  "Chord-based signed area of a cycle (sum of cross products of each
  half-edge's endpoints).  Positive for CCW (outer) cycles, negative
  for CW (hole) cycles.  Curves are approximated by their chords --
  sufficient for orientation classification."
  [cycle half-edges vid->point]
  (loop [area 0.0
         [he-id & rest] cycle]
    (if he-id
      (let [he (get half-edges he-id)
            f  (vid->point (:from he))
            t  (vid->point (:to   he))]
        (recur (+ area (- (* (:x f) (:y t)) (* (:x t) (:y f)))) rest))
      area)))

(defn- cycle-rep-point
  "Finds a point strictly inside the bounded region enclosed by
  `cycle`.  For each half-edge it tries the midpoint offset slightly
  to the left and to the right (perpendicular to the edge); the first
  candidate that passes `point-in-cycle?` is returned.  Falls back to
  the centroid of the cycle's vertices if no offset works (extremely
  degenerate cases)."
  [cycle half-edges edges-by-id vid->point]
  (let [found
        (some
         (fn [he-id]
           (let [he   (get half-edges he-id)
                 edge (get edges-by-id (:edge he))
                 geom (edge->oriented-geom he edge vid->point)
                 from (first geom)
                 to   (second geom)
                 mid  (gpt/lerp from to 0.5)
                 dir  (gpt/to-vec from to)
                 len  (gpt/length dir)]
             (when (not (mth/almost-zero? len))
               (let [lu (gpt/unit (gpt/normal-left  dir))
                     ru (gpt/unit (gpt/normal-right dir))
                     c1 (gpt/add mid (gpt/scale lu interior-eps))
                     c2 (gpt/add mid (gpt/scale ru interior-eps))]
                 (cond
                   (point-in-cycle? c1 cycle half-edges edges-by-id vid->point) c1
                   (point-in-cycle? c2 cycle half-edges edges-by-id vid->point) c2
                   :else nil)))))
         cycle)]
    (or found
        (let [pts (mapv #(vid->point (:from (get half-edges %))) cycle)]
          (gpt/scale (reduce gpt/add pts) (/ 1.0 (count pts)))))))

;; ---------------------------------------------------------------------------
;; Faces (cycle classification + hole assignment)
;; ---------------------------------------------------------------------------

(defn- assign-holes
  "Given outer (CCW) and hole (CW) cycles with their representative
  points, assigns each hole to the smallest outer cycle that contains
  it.  Holes not contained in any outer cycle belong to the unbounded
  face.  Returns

    {:face-holes  {outer-cycle -> [hole-cycle ...]}
     :unbounded-holes [hole-cycle ...]}"
  [outers holes rep-points half-edges edges-by-id vid->point]
  (let [outer-info
        (mapv (fn [oc]
                {:cycle oc
                 :rep   (get rep-points oc)
                 :area  (cycle-signed-area oc half-edges vid->point)})
              outers)]
    (reduce
     (fn [acc hc]
       (let [hrep (get rep-points hc)
             containers
             (filterv
              (fn [oi]
                (point-in-cycle? hrep (:cycle oi) half-edges edges-by-id vid->point))
              outer-info)]
         (if (empty? containers)
           (update acc :unbounded-holes conj hc)
           (let [smallest (apply min-key :area containers)]
             (update acc :face-holes update (:cycle smallest) conj hc)))))
     {:face-holes  {}
      :unbounded-holes []}
     holes)))

;; Forward declarations: `content->geom-data` and `face-rep-point` are defined
;; further down in this namespace but referenced above (in `build-faces`).
;; CLJS/CLJ analyze top-level forms top-down, so these forward references trip
;; `:undeclared-var` without eager `declare`s.
(declare content->geom-data face-rep-point)

(defn- build-faces
  "Turns the walked cycles into FACE records.  Each bounded face has
  one outer (CCW) cycle and zero or more hole (CW) cycles; the
  unbounded face has only hole cycles (one per connected component).

  Each face map is

    {:id :type :bounded? :outer-cycle :holes :edges :half-edges
     :rep-point :contains-subpaths :area}

  `:contains-subpaths` is computed by testing the face's
  representative point against every original sub-path's closed
  content with the non-zero winding rule."
  [cycles half-edges edges-by-id vid->point subpath-content]
  (let [areas     (into {} (map #(vector % (cycle-signed-area % half-edges vid->point))) cycles)
        reps      (into {} (map #(vector %
                                         (cycle-rep-point % half-edges edges-by-id vid->point)))
                        cycles)
        outer     (filterv #(> (get areas %) area-epsilon) cycles)
        holes     (filterv #(< (get areas %) (- area-epsilon)) cycles)
        ;; zero-area cycles are dangling edges -> discarded
        {:keys [face-holes unbounded-holes]}
        (assign-holes outer holes reps half-edges edges-by-id vid->point)

        ;; Pre-compute geom-data per original sub-path for containment
        ;; winding tests.
        subpath-geom
        (into {}
              (for [[sub-id content] subpath-content]
                [sub-id (content->geom-data content)]))

        face-id (atom 0)]
    (conj
     (mapv
      (fn [oc]
        (let [hs   (get face-holes oc [])
              rep  (face-rep-point oc hs reps half-edges edges-by-id vid->point)
              contains
              (into #{}
                    (keep
                     (fn [[sub-id geom]]
                       (when (helpers/is-point-in-geom-data? rep geom)
                         sub-id)))
                    subpath-geom)
              he-all (into (vec oc) (mapcat identity) (vals hs))]
          {:id                 (keyword (str "f" (swap! face-id inc)))
           :type               :face
           :bounded?           true
           :outer-cycle        oc
           :holes              hs
           :half-edges         he-all
           :edges              (distinct (map #(-> half-edges (get %) :edge) he-all))
           :rep-point          rep
           :contains-subpaths  contains
           :area               (get areas oc)}))
      outer)
     {:id                 :unbounded
      :type               :face
      :bounded?           false
      :outer-cycle        nil
      :holes              unbounded-holes
      :half-edges         (vec unbounded-holes)
      :edges              (distinct (map #(-> half-edges (get %) :edge) unbounded-holes))
      :rep-point          nil
      :contains-subpaths  #{}
      :area               nil})))

(defn- face-rep-point
  "Picks a representative point inside the face's region (inside the
  outer cycle AND outside every hole).  Starts from the outer
  cycle's rep point and, if it falls inside a hole, retries
  candidate points derived from successive outer half-edges (each
  edge's midpoint nudged toward the interior) until a valid one is
  found.  Falls back to the outer rep point if none qualify."
  [outer-cycle holes reps half-edges edges-by-id vid->point]
  (let [hole-cycles  (vec holes)
        not-in-hole? (fn [p]
                       (not-any?
                        #(point-in-cycle? p % half-edges edges-by-id vid->point)
                        hole-cycles))
        outer-rep    (get reps outer-cycle)
        edge-candidates
        (eduction
         (map
          (fn [he-id]
            (let [he   (get half-edges he-id)
                  edge (get edges-by-id (:edge he))
                  geom (edge->oriented-geom he edge vid->point)
                  from (first geom)
                  to   (second geom)
                  mid  (gpt/lerp from to 0.5)
                  dir  (gpt/to-vec from to)]
              (when (not (mth/almost-zero? (gpt/length dir)))
                (let [lu (gpt/unit (gpt/normal-left dir))]
                  (gpt/add mid (gpt/scale lu interior-eps)))))))
         (filter some?)
         outer-cycle)]
    (or (some (fn [p] (when (not-in-hole? p) p))
              (cons outer-rep edge-candidates))
        outer-rep)))

;; ---------------------------------------------------------------------------
;; Sub-path closed content (for containment tests)
;; ---------------------------------------------------------------------------

(defn- content->geom-data
  "Builds the `geom-data` vector for a closed sub-path's content
  (each `line-to`/`curve-to` with `:prev` attached).  Used by the
  non-zero winding containment test in `build-faces`."
  [content]
  (mapv
   (fn [cmd]
     {:command (:command cmd)
      :geom    (if (= :line-to (:command cmd))
                 (helpers/command->line cmd)
                 (helpers/command->bezier cmd))
      :selrect (helpers/command->selrect cmd (:prev cmd))})
   (filter #(#{:line-to :curve-to} (:command %)) content)))

(defn- collect-subpath-content
  "Returns a map `{subpath-id -> prepared closed content}` for every
  input shape, used as the containment-test source.  Content is
  prepared exactly as the segments were (close open subpaths when
  `open-as-closed`, convert close-path to line-to, attach :prev)."
  [shapes open-as-closed]
  (into
   {}
   (mapcat
    (fn [{:keys [id content]}]
      (let [prepared (prepare-content content open-as-closed)
            subpaths (subpath/get-subpaths prepared)]
        (for [[idx sp] (d/enumerate subpaths)]
          [{:shape-id id :idx idx} (:data sp)]))))
   shapes))

;; ---------------------------------------------------------------------------
;; Closed-path emission (divide-into-faces)
;; ---------------------------------------------------------------------------

(defn- half-edge->command
  "Builds the Penpot path command for a half-edge, oriented along its
  traversal direction (handlers swapped when running opposite to the
  edge's canonical orientation)."
  [he edge vid->point]
  (let [forward? (= (:from he) (:from edge))
        to-pt    (vid->point (:to he))]
    (case (:command edge)
      :line-to  (helpers/make-line-to to-pt)
      :curve-to (if forward?
                  (helpers/make-curve-to to-pt (:h1 edge) (:h2 edge))
                  (helpers/make-curve-to to-pt (:h2 edge) (:h1 edge))))))

(defn- cycle->content
  "Emits a closed sub-path for a cycle: `move-to` the first vertex,
  one command per half-edge, then `close-path`."
  [cycle half-edges edges-by-id vid->point]
  (let [first-he (get half-edges (first cycle))
        start-pt (vid->point (:from first-he))]
    (conj
     (into
      [{:command :move-to
        :params {:x (:x start-pt) :y (:y start-pt)}}]
      (map (fn [he-id]
             (let [he   (get half-edges he-id)
                   edge (get edges-by-id (:edge he))]
               (half-edge->command he edge vid->point))))
      cycle)
     {:command :close-path})))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn compute-arrangement
  "Compute the planar arrangement of a collection of overlapping
  paths.

  `shapes` is a sequence of maps `{:id any? :content path-content}`.

  Options:
    :gap-tolerance  px distance within which endpoints are snapped to
                    a single vertex (default 0.5).  Lets imperfect
                    pen-tool output still close regions.
    :open-as-closed when true, open filled sub-paths are treated as
                    closed for arrangement purposes (default false).

  Returns a map:

    {:vertices    {vertex-id -> point}
     :edges       [edge-map ...]
     :half-edges  {he-id -> half-edge-map}
     :faces       [face-map ...]}

  Edge map fields: :id :from :to :from-point :to-point :command
  :h1 :h2 :subpath-ids :source-ids.

  Face map fields: :id :type :bounded? :outer-cycle :holes
  :half-edges :edges :rep-point :contains-subpaths :area.  The
  unbounded (outer) face is always present with :bounded? false."
  [shapes & {:keys [gap-tolerance open-as-closed]
             :or {gap-tolerance default-gap-tolerance
                  open-as-closed false}}]
  (let [shapes           (vec shapes)
        segs             (collect-source-segments shapes open-as-closed)
        tvals            (collect-intersection-ts segs)
        raw-edges        (split-all-segments segs tvals)
        all-points       (into []
                               (mapcat
                                (fn [re]
                                  [(:from-point re) (:to-point re)]))
                               raw-edges)
        {:keys [point->vid vid->point]}
                         (build-vertices all-points gap-tolerance)
        edges            (build-edges raw-edges point->vid gap-tolerance)
        edges-by-id      (into {} (map #(vector (:id %) %)) edges)
        half-edges       (-> (make-half-edges edges)
                             (link-next vid->point))
        cycles           (walk-cycles half-edges)
        subpath-content  (collect-subpath-content shapes open-as-closed)
        faces            (build-faces cycles half-edges edges-by-id
                                      vid->point subpath-content)]
    {:vertices   vid->point
     :edges      edges
     :half-edges half-edges
     :faces      faces}))

(defn point-in-face
  "Hit-test a cursor `point` against an arrangement produced by
  `compute-arrangement`.

  Returns a map describing what is under the cursor:
    `{:type :edge  :edge edge-map}`      -- cursor lies on an edge
    `{:type :face  :face face-map}`      -- cursor lies inside a face
    `{:type :outside}`                   -- cursor lies in the
                                           unbounded face

  Edges are checked first (a cursor exactly on a boundary is reported
  as an edge hit).  Face containment uses the non-zero winding rule
  against the face's outer cycle and holes."
  [arrangement point]
  (let [{:keys [edges half-edges vertices faces]} arrangement
        edges-by-id (into {} (map #(vector (:id %) %)) edges)
        vid->point  vertices]
    (or
     ;; 1. Edge hit -- cursor on a boundary segment.
     (some
      (fn [edge]
        (let [geom [(:from-point edge) (:to-point edge)]]
          (if (= :line-to (:command edge))
            (when (helpers/segment-has-point? point geom)
              {:type :edge :edge edge})
            (when (helpers/curve-has-point? point
                   [(:from-point edge) (:to-point edge) (:h1 edge) (:h2 edge)])
              {:type :edge :edge edge}))))
      edges)
     ;; 2. Face hit -- inside a bounded face's outer cycle and not in
     ;;    any of its holes.
     (some
      (fn [face]
        (when (:bounded? face)
          (let [outer (:outer-cycle face)
                holes (:holes face)]
            (when (and (point-in-cycle? point outer half-edges edges-by-id vid->point)
                       (not-any? #(point-in-cycle? point % half-edges edges-by-id vid->point)
                                 holes))
              {:type :face :face face}))))
      faces)
     ;; 3. Outside everything.
     {:type :outside})))

(defn divide-into-faces
  "Pathfinder-style Divide: returns one closed path per bounded face
  of the arrangement.  Each path is a map

    `{:face-id <id> :content <path-content>}`

  where `:content` is a plain vector of Penpot command maps.  A face
  with holes produces a compound path: an outer sub-path followed by
  one sub-path per hole (each closed).  The unbounded face is
  excluded."
  [arrangement]
  (let [{:keys [faces half-edges edges vertices]} arrangement
        edges-by-id (into {} (map #(vector (:id %) %)) edges)
        vid->point  vertices]
    (into
     []
     (keep
      (fn [face]
        (when (:bounded? face)
          (let [outer (cycle->content (:outer-cycle face) half-edges edges-by-id vid->point)
                holes (mapv #(cycle->content % half-edges edges-by-id vid->point)
                            (:holes face))]
            {:face-id (:id face)
             :content (into outer (mapcat identity) holes)}))))
     faces)))