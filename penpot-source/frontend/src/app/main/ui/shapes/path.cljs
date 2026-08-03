;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.shapes.path
  (:require
   [app.common.data.macros :as dm]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.logging :as log]
   [app.common.math :as mth]
   [app.common.types.path :as path]
   [app.main.ui.shapes.custom-stroke :refer [shape-custom-strokes]]
   [rumext.v2 :as mf]))

(defn- content->string
  [content]
  (cond
    (nil? content)
    ""

    (path/content? content)
    (.toString content)

    :else
    (let [content (path/content content)]
      (.toString content))))

;; ---------------------------------------------------------------------------
;; Brush path-following stamp (feature slot #52, APPROX)
;;
;; When a path shape carries a non-nil :brush-id slot AND the resolved brush
;; def is available, the path is rendered as a series of <use> stamps placed
;; along the path content instead of a single stroked <path>. The path itself
;; is NOT stroked in brush mode -- the stamps are the visual.
;;
;; CORE INVARIANT (byte-identical when the feature slot is absent):
;;   - Legacy paths have no :brush-id slot (absent = nil), so `path-shape`
;;     always takes the legacy branch and emits the exact pre-existing
;;     `[:& shape-custom-strokes {:shape shape} [:path {:d pdata}]]` form.
;;   - When :brush-id is some? but the brush def cannot be resolved (file has
;;     no :brushes asset -> brush prop is nil), `brush-stamp-shape` short
;;     circuits to that same legacy form. No <defs>, <symbol>, <use>, <g> or
;;     :transform is emitted in either case.
;; ---------------------------------------------------------------------------

(defn- brush-seeded-rand
  "Deterministic pseudo-random in [0,1) from a non-negative index and numeric
  seed. Pure function of its inputs (NO Math/random) so re-renders produce
  byte-identical stamp placements."
  [index seed]
  (let [v (mth/sin (+ (* (+ index 1) 12.9898)
                      (* (mod seed 1000000) 78.233)))]
    (- (mth/abs v) (mth/floor (mth/abs v)))))

(defn- brush-sample-points
  "Walks the path `content` as a polyline (endpoints via `path/get-points`;
  curve-to interiors are approximated by their endpoint polyline -- a TODO
  for finer Casteljau subdivision) and returns a vector of stamp placements
  of the form `[idx fx fy rotate-str]`, sampled every `spacing` units of
  arc length.

  `mode` is either :stretch (stamp rotated to the local tangent, no offset)
  or :scatter (no tangent rotation, plus a perpendicular offset up to
  `scatter` whose magnitude is SEEDED BY INDEX for determinism).

  Returns an empty vector when the content cannot be sampled (nil/short
  path, non-positive spacing, zero length)."
  [content spacing mode scatter seed]
  (let [points (when (some? content)
                 (try
                   (path/get-points content)
                   (catch :default _ nil)))
        n      (count points)]
    (cond
      (or (nil? points) (< n 2) (<= spacing 0)) []

      :else
      (let [cumul (loop [i 1 acc [0]]
                    (if (>= i n)
                      acc
                      (let [p0 (nth points (dec i))
                            p1 (nth points i)
                            d  (gpt/distance p0 p1)]
                        (recur (inc i) (conj acc (+ (peek acc) d))))))
            total (peek cumul)]
        (if (<= total 0)
          []
          (let [stamps (inc (int (/ total spacing)))]
            (loop [idx 0 result []]
              (if (>= idx stamps)
                result
                (let [target (* idx spacing)
                      found  (loop [j 1]
                               (if (or (>= j n) (<= target (nth cumul j)))
                                 j
                                 (recur (inc j))))
                      si      (min found (dec n))
                      seg-start (nth cumul (dec si))
                      seg-end   (nth cumul si)
                      p0 (nth points (dec si))
                      p1 (nth points si)
                      t  (if (> seg-end seg-start)
                           (/ (- target seg-start) (- seg-end seg-start))
                           0)
                      pt (gpt/lerp p0 p1 t)
                      dx (- (dm/get-prop p1 :x) (dm/get-prop p0 :x))
                      dy (- (dm/get-prop p1 :y) (dm/get-prop p0 :y))
                      len (mth/sqrt (+ (* dx dx) (* dy dy)))
                      rotate-str (if (= mode :stretch)
                                   (let [ang (gpt/angle (gpt/point dx dy))]
                                     (dm/str "rotate(" (mth/round ang) ") "))
                                   "")
                      [ox oy] (if (= mode :scatter)
                                (let [r     (brush-seeded-rand idx seed)
                                      off   (* (- r 0.5) 2.0 scatter)
                                      plen  (if (> len 0) len 1)
                                      px    (/ (- dy) plen)
                                      py    (/ dx plen)]
                                  [(* off px) (* off py)])
                                [0 0])
                      fx (+ (dm/get-prop pt :x) ox)
                      fy (+ (dm/get-prop pt :y) oy)]
                  (recur (inc idx) (conj result [idx fx fy rotate-str])))))))))))

(mf/defc brush-stamp-shape
  {::mf/props :obj}
  [{:keys [shape brush pdata]}]
  (if (nil? brush)
    ;; :brush-id was set but the brush def could not be resolved (the file
    ;; has no :brushes asset). Short-circuit to the exact legacy path output
    ;; so the SVG is byte-for-byte identical to a non-brush path.
    [:& shape-custom-strokes {:shape shape}
     [:path {:d pdata}]]

    (let [brush-id (dm/get-prop shape :brush-id)
          shape-id (dm/get-prop shape :id)
          content  (get shape :content)
          width    (dm/get-prop shape :width)
          spacing  (or (:spacing brush)
                       (if (and (number? width) (> width 0)) (/ width 8) 10))
          size     (or (:size brush)
                       (if (and (number? width) (> width 0)) (/ width 8) 10))
          mode     (or (:mode brush) :stretch)
          scatter  (or (:scatter brush) 0)
          opacity  (or (:opacity brush) 1)
          color    (or (:color brush) "#000000")
          seed     (hash (str brush-id))
          sym-id   (dm/str "brush-stamp-" brush-id "-" shape-id)
          samples  (mf/with-memo [content spacing mode scatter seed]
                     (brush-sample-points content spacing mode scatter seed))
          transform (gsh/transform-str shape)]
      [:g.brush-shape {:transform transform}
       [:defs
        ;; TODO(feature #52): render the brush :source-shape-id (a closed
        ;; vector shape from `objects`) here via the same workspace
        ;; shape-wrapper used in workspace/shapes.cljs so all fills/strokes
        ;; of the source shape apply. A unit ellipse stamp stands in for now
        ;; and is scaled/rotated per sample by each <use> below.
        [:> "symbol" {:id sym-id}
         [:ellipse {:cx 0 :cy 0 :rx 1 :ry 1 :fill color}]]]
       (for [[idx fx fy rotate-str] samples]
         [:use {:key      (dm/str "stamp-" idx)
                :href     (dm/str "#" sym-id)
                :transform (dm/str "translate(" fx "," fy ") " rotate-str "scale(" size ")")
                :opacity  opacity}])])))

(mf/defc path-shape
  {::mf/props :obj}
  [{:keys [shape brush objects render-id]}]
  (let [content (get shape :content)
        pdata   (mf/with-memo [content]
                  (try
                    (content->string content)
                    (catch :default cause
                      (log/error :hint "unexpected error on formatting path"
                                 :shape-name (:name shape)
                                 :shape-id (:id shape)
                                 :cause cause)
                      "")))]
    (if (some? (:brush-id shape))
      [:& brush-stamp-shape {:shape shape
                             :brush brush
                             :objects objects
                             :render-id render-id
                             :pdata pdata}]
      [:& shape-custom-strokes {:shape shape}
       [:path {:d pdata}]])))