;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.types.shape.polygon
  "Figma-parity polygon + star tools (gap #58).

  This namespace owns the polygon/star SVG path builders used by the
  frontend renderer (shapes.cljs) to draw these shapes. The schemas
  (schema:polygon-attrs / schema:star-attrs) are declared inline in
  shape.cljc so they can merge with the generic/base attrs in the
  schema:shape-attrs multi without a load-order cycle; this namespace
  holds the geometry helpers instead."
  (:require
   [app.common.geom.point :as gpt]
   [app.common.math :as mth]
   [clojure.string :as str]))

(defn- polar->point
  "Convert a polar coordinate (center cx/cy, radius r, angle in radians)
  to a cartesian point."
  [cx cy r angle]
  (gpt/point (+ cx (* r (mth/cos angle)))
             (+ cy (* r (mth/sin angle)))))

(defn polygon-points
  "Return the vertex points of a regular polygon with `point-count` sides
  inscribed in a circle of radius `r` centered at (cx, cy). The first
  vertex points up (-pi/2)."
  [cx cy r point-count]
  (let [step  (/ (* 2.0 mth/PI) point-count)
        start (- (/ mth/PI 2.0))]
    (mapv (fn [i] (polar->point cx cy r (+ start (* i step))))
          (range point-count))))

(defn star-points
  "Return the alternating outer/inner vertex points of a star with
  `point-count` outer points, outer radius `r` and inner radius `inner-r`
  (a ratio of `r`), centered at (cx, cy). The first outer point points up."
  [cx cy r point-count inner-r]
  (let [total (* 2 point-count)
        step  (/ (* 2.0 mth/PI) total)
        start (- (/ mth/PI 2.0))]
    (mapv (fn [i]
            (let [radius (if (even? i) r (* r inner-r))]
              (polar->point cx cy radius (+ start (* i step)))))
          (range total))))

(defn points->svg-path
  "Build a closed SVG path 'd' string from a sequence of points, using
  straight line segments. Empty/1-point input yields an empty string
  (the renderer must guard against that)."
  [points]
  (if (<= (count points) 1)
    ""
    (let [first-pt (first points)
          move     (str "M" (:x first-pt) "," (:y first-pt))
          lines    (map (fn [p] (str "L" (:x p) "," (:y p)))
                        (rest points))]
      (str (str/join " " (cons move lines)) " Z"))))

(defn polygon-svg-path
  "Build a closed SVG path 'd' string for a regular polygon filling the
  circle (cx, cy, r) with `point-count` sides. `corner-radius` is
  accepted for API symmetry with star but currently renders sharp corners
  (rounded polygon vertices are a deferred refinement; 0 = sharp = today)."
  [cx cy r point-count corner-radius]
  ;; corner-radius is accepted but not yet applied to keep the renderer
  ;; additive + low-risk; 0 (the default) is byte-identical to a sharp
  ;; polygon. Rounded polygon vertices are a deferred polish item.
  (let [_ corner-radius]
    (points->svg-path (polygon-points cx cy r point-count))))

(defn star-svg-path
  "Build a closed SVG path 'd' string for a star filling the circle
  (cx, cy, r) with `point-count` outer points and inner radius `inner-r`
  (a ratio of r). `corner-radius` rounds the outer points (0 = sharp)."
  [cx cy r point-count inner-r corner-radius]
  ;; corner-radius accepted for API symmetry; 0 (default) = sharp star,
  ;; byte-identical to a plain star. Rounded star points are deferred.
  (let [_ corner-radius]
    (points->svg-path (star-points cx cy r point-count inner-r))))

(defn point-in-polygon?
  "Ray-casting point-in-polygon test. Returns true when the point
  {:x :y} lies inside the polygon described by `poly` (a sequence of
  {:x :y} points). Used by the Figma-parity lasso selection tool (gap
  #51) to test shape-bound corners against the captured freehand path.
  An empty / <3-point polygon returns false."
  [{:keys [x y]} poly]
  (let [n (count poly)]
    (if (< n 3)
      false
      (loop [i 0
             j (dec n)
             inside false]
        (if (>= i n)
          inside
          (let [pi  (nth poly i)
                pj  (nth poly j)
                xi  (:x pi)
                yi  (:y pi)
                xj  (:x pj)
                yj  (:y pj)
                ;; Standard PNPOLY ray cast: edge crosses the horizontal
                ;; ray at y, and the crossing is left of x.
                crosses? (not= (>= yi y) (>= yj y))
                left?    (< x (+ xi (* (/ (- xj xi) (- yj yi)) (- y yi))))]
            (recur (inc i)
                   i
                   (if (and crosses? left?)
                     (not inside)
                     inside))))))))