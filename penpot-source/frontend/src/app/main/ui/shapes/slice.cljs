;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.shapes.slice
  "Renderer for the Figma-parity slice tool. A slice is a rect-shaped
  export region: it renders NO authoring content of its own (empty
  fills/strokes), only a translucent dashed overlay so the user can
  see, select, move and resize the region on the canvas. The overlay
  is part of the shape's own SVG so it shows on the workspace canvas."
  (:require
   [app.common.data.macros :as dm]
   [app.common.geom.shapes :as gsh]
   [rumext.v2 :as mf]))

(mf/defc slice-shape
  {::mf/wrap-props false}
  [props]
  (let [shape (unchecked-get props "shape")
        x     (dm/get-prop shape :x)
        y     (dm/get-prop shape :y)
        w     (dm/get-prop shape :width)
        h     (dm/get-prop shape :height)
        t     (gsh/transform-str shape)]
    ;; Figma-style slice overlay: a translucent green fill with a dashed
    ;; green border. Fixed style (ignores the shape's empty fills/strokes).
    ;; 8/4 dash, 1px stroke in the shape's local space.
    [:> :rect #js {:x x
                  :y y
                  :width w
                  :height h
                  :transform t
                  :fill "rgba(76, 192, 138, 0.12)"
                  :stroke "#4cc08a"
                  :stroke-width 1
                  :stroke-dasharray "8 4"
                  :pointer-events "all"}]))