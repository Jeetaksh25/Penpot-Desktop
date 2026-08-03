;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.shapes.circle
  (:require
   [app.common.data.macros :as dm]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.main.ui.shapes.custom-stroke :refer [shape-custom-strokes]]
   [app.util.object :as obj]
   [rumext.v2 :as mf]))

;; Figma-parity arc / pie / ring / donut (gap #59). When :arc-start and
;; :arc-end are both present on the circle shape, the renderer emits an
;; SVG `<path>` (arc / pie / ring) instead of `<ellipse>`. When either
;; angle is absent the shape renders byte-identically to today's
;; `<ellipse>`. Angles are in degrees, 0 = +x axis, clockwise (matching
;; SVG's y-down coordinate system, so the sweep flag is 1).
(defn- arc-point [cx cy rx ry deg]
  (let [rad (/ (* deg mth/PI) 180.0)]
    [(+ cx (* rx (mth/cos rad)))
     (+ cy (* ry (mth/sin rad)))]))

(defn- arc-path
  "Build the SVG path 'd' for an arc / pie / ring on the ellipse (cx, cy,
  rx, ry) between `start-deg` and `end-deg` (degrees, clockwise). When
  `inner-ratio` (0..1) is non-nil and < 1, a ring/donut is produced using
  a second concentric arc of radius `inner-ratio` of the outer radius;
  otherwise a pie slice (lines to the center) is produced. A full circle
  (start == end mod 360) still renders as an arc path so callers that
  want a plain ellipse should simply omit the arc fields."
  [cx cy rx ry start-deg end-deg inner-ratio]
  (let [start-norm (mod start-deg 360.0)
        end-norm   (mod end-deg 360.0)
        ;; Clockwise sweep; large-arc if the swept angle exceeds 180.
        sweep     (mod (- end-norm start-norm) 360.0)
        large     (if (> sweep 180.0) 1 0)
        [sx sy]   (arc-point cx cy rx ry start-norm)
        [ex ey]   (arc-point cx cy rx ry end-norm)]
    (if (or (nil? inner-ratio) (>= inner-ratio 1))
      ;; Pie slice: move to center, line to start, arc to end, close.
      ;; inner-ratio nil OR >= 1 degrades to a pie (a ratio of 1 would
      ;; make the inner arc coincide with the outer arc, enclosing zero
      ;; area — see gap #59; the measures control placeholder is "1").
      (dm/fmt "M%,%L%,%A%,% 0 % 1 %,%Z"
              cx cy sx sy rx ry large ex ey)
      ;; Ring / donut: outer arc, then line to inner end, inner arc back
      ;; (reverse sweep), then close to outer start.
      (let [irx       (* rx inner-ratio)
            iry       (* ry inner-ratio)
            [isx isy] (arc-point cx cy irx iry start-norm)
            [iex iey] (arc-point cx cy irx iry end-norm)]
        (dm/fmt "M%,%A%,% 0 % 1 %,%L%,%A%,% 0 % 0 %,%Z"
                sx sy rx ry large ex ey
                iex iey irx iry large isx isy)))))

(mf/defc circle-shape
  {::mf/wrap-props false}
  [props]
  (let [shape (unchecked-get props "shape")

        x     (dm/get-prop shape :x)
        y     (dm/get-prop shape :y)
        w     (dm/get-prop shape :width)
        h     (dm/get-prop shape :height)

        t     (gsh/transform-str shape)

        cx    (+ x (/ w 2))
        cy    (+ y (/ h 2))
        rx    (/ w 2)
        ry    (/ h 2)

        ;; Figma-parity arc fields (optional; absent = plain ellipse).
        arc-start   (dm/get-prop shape :arc-start)
        arc-end     (dm/get-prop shape :arc-end)
        inner-ratio (dm/get-prop shape :inner-radius)
        arc?        (and (some? arc-start) (some? arc-end))

        props (mf/with-memo [shape]
                (if arc?
                  (let [d (arc-path cx cy rx ry arc-start arc-end inner-ratio)]
                    (-> #js {}
                        (obj/merge! #js {:d d :transform t})))
                  (-> #js {}
                      (obj/merge! #js {:cx cx :cy cy :rx rx :ry ry :transform t}))))]

    [:& shape-custom-strokes {:shape shape}
     (if arc?
       [:> :path props]
       [:> :ellipse props])]))
