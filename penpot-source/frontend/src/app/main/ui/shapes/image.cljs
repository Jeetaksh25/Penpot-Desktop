;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.shapes.image
  (:require
   [app.common.data.macros :as dm]
   [app.common.geom.shapes :as gsh]
   [app.main.ui.context :as muc]
   [app.main.ui.shapes.attrs :as attrs]
   [app.main.ui.shapes.custom-stroke :refer [shape-custom-strokes]]
   [app.util.object :as obj]
   [rumext.v2 :as mf]))

(mf/defc image-shape
  {::mf/wrap-props false}
  [props]

  (let [shape     (unchecked-get props "shape")

        x         (dm/get-prop shape :x)
        y         (dm/get-prop shape :y)
        w         (dm/get-prop shape :width)
        h         (dm/get-prop shape :height)

        render-id (mf/use-ctx muc/render-id)
        transform (gsh/transform-str shape)

        ;; Figma-parity wireframe render mode (ALL_APPS_PARITY P2.26). When
        ;; the workspace viewport is in wireframe mode, the raster image is
        ;; hidden by the `.wireframe-mode image { visibility: hidden }` CSS
        ;; rule and the rect/path fill is forced to neutral gray, so the
        ;; shape reads as a plain gray box. To keep image placeholders
        ;; RECOGNIZABLE (vs. any other gray box) we overlay a centered
        ;; Lucide "image" glyph. The context defaults false and only the
        ;; workspace viewport mounts the provider, so non-workspace renders
        ;; (thumbnails, exports, viewer) emit zero overlay -> byte-identical
        ;; with today. No reduced-motion concern (no motion introduced).
        wireframe? (mf/use-ctx muc/wireframe-mode?)

        props     (mf/with-memo [shape render-id]
                    (-> #js {}
                        (attrs/add-fill-props! shape render-id)
                        (attrs/add-border-props! shape)
                        (obj/merge! #js {:x x :y y :width w :height h :transform transform})))

        path?     (some? (.-d props))

        ;; Center + size of the image-icon overlay, in the shape's local
        ;; (pre-transform) coordinate space — the overlay <g> carries the
        ;; same `transform` as the rect so it lands exactly on the image.
        cx         (+ x (/ w 2))
        cy         (+ y (/ h 2))
        ;; icon-size is capped at min(w,h) and 64 — clamped to the SMALLER
        ;; image dimension so the glyph always fits inside the gray box and
        ;; never overflows a tiny placeholder (a floor like `max 12 ...`
        ;; would push the icon past a <12px image edge). 64 is the legibility
        ;; ceiling for large images. Guarded by (pos? w) (pos? h) above.
        icon-size  (min w h 64)
        scale      (/ icon-size 24)]

    [:*
     [:& shape-custom-strokes {:shape shape}
      (if ^boolean path?
        [:> :path props]
        [:> :rect props])]

     (when (and ^boolean wireframe? (pos? w) (pos? h))
       ;; Lucide "image" icon (lucide.dev, MIT) — 24x24 viewBox, stroke
       ;; currentColor, stroke-width 2, fill none. Translated to the shape
       ;; center and scaled to `icon-size`. The actual stroke/fill colors in
       ;; wireframe come from the higher-specificity restoration rule in
       ;; viewport.scss (`:global(.wireframe-image-icon.wireframe-image-icon)
       ;; :is(rect, circle, path)` -> stroke var(--wireframe-icon) (#ffffff
       ;; white, NOT --wireframe-gray), fill none, stroke-width 2), which
       ;; overrides both these inline presentation attrs and the base
       ;; `#render.wireframe-mode` gray-fill rule. The icon is stroked WHITE
       ;; (not gray) because the image rect underneath is forced to
       ;; --wireframe-gray (#7d7d7d) by the leaf rule — a gray-on-gray glyph
       ;; would be 1:1 contrast and invisible; white reads ~4.1:1.
       ;; `currentColor` here is just a harmless fallback for the inactive
       ;; (non-wireframe) case, where the overlay is not rendered at all.
       [:> :g {:class "wireframe-image-icon"
               :transform (dm/str transform
                                  " translate(" cx " " cy ")"
                                  " scale(" scale ")"
                                  " translate(-12 -12)")}
        [:> :rect {:x 3 :y 3 :width 18 :height 18 :rx 2 :ry 2
                   :fill "none" :stroke "currentColor"
                   :stroke-width 2 :stroke-linecap "round" :stroke-linejoin "round"}]
        [:> :circle {:cx 9 :cy 9 :r 2
                     :fill "none" :stroke "currentColor"
                     :stroke-width 2 :stroke-linecap "round" :stroke-linejoin "round"}]
        [:> :path {:d "M21 15l-3.086-3.086a2 2 0 0 0-2.828 0L6 21"
                   :fill "none" :stroke "currentColor"
                   :stroke-width 2 :stroke-linecap "round" :stroke-linejoin "round"}]])]))
