;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.shapes.polygon
  "Figma-parity polygon + star renderers (gap #58).

  Both shapes are rect-shaped (x/y/width/height) and render as an SVG
  `<path>` whose `d` is a closed regular-polygon / star outline built by
  app.common.types.shape.polygon. Fills/strokes reuse the generic
  shape-custom-strokes wrapper, so they behave exactly like a rect."
  (:require
   [app.common.data.macros :as dm]
   [app.common.geom.shapes :as gsh]
   [app.common.types.shape.polygon :as ctsp]
   [app.main.ui.shapes.custom-stroke :refer [shape-custom-strokes]]
   [app.util.object :as obj]
   [rumext.v2 :as mf]))

(mf/defc polygon-shape
  {::mf/wrap-props false}
  [props]
  (let [shape      (unchecked-get props "shape")

        x          (dm/get-prop shape :x)
        y          (dm/get-prop shape :y)
        w          (dm/get-prop shape :width)
        h          (dm/get-prop shape :height)

        ;; A polygon is inscribed in the bounding circle of the rect.
        cx         (+ x (/ w 2))
        cy         (+ y (/ h 2))
        r          (/ (min w h) 2)

        point-count (dm/get-prop shape :point-count)
        corner      (dm/get-prop shape :corner-radius)

        t          (gsh/transform-str shape)

        d          (ctsp/polygon-svg-path cx cy r (or point-count 5) (or corner 0))

        path-props (mf/with-memo [shape]
                     (-> #js {}
                         (obj/merge! #js {:d d :transform t})))]

    [:& shape-custom-strokes
     {:shape shape}
     [:> :path path-props]]))

(mf/defc star-shape
  {::mf/wrap-props false}
  [props]
  (let [shape      (unchecked-get props "shape")

        x          (dm/get-prop shape :x)
        y          (dm/get-prop shape :y)
        w          (dm/get-prop shape :width)
        h          (dm/get-prop shape :height)

        cx         (+ x (/ w 2))
        cy         (+ y (/ h 2))
        r          (/ (min w h) 2)

        point-count (dm/get-prop shape :point-count)
        inner-r     (dm/get-prop shape :inner-radius)
        corner      (dm/get-prop shape :corner-radius)

        t          (gsh/transform-str shape)

        d          (ctsp/star-svg-path cx cy r
                                       (or point-count 5)
                                       (or inner-r 0.5)
                                       (or corner 0))

        path-props (mf/with-memo [shape]
                     (-> #js {}
                         (obj/merge! #js {:d d :transform t})))]

    [:& shape-custom-strokes
     {:shape shape}
     [:> :path path-props]]))