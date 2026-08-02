;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.smart-selection
  "Pure geometry for Smart Selection (Figma parity gap #8).

  Detects whether a set of selected sibling shapes form a uniformly
  spaced 1D row, 1D column or 2D grid, and computes the new top-left
  positions that would `tidy up` the selection into a uniform layout.

  This namespace is pure: no side effects, no store, no emission. It is
  safe to call from data events and from the UI overlay alike."
  (:require
   [app.common.data :as d]))

;; A selection must contain at least this many items for Smart
;; Selection to engage (matches Figma's 3+ rule).
(def ^:private min-items 3)

;; Two gaps are considered the same when within this many canvas px
;; of the median gap. Tuned to match Figma's visual tolerance.
(def ^:private spacing-tolerance 2.0)

;; Centers (or edges) are considered aligned when within this many
;; canvas px of each other.
(def ^:private align-tolerance 2.0)

(defn- abs [n]
  (if (neg? n) (- n) n))

(defn- near? [a b tol]
  (<= (abs (- a b)) tol))

(defn- median
  [xs]
  (let [s  (sort xs)
        n  (count s)
        m  (quot n 2)]
    (if (odd? n)
      (nth s m)
      (/ (+ (nth s (dec m)) (nth s m)) 2.0))))

;; --- bbox helpers ------------------------------------------------

(defn- bbox
  "Build a small AABB descriptor from a shape's selrect (the
  axis-aligned bounding box that already accounts for rotation)."
  [shape]
  (let [r (:selrect shape)]
    {:id (:id shape)
     :x  (:x r)
     :y  (:y r)
     :w  (:width r)
     :h  (:height r)}))

(defn- x-center [b] (+ (:x b) (/ (:w b) 2)))
(defn- y-center [b] (+ (:y b) (/ (:h b) 2)))

(defn- x-gap [a b] (- (:x b) (+ (:x a) (:w a))))
(defn- y-gap [a b] (- (:y b) (+ (:y a) (:h b))))

(defn- uniform-gaps?
  "True when every gap is within `spacing-tolerance` of the median gap
  and the median gap is positive (i.e. items do not overlap)."
  [gaps]
  (let [med (median gaps)]
    (and (pos? med)
         (every? #(near? % med spacing-tolerance) gaps))))

;; --- 1D detection ------------------------------------------------

(defn- detect-row
  [bboxes]
  (when (>= (count bboxes) min-items)
    (let [sorted (sort-by :x bboxes)
          gaps  (mapv (fn [[a b]] (x-gap a b)) (partition 2 1 sorted))
          ys    (map :y bboxes)
          y0    (first (sort ys))]
      (when (and (uniform-gaps? gaps)
                 (every? #(near? % y0 align-tolerance) ys))
        {:axis    :horizontal
         :spacing (median gaps)
         :items   (vec sorted)}))))

(defn- detect-column
  [bboxes]
  (when (>= (count bboxes) min-items)
    (let [sorted (sort-by :y bboxes)
          gaps  (mapv (fn [[a b]] (y-gap a b)) (partition 2 1 sorted))
          xs    (map :x bboxes)
          x0    (first (sort xs))]
      (when (and (uniform-gaps? gaps)
                 (every? #(near? % x0 align-tolerance) xs))
        {:axis    :vertical
         :spacing (median gaps)
         :items   (vec sorted)}))))

;; --- 2D grid detection -------------------------------------------

(defn- cluster-rows
  "Group bboxes into rows by proximity of their top edge. Items within
  `align-tolerance` of the first item seen in the current row are
  appended to it; otherwise a new row is started."
  [bboxes]
  (let [sorted (sort-by (juxt :y :x) bboxes)]
    (loop [items  sorted
           rows    []
           cur     []
           cur-y   nil]
      (if (empty? items)
        (if (empty? cur) rows (conj rows cur))
        (let [b (:y (first items))]
          (if (or (nil? cur-y) (near? b cur-y align-tolerance))
            (recur (rest items) rows (conj cur (first items)) (or cur-y b))
            (recur (rest items) (conj rows cur) [(first items)] b)))))))

(defn- detect-grid
  [bboxes]
  (when (>= (count bboxes) min-items)
    (let [rows (cluster-rows bboxes)]
      (when (>= (count rows) 2)
        (let [cols-count (count (first rows))]
          ;; Every row must have the same number of columns (>= 2) so
          ;; the layout reads as a proper grid rather than a ragged
          ;; staircase.
          (when (and (>= cols-count 2)
                     (every? #(= (count %) cols-count) rows))
            (let [row-gaps
                  (mapv (fn [row]
                          (let [s (sort-by :x row)]
                            (mapv (fn [[a b]] (x-gap a b))
                                  (partition 2 1 s))))
                        rows)

                  row-spacings (mapv (fn [g] (when (seq g) (median g))) row-gaps)
                  defined      (filter some? row-spacings)
                  x-spacing    (when (seq defined) (median defined))

                  ;; Columns must be vertically aligned across rows.
                  cols-aligned?
                  (let [cols (apply map vector (map #(sort-by :x %) rows))]
                    (every? (fn [col]
                              (let [xs (map :x col)]
                                (every? #(near? % (first xs) align-tolerance) xs)))
                            cols))]

              (when (and x-spacing
                         (every? #(near? % x-spacing spacing-tolerance) defined)
                         cols-aligned?)
                ;; Row-to-row y gaps must be uniform too.
                (let [sorted-rows (sort-by #(:y (first (sort-by :x %))) rows)
                      row-gaps-y
                      (mapv (fn [[ra rb]]
                              (let [bottom (reduce max (map #(+ (:y %) (:h %)) ra))
                                    top    (:y (first (sort-by :x rb)))]
                                (- top bottom)))
                            (partition 2 1 sorted-rows))]
                  (when (uniform-gaps? row-gaps-y)
                    {:axis        :grid
                     :spacing     x-spacing
                     :row-spacing (median row-gaps-y)
                     :rows        sorted-rows
                     :cols        cols-count
                     :items       (vec (apply concat sorted-rows))}))))))))))

;; --- public detection -------------------------------------------

(defn detect-smart-selection
  "Given a collection of selected shapes, return a map describing the
  uniform layout when the shapes are 3+ siblings arranged in a uniform
  row, column or grid; otherwise return nil.

  Returned map shape:
    {:axis :horizontal|:vertical|:grid
     :spacing <px>            ;; uniform gap along the primary axis
     :row-spacing <px>         ;; grid only: uniform gap between rows
     :rows  [...]              ;; grid only: rows of bboxes
     :cols  <int>              ;; grid only
     :items [...]              ;; bboxes in reading order (sorted)"
  [shapes]
  (when (and (seq shapes) (>= (count shapes) min-items))
    (let [shapes    (vec shapes)
          parent-id (:parent-id (first shapes))]
      ;; All selected shapes must share a parent to be considered a
      ;; Smart Selection group (Figma only engages on siblings).
      (when (every? #(= (:parent-id %) parent-id) shapes)
        (let [bboxes (mapv bbox shapes)]
          (or (detect-grid bboxes)
              (detect-row bboxes)
              (detect-column bboxes)))))))

;; --- tidy-up geometry -------------------------------------------

(defn tidy-up-positions
  "Given a layout returned by `detect-smart-selection`, compute the new
  top-left positions that arrange the items into a uniform layout.

  Returns a map {shape-id [x y]} of new canvas-space top-left
  positions. Preserves the overall bounding-box top-left, uses the
  detected (median) spacing, and keeps row-major reading order (items
  are sorted by y then x)."
  [layout]
  (when (some? layout)
    (let [items   (:items layout)
          start-x (reduce min (map :x items))
          start-y (reduce min (map :y items))]
      (case (:axis layout)
        :horizontal
        (let [sorted  (sort-by (juxt :y :x) items)
              spacing (:spacing layout)]
          (loop [rem sorted x start-x result {}]
            (if (empty? rem)
              result
              (let [b (first rem)]
                (recur (rest rem)
                       (+ x (:w b) spacing)
                       (assoc result (:id b) [x start-y]))))))

        :vertical
        (let [sorted  (sort-by (juxt :x :y) items)
              spacing (:spacing layout)]
          (loop [rem sorted y start-y result {}]
            (if (empty? rem)
              result
              (let [b (first rem)]
                (recur (rest rem)
                       (+ y (:h b) spacing)
                       (assoc result (:id b) [start-x y]))))))

        :grid
        (let [rows      (:rows layout)
              x-spacing (:spacing layout)
              y-spacing (or (:row-spacing layout) x-spacing)
              row-h     (mapv (fn [row] (reduce max (map :h row))) rows)]
          (loop [rem-rows rows r 0 y start-y result {}]
            (if (empty? rem-rows)
              result
              (let [row (sort-by :x (first rem-rows))
                    row-result
                    (loop [cells row x start-x acc {}]
                      (if (empty? cells)
                        acc
                        (let [b (first cells)]
                          (recur (rest cells)
                                 (+ x (:w b) x-spacing)
                                 (assoc acc (:id b) [x y])))))]
                (recur (rest rem-rows)
                       (inc r)
                       (+ y (nth row-h r) y-spacing)
                       (merge result row-result))))))))))