;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.shapes.rect
  (:require
   [app.common.data.macros :as dm]
   [app.common.geom.shapes :as gsh]
   [app.main.data.workspace.video :as dwv]
   [app.main.ui.shapes.attrs :as attrs]
   [app.main.ui.shapes.custom-stroke :refer [shape-custom-strokes]]
   [app.main.ui.shapes.video :as video]
   [app.util.object :as obj]
   [rumext.v2 :as mf]))

(mf/defc rect-shape
  {::mf/wrap-props false}
  [props]
  (let [shape (unchecked-get props "shape")

        x     (dm/get-prop shape :x)
        y     (dm/get-prop shape :y)
        w     (dm/get-prop shape :width)
        h     (dm/get-prop shape :height)

        t     (gsh/transform-str shape)

        props (mf/with-memo [shape]
                (-> #js {}
                    (attrs/add-border-props! shape)
                    (obj/merge! #js {:x x :y y :transform t :width w :height h})))

        path? (some? (.-d props))

        ;; P2.39: video/GIF playback slot. When the carrier rect carries the
        ;; `:ovion "video"` plugin-data slot we render a <foreignObject>
        ;; wrapping <video>/<img> on top of the rect (see ui.shapes.video).
        ;; Absent slot → video-config is nil → the fragment below degenerates
        ;; to the exact pre-existing `[:& shape-custom-strokes …]` form, so the
        ;; SVG is byte-identical to today for every non-video rect.
        video-cfg (dwv/read-video-slot shape)]

    (if (nil? video-cfg)
      [:& shape-custom-strokes {:shape shape}
       (if path?
         [:> :path props]
         [:> :rect props])]
      [:*
       [:& shape-custom-strokes {:shape shape}
        (if path?
          [:> :path props]
          [:> :rect props])]
       [:> video/video-foreign-object* {:shape shape}]])))
