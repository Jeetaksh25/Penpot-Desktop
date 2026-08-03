;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.geom.shapes.bounds
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.geom.rect :as grc]
   [app.common.math :as mth]
   [app.common.types.path :as path]
   [app.common.types.stroke :as cts]))

(defn shape-stroke-margin
  [shape stroke-width]
  (if (cfh/path-shape? shape)
    ;; TODO: Calculate with the stroke offset (not implemented yet)
    (+ stroke-width (mth/sqrt (* 2 stroke-width stroke-width)))
    (mth/sqrt (* 2 stroke-width stroke-width))))

(defn- apply-filters
  [attr type filters]
  (sequence
   (comp
    (remove :hidden)
    (filter #(= (attr %) type))
    (map (fn [item]
           {:id (dm/str "filter_" (:id item))
            :type type
            :params item})))
   filters))

(defn shape->filters
  [shape]
  (d/concat-vec
   [{:id "BackgroundImageFix" :type :image-fix}]

   ;; Texture is emitted BEFORE drop-shadows so the drop-shadow filter-in
   ;; chains from the textured alpha (shadows compute from the displaced
   ;; graphic). Only present when (seq (remove :hidden (:texture shape))).
   (->> shape :texture (apply-filters :type :texture))

   (->> shape :shadow (apply-filters :style :drop-shadow))
   [{:id "shape" :type :blend-filters}]
   (->> shape :shadow (apply-filters :style :inner-shadow))

   ;; Noise is an overlay appended after inner-shadows; bounded to the
   ;; shape (clipped to SourceAlpha) so no filter-region growth is added.
   ;; Only present when (seq (remove :hidden (:noise shape))).
   (->> shape :noise (apply-filters :type :noise))

   (->> shape :blur list (apply-filters :type :layer-blur))

   ;; STACKED-BLUR (#60) — :blurs is a VECTOR slot; each non-hidden entry
   ;; contributes one radius (its :value) to a single :stacked-blur filter
   ;; entry whose :params carries :radii. Absent/empty/all-hidden :blurs ->
   ;; no entry -> filters vector stays at baseline count of 2 -> filters*
   ;; count-guard false -> no <filter> (byte-identical).
   (when (seq (remove :hidden (:blurs shape)))
     (let [items (remove :hidden (:blurs shape))
           radii (vec (keep :value items))]
       (when (seq radii)
         [{:id "filter_stacked-blur"
           :type :stacked-blur
           :params {:radii radii}}])))

   ;; GLASS (#61) — single-map slot appended after stacked-blur.
   ;; apply-filters does (remove :hidden) internally, so an
   ;; absent/nil/hidden slot yields an empty seq and NO entry is added
   ;; -> filters vector stays at baseline count of 2 -> filters*
   ;; count-guard false -> no <filter> (byte-identical).
   (->> shape :glass list (apply-filters :type :glass))

   ;; SHADER (#64) — :shader-effect is a VECTOR slot; index 0 (first
   ;; non-hidden item) is the active shader-effect map. SVG-expressible
   ;; presets (:clouds / :halftone / :noise) get a first-class SVG filter
   ;; entry appended after glass; arbitrary/prompt-built presets are
   ;; rendered by the WebGL2 canvas path (shader-canvas* in filters.cljs)
   ;; and deliberately do NOT enter the SVG filter chain. The entry is
   ;; appended ONLY when the first non-hidden item's :shader-preset is in
   ;; the SVG-expressible set (inlined here because bounds.cljc is in
   ;; app.common.* and cannot require filters.cljs); :shader-preset is
   ;; aliased to :preset in :params (shader-preset-filter* reads
   ;; :preset). Absent/empty/all-hidden/non-SVG slot -> no entry ->
   ;; filters vector stays at baseline count of 2 -> filters* count-guard
   ;; false -> no <filter> element (byte-identical).
   (when (seq (:shader-effect shape))
     (let [item (first (remove :hidden (:shader-effect shape)))
           preset (:shader-preset item)]
       (when (contains? #{:clouds :halftone :noise} preset)
         [{:id (dm/str "filter_" (or (:id item) "shader"))
           :type :shader-effect
           :params (assoc item :preset preset)}])))))

(defn- calculate-filter-bounds
  [selrect filter-entry]
  (let [x (dm/get-prop selrect :x)
        y (dm/get-prop selrect :y)
        w (dm/get-prop selrect :width)
        h (dm/get-prop selrect :height)]
    (cond
      (= :texture (:type filter-entry))
      ;; feDisplacementMap scale `radius` grows the filter region
      ;; symmetrically around the shape. Absent slot -> no :texture
      ;; entry -> this branch is never reached (byte-identical).
      (let [radius   (or (-> filter-entry :params :radius) 0)
            filter-x (- x radius 5)
            filter-y (- y radius 5)
            filter-w (+ w (* radius 2) 10)
            filter-h (+ h (* radius 2) 10)]
        (grc/make-rect filter-x filter-y filter-w filter-h))

      (= :stacked-blur (:type filter-entry))
      ;; N chained Gaussian blurs; the largest radius dominates the
      ;; filter-region growth. Absent slot -> no :stacked-blur entry
      ;; -> never reached (byte-identical).
      (let [radii    (or (seq (-> filter-entry :params :radii)) [0])
            grow     (apply max radii)
            filter-x (- x grow 5)
            filter-y (- y grow 5)
            filter-w (+ w (* grow 2) 10)
            filter-h (+ h (* grow 2) 10)]
        (grc/make-rect filter-x filter-y filter-w filter-h))

      (= :glass (:type filter-entry))
      ;; Glass grows the region by the max of refraction scale, frost
      ;; blur and dispersion offset. Absent slot -> no :glass entry
      ;; -> never reached (byte-identical).
      (let [grow     (max (or (-> filter-entry :params :refraction) 0)
                          (or (-> filter-entry :params :frost-blur) 0)
                          (or (-> filter-entry :params :dispersion) 0))
            filter-x (- x grow 5)
            filter-y (- y grow 5)
            filter-w (+ w (* grow 2) 10)
            filter-h (+ h (* grow 2) 10)]
        (grc/make-rect filter-x filter-y filter-w filter-h))

      :else
      (let [{:keys [offset-x offset-y blur spread]
             :or {offset-x 0 offset-y 0 blur 0 spread 0}}
            (:params filter-entry)

            filter-x (mth/min x (+ x offset-x (- spread) (- blur) -5))
            filter-y (mth/min y (+ y offset-y (- spread) (- blur) -5))
            filter-w (+ w (mth/abs offset-x) (* spread 2) (* blur 2) 10)
            filter-h (+ h (mth/abs offset-y) (* spread 2) (* blur 2) 10)]

        (grc/make-rect filter-x filter-y filter-w filter-h)))))

(defn get-rect-filter-bounds
  ([selrect filters blur-value]
   (get-rect-filter-bounds selrect filters blur-value false))
  ([selrect filters blur-value ignore-shadow-margin?]
   (let [bounds-xf  (comp
                     (filter #(and (not ignore-shadow-margin?)
                                   (#{:drop-shadow :texture :stacked-blur :glass} (:type %))))
                     (map (partial calculate-filter-bounds selrect)))
         delta-blur (* blur-value 2)]
     (-> (into [selrect] bounds-xf filters)
         (grc/join-rects)
         (update :x - delta-blur)
         (update :y - delta-blur)
         (update :x1 - delta-blur)
         (update :y1 - delta-blur)
         (update :x2 + delta-blur)
         (update :y2 + delta-blur)
         (update :width + (* delta-blur 2))
         (update :height + (* delta-blur 2))))))

(defn get-shape-filter-bounds
  ([shape]
   (get-shape-filter-bounds shape false))
  ([shape ignore-shadow-margin?]
   (cond
     ;; SVG raw elements (non-root) don't have proper rotated points; use selrect
     (and (cfh/svg-raw-shape? shape)
          (not= :svg (dm/get-in shape [:content :tag])))
     (dm/get-prop shape :selrect)

     ;; No shadows or blur: use the axis-aligned bounding box from the actual
     ;; (possibly rotated) points. Using selrect here would be wrong for rotated
     ;; shapes because selrect stores the unrotated rectangle, not the screen-space bbox.
     (and (empty? (-> shape :shadow))
          (or (nil? (:blur shape))
              (zero? (-> shape :blur :value (or 0)))))
     (-> (dm/get-prop shape :points)
         (grc/points->rect))

     :else
     (let [filters    (shape->filters shape)
           blur-value (or (-> shape :blur :value) 0)
           srect      (-> (dm/get-prop shape :points)
                          (grc/points->rect))]
       (get-rect-filter-bounds srect filters blur-value ignore-shadow-margin?)))))

(def ^:private stroke-margin-multiplier 4.25)

(defn- stroke-cap-marker-margin
  [strokes open-path?]
  (if open-path?
    (->> strokes
         (filter (fn [s]
                   (or (cts/stroke-caps-marker (:stroke-cap-start s))
                       (cts/stroke-caps-marker (:stroke-cap-end s)))))
         (map #(* stroke-margin-multiplier (:stroke-width % 0)))
         (reduce d/max 0))
    0))

(defn calculate-padding
  ([shape]
   (calculate-padding shape false false))
  ([shape ignore-margin? ignore-shadow-margin?]
   (let [strokes (:strokes shape)

         open-path?    (and ^boolean (cfh/path-shape? shape)
                            ^boolean (path/shape-with-open-path? shape))

         stroke-width
         (->> strokes
              (map #(case (get % :stroke-alignment :center)
                      :center (/ (:stroke-width % 0) 2)
                      :outer  (:stroke-width % 0)
                      (if open-path? (:stroke-width % 0) 0)))
              (reduce d/max 0))

         stroke-margin
         (if ignore-margin?
           0
           (shape-stroke-margin shape stroke-width))

         stroke-cap-margin
         (if ignore-margin?
           0
           (stroke-cap-marker-margin strokes open-path?))

         shadow-width
         (->> (:shadow shape)
              (remove :hidden)
              (map #(case (:style % :drop-shadow)
                      :drop-shadow (+ (mth/abs (:offset-x %)) (* (:spread %) 2) (* (:blur %) 2) 10)
                      0))
              (reduce d/max 0))

         shadow-height
         (->> (:shadow shape)
              (remove :hidden)
              (map #(case (:style % :drop-shadow)
                      :drop-shadow (+ (mth/abs (:offset-y %)) (* (:spread %) 2) (* (:blur %) 2) 10)
                      0))
              (reduce d/max 0))

         shadow-height
         (if ignore-shadow-margin? 0 shadow-height)

         shadow-width
         (if ignore-shadow-margin? 0 shadow-width)]

     {:horizontal (mth/ceil (+ stroke-margin stroke-cap-margin shadow-width))
      :vertical (mth/ceil (+ stroke-margin stroke-cap-margin shadow-height))})))

(defn- add-padding
  [bounds padding]
  (let [h-padding (:horizontal padding)
        v-padding (:vertical padding)]
    (-> bounds
        (update :x - h-padding)
        (update :x1 - h-padding)
        (update :x2 + h-padding)
        (update :y - v-padding)
        (update :y1 - v-padding)
        (update :y2 + v-padding)
        (update :width + (* 2 h-padding))
        (update :height + (* 2 v-padding)))))

(defn calculate-base-bounds
  ([shape]
   (calculate-base-bounds shape true false))
  ([shape ignore-margin? ignore-shadow-margin?]
   (-> (get-shape-filter-bounds shape ignore-shadow-margin?)
       (add-padding (calculate-padding shape ignore-margin? ignore-shadow-margin?)))))

(defn get-object-bounds
  ([objects shape]
   (get-object-bounds objects shape nil))
  ([objects shape {:keys [ignore-margin? ignore-shadow-margin?]
                   :or {ignore-margin? true ignore-shadow-margin? false}}]
   (let [base-bounds (calculate-base-bounds shape ignore-margin? ignore-shadow-margin?)
         bounds
         (cond
           (or (empty? (:shapes shape))
               (:masked-group shape)
               (cfh/bool-shape? shape)
               (and (cfh/frame-shape? shape)
                    (not (:show-content shape))))
           [base-bounds]

           :else
           (cfh/reduce-objects
            objects

            (fn [shape]
              (and (not (:hidden shape))
                   (d/not-empty? (:shapes shape))
                   (or (not (cfh/frame-shape? shape))
                       (:show-content shape))

                   (or (not (cfh/group-shape? shape))
                       (not (:masked-group shape)))))
            (:id shape)

            (fn [result child]
              (cond-> result
                (not (:hidden child))
                (conj (calculate-base-bounds child))))

            [base-bounds]))

         children-bounds
         (cond->> (grc/join-rects bounds)
           (not (cfh/frame-shape? shape)) (or (:children-bounds shape)))

         filters (shape->filters shape)
         blur-value (or (-> shape :blur :value) 0)]

     (get-rect-filter-bounds children-bounds filters blur-value ignore-shadow-margin?))))

(defn get-frame-bounds
  ([shape]
   (get-frame-bounds shape nil))
  ([shape {:keys [ignore-margin? ignore-shadow-margin?] :or {ignore-margin? false ignore-shadow-margin? false}}]
   (get-object-bounds [] shape {:ignore-margin? ignore-margin?
                                :ignore-shadow-margin? ignore-shadow-margin?})))
