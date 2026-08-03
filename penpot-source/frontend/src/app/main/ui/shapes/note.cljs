;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.shapes.note
  "Figma-parity sticky-note renderer (gap #44).

  A sticky note is a colored rectangle carrying freeform text. We render
  it as an SVG `<rect>` (the colored sticky body, using the note's own
  fill so it behaves like any other fillable shape) plus an SVG `<text>`
  element with the note body. The text is rendered as a single anchored
  block (no rich wrapping) to keep the render additive + low-risk; full
  wrapped multi-line text is a deferred polish item. Existing fills on
  the shape still apply via shape-custom-strokes so the note can be
  re-colored like any shape; the dedicated :note-color is a fallback used
  only when the shape has no fills."
  (:require
   [app.common.data.macros :as dm]
   [app.common.geom.shapes :as gsh]
   [app.main.ui.shapes.custom-stroke :refer [shape-custom-strokes]]
   [app.util.object :as obj]
   [rumext.v2 :as mf]))

(mf/defc note-shape
  {::mf/wrap-props false}
  [props]
  (let [shape (unchecked-get props "shape")

        x    (dm/get-prop shape :x)
        y    (dm/get-prop shape :y)
        w    (dm/get-prop shape :width)
        h    (dm/get-prop shape :height)

        t    (gsh/transform-str shape)

        text (or (dm/get-prop shape :note-text) "")
        ;; Body text color; default dark gray for legibility on yellow.
        tcolor (or (dm/get-prop shape :note-text-color) "#333333")

        rect-props (mf/with-memo [shape]
                     (-> #js {}
                         (obj/merge! #js {:x x :y y :width w :height h
                                          :transform t})))

        ;; Anchor text near the top-left inset of the note. font-size is
        ;; a fixed 14px for the v1 sticky; per-note font sizing is
        ;; deferred. Only render the <text> node when there is body text
        ;; so empty notes render byte-identical to a plain rect.
        tx       (+ x 8)
        ty       (+ y 18)]

    [:*
     [:& shape-custom-strokes {:shape shape}
      [:> :rect rect-props]]
     (when (pos? (count text))
       [:> :text
        #js {:x tx :y ty :transform t
             :fill tcolor :font-size 14
             :font-family "sans-serif"}
        text])]))