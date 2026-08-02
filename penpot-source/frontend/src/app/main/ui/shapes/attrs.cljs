;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.shapes.attrs
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.geom.shapes :as gsh]
   [app.common.json :as json]
   [app.common.svg :as csvg]
   [app.common.types.color :as clr]
   [app.common.types.shape :refer [stroke-caps-line stroke-caps-marker]]
   [app.common.types.shape.radius :as ctsr]
   [app.util.object :as obj]
   [clojure.string :as cstr]
   [cuerdas.core :as str]))

(defn- calculate-dasharray
  [style width dash gap]
  (let [w+5  (+ 5 width)
        w+1  (+ 1 width)
        w+10 (+ 10 width)]
    (case style
      :mixed  (str/concat "" w+5 "," w+5 "," w+1 "," w+5)
      :dotted (str/concat "0," w+5)
      :dashed (str/concat "" (or dash w+10) "," (or gap w+10))
      "")))

(def ^:private corner-smoothing-steps 16)

(defn- smooth-rect-path
  "SVG path 'd' for a rounded rectangle whose corners are superellipse
  curves (Figma-style 'corner smoothing').

  `n` is the superellipse exponent: n=2 reproduces a circular arc, so
  smoothing=0 is pixel-identical to the un-smoothed renderer; larger n
  flattens the corner toward the rectangle (stronger smoothing).

  Each corner is sampled at P(t) = C + a^(2/n)·(from-C) + b^(2/n)·(to-C)
  with t in [0,1] (a=cos(t·π/2), b=sin(t·π/2)) — the exact superellipse
  quarter between the two edge tangent points, bulging toward the
  rectangle corner. The radii passed in are already overlap-adjusted by
  `gsh/shape-corners-4`, so corners never overshoot the box. This is a
  piecewise-linear approximation of the true superellipse; 16 samples per
  corner is visually smooth at typical canvas zoom and converges to the
  exact curve as the step count grows."
  [x y w h r1 r2 r3 r4 n]
  (let [inv (/ 2.0 n)
        pi2 (/ js/Math.PI 2)
        corner
        (fn [cx cy fx fy tx ty]
          (map (fn [i]
                 (let [t   (/ i corner-smoothing-steps)
                       ang (* t pi2)
                       a   (js/Math.pow (js/Math.cos ang) inv)
                       b   (js/Math.pow (js/Math.sin ang) inv)
                       px  (+ cx (* a (- fx cx)) (* b (- tx cx)))
                       py  (+ cy (* a (- fy cy)) (* b (- ty cy)))]
                   (cstr/join " " ["L" px py])))
               (range 1 (inc corner-smoothing-steps))))]
    (cstr/join
     " "
     (concat
      [(cstr/join " " ["M" (+ x r1) y])
       (cstr/join " " ["L" (- (+ x w) r2) y])]
      ;; TR: center (x+w-r2, y+r2), from top (x+w-r2, y) -> to right (x+w, y+r2)
      (corner (- (+ x w) r2) (+ y r2)
              (- (+ x w) r2) y
              (+ x w) (+ y r2))
      [(cstr/join " " ["L" (+ x w) (- (+ y h) r3)])]
      ;; BR: center (x+w-r3, y+h-r3), from right (x+w, y+h-r3) -> to bottom (x+w-r3, y+h)
      (corner (- (+ x w) r3) (- (+ y h) r3)
              (+ x w) (- (+ y h) r3)
              (- (+ x w) r3) (+ y h))
      [(cstr/join " " ["L" (+ x r4) (+ y h)])]
      ;; BL: center (x+r4, y+h-r4), from bottom (x+r4, y+h) -> to left (x, y+h-r4)
      (corner (+ x r4) (- (+ y h) r4)
              (+ x r4) (+ y h)
              x (- (+ y h) r4))
      [(cstr/join " " ["L" x (+ y r1)])]
      ;; TL: center (x+r1, y+r1), from left (x, y+r1) -> to top (x+r1, y)
      (corner (+ x r1) (+ y r1)
              x (+ y r1)
              (+ x r1) y)
      ["Z"]))))

(defn get-border-props
  [shape]
  (let [smoothing (dm/get-prop shape :corner-smoothing)]
    (if (and (pos? smoothing) (ctsr/has-radius? shape))
      ;; Corner smoothing (superellipse). Figma exposes smoothing as a
      ;; whole-shape property (no per-corner smoothing), but the
      ;; superellipse naturally accepts per-corner radii, so we honor
      ;; r1..r4. n=2 reproduces the circular arc; n grows with smoothing.
      (let [[r1 r2 r3 r4] (gsh/shape-corners-4 shape)
            n    (+ 2.0 (* 8.0 (min 1.0 (max 0.0 smoothing))))
            x    (dm/get-prop shape :x)
            y    (dm/get-prop shape :y)
            w    (dm/get-prop shape :width)
            h    (dm/get-prop shape :height)]
        #js {:d (smooth-rect-path x y w h r1 r2 r3 r4 n)})
      (case (ctsr/radius-mode shape)
        :radius-1
        (let [radius (gsh/shape-corners-1 shape)]
          #js {:rx radius :ry radius})

        :radius-4
        (let [[r1 r2 r3 r4] (gsh/shape-corners-4 shape)
              x      (dm/get-prop shape :x)
              y      (dm/get-prop shape :y)
              width  (dm/get-prop shape :width)
              height (dm/get-prop shape :height)
              top    (- width r1 r2)
              right  (- height r2 r3)
              bottom (- width r3 r4)
              left   (- height r4 r1)]
          #js {:d (dm/str
                   "M" (+ x r1) "," y " "
                   "h" top " "
                   "a" r2 "," r2 " 0 0 1 " r2 "," r2 " "
                   "v" right " "
                   "a" r3 "," r3 " 0 0 1 " (- r3) "," r3 " "
                   "h" (- bottom) " "
                   "a" r4 "," r4 " 0 0 1 " (- r4) "," (- r4) " "
                   "v" (- left) " "
                   "a" r1 "," r1 " 0 0 1 " r1 "," (- r1) " "
                   "z")}))))

(defn add-border-props!
  [props shape]
  (obj/merge! props (get-border-props shape)))

(defn add-fill!
  ([attrs fill-data render-id index type]
   (add-fill! attrs fill-data render-id index type "none"))
  ([attrs fill-data render-id index type fill-default]
   (let [index (if (some? index) (dm/str "-" index) "")]
     (cond
       (contains? fill-data :fill-image)
       (let [id (dm/str "fill-image-" render-id)]
         (obj/set! attrs "fill" (dm/str "url(#" id ")")))

       (some? (:fill-color-gradient fill-data))
       (let [id (dm/str "fill-color-gradient-" render-id index)]
         (obj/set! attrs "fill" (dm/str "url(#" id ")")))

       (contains? fill-data :fill-color)
       (obj/set! attrs "fill" (:fill-color fill-data))

       :else
       (obj/set! attrs "fill" fill-default))

     (when (contains? fill-data :fill-opacity)
       (obj/set! attrs "fillOpacity" (:fill-opacity fill-data)))

     (when (and (= :text type)
                (nil? (:fill-color-gradient fill-data))
                (nil? (:fill-color fill-data)))
       (obj/set! attrs "fill" "black"))

     attrs)))

(defn add-stroke!
  [attrs data render-id index open-path?]
  (let [style (:stroke-style data :solid)]
    (when-not (= style :none)
      (let [width       (:stroke-width data 1)
            dash        (:stroke-dash data)
            gap         (:stroke-gap data)
            gradient    (:stroke-color-gradient data)
            color       (:stroke-color data)
            opacity     (:stroke-opacity data)]

        (obj/set! attrs "strokeWidth" width)

        (if (some? gradient)
          (let [gradient-id (dm/str "stroke-color-gradient-" render-id "-" index)]
            (obj/set! attrs "stroke" (str/ffmt "url(#%)" gradient-id)))

          (when (some? color)
            (obj/set! attrs "stroke" color)))

        (when (some? opacity)
          (obj/set! attrs "strokeOpacity" opacity))

        (when (not= style :svg)
          (obj/set! attrs "strokeDasharray" (calculate-dasharray style width dash gap)))

        ;; Figma-parity: stroke join + miter limit. These are pure SVG
        ;; attributes that apply to the path's own stroke regardless of
        ;; stroke alignment (inner/outer render via <use> which inherits
        ;; these style keys, so joins render correctly there too). Unset =
        ;; SVG defaults (:miter, 4) which match Figma, so legacy strokes
        ;; render pixel-identically.
        (when-let [join (:stroke-join data)]
          (obj/set! attrs "strokeLinejoin" (name join)))
        (when-let [miter (:stroke-miter-limit data)]
          (obj/set! attrs "strokeMiterlimit" miter))

        ;; For simple line caps we use svg stroke-line-cap attribute. This
        ;; only works if all caps are the same and we are not using the tricks
        ;; for inner or outer strokes.
        (let [caps-start (:stroke-cap-start data)
              caps-end   (:stroke-cap-end data)
              alignment  (:stroke-alignment data)]
          (cond
            (and (contains? stroke-caps-line caps-start)
                 (= caps-start caps-end)
                 (or open-path?
                     (and (not= :inner alignment)
                          (not= :outer alignment)))
                 (not= :dotted style))
            (obj/set! attrs "strokeLinecap" (name caps-start))

            (= :dotted style)
            (obj/set! attrs "strokeLinecap" "round"))

          (when (or open-path?
                    (and (not= :inner alignment)
                         (not= :outer alignment)))

            ;; For other cap types we use markers.
            (when (or (contains? stroke-caps-marker caps-start)
                      (and (contains? stroke-caps-line caps-start)
                           (not= caps-start caps-end)))
              (obj/set! attrs "markerStart" (str/ffmt "url(#marker-%-%)" render-id (name caps-start))))

            (when (or (contains? stroke-caps-marker caps-end)
                      (and (contains? stroke-caps-line caps-end)
                           (not= caps-start caps-end)))
              (obj/set! attrs "markerEnd" (str/ffmt "url(#marker-%-%)" render-id (name caps-end))))))))

    attrs))

(defn get-svg-props
  [shape render-id]
  (let [attrs (get shape :svg-attrs {})
        defs  (get shape :svg-defs {})]

    (if (and (empty? defs)
             (empty? attrs))
      #js {}
      (-> attrs
          (csvg/update-attr-ids
           (fn [id]
             (if (contains? defs id)
               (dm/str render-id "-" id)
               id)))
          (dissoc :id)
          (json/->js :key-fn name)))))

(defn get-fill-style
  ([fill-data index render-id type]
   (add-fill! #js {} fill-data render-id index type))
  ([fill-data index render-id type fill-default]
   (add-fill! #js {} fill-data render-id index type fill-default)))

(defn add-fill-props!
  ([props shape render-id]
   (add-fill-props! props shape 0 render-id))

  ([props shape position render-id]
   (let [shape-fills  (get shape :fills)
         shape-shadow (get shape :shadow)

         shape-blur   (get shape :blur)

         svg-attrs    (get-svg-props shape render-id)
         svg-styles   (obj/get svg-attrs "style")

         shape-type   (dm/get-prop shape :type)

         style        (-> (obj/get props "style")
                          (obj/clone)
                          (obj/merge! svg-styles))

         url-fill?    (or ^boolean (some? (:fill-image shape))
                          ^boolean (cfh/image-shape? shape)
                          ^boolean (> (count shape-fills) 1)
                          ^boolean (some? (some :fill-color-gradient shape-fills))
                          ^boolean (some? (some :fill-image shape-fills)))

         props        (if (cfh/frame-shape? shape)
                        props
                        (if (or (some? (->> shape-shadow (remove :hidden) seq))
                                (and (some? shape-blur) (not ^boolean (:hidden shape-blur))))
                          (obj/set! props "filter" (dm/fmt "url(#filter-%)" render-id))
                          props))]

     (cond
       ;; If the shape comes from an imported SVG (we know because
       ;; it has the :svg-attrs atribute), and it does not have an
       ;; own fill, we set a default black fill. This will be
       ;; inherited by child nodes, and is for emulating the
       ;; behavior of standard SVG, in that a node that has no
       ;; explicit fill has a default fill of black. This may be
       ;; reset to normal if a Penpot frame shape appears below
       ;; (see main.ui.shapes.frame/frame-container).
       (and ^boolean (contains? shape :svg-attrs)
            ^boolean (or ^boolean (= :svg-raw shape-type)
                         ^boolean (= :group shape-type))
            ^boolean (empty? shape-fills))
       (let [wstyle (get shape :wrapper-styles)
             fill   (obj/get wstyle "fill")
             fill   (d/nilv fill clr/black)]
         (obj/set! style "fill" fill))

       ^boolean url-fill?
       (do
         (obj/unset! style "fill")
         (obj/unset! style "fillOpacity")
         (obj/set! props "fill" (dm/fmt "url(#fill-%-%)" position render-id)))

       (and ^boolean (some? svg-styles)
            ^boolean (obj/contains? svg-styles "fill"))
       (let [fill    (obj/get svg-styles "fill")
             opacity (obj/get svg-styles "fillOpacity")]
         (when (some? fill)
           (obj/set! style "fill" fill))
         (when (some? opacity)
           (obj/set! style "fillOpacity" opacity)))

       (and ^boolean (some? svg-attrs)
            ^boolean (empty? shape-fills))
       (let [fill    (obj/get svg-attrs "fill")
             opacity (obj/get svg-attrs "fillOpacity")]
         (when (some? fill)
           (obj/set! style "fill" fill))
         (when (some? opacity)
           (obj/set! style "fillOpacity" opacity)))

       ^boolean (d/not-empty? shape-fills)
       (let [fill (nth shape-fills 0)
             svg-fill (obj/get svg-attrs "fill")
             fill-default (d/nilv svg-fill "none")]
         (obj/merge! style (get-fill-style fill render-id 0 shape-type fill-default)))

       (and ^boolean (cfh/path-shape? shape)
            ^boolean (empty? shape-fills))
       (obj/set! style "fill" "none"))

     (-> props
         (obj/merge! svg-attrs)
         (obj/set! "style" style)))))
