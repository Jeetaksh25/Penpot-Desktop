;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.shapes.video
  "Video/GIF playback render helper (ALL_APPS_PARITY P2.39).

  When a shape carries the `:ovion \"video\"` plugin-data slot (see
  app.main.data.workspace.video), its render emits an HTML5 `<video>`
  element — or an `<img>` for an animated GIF source — wrapped in an SVG
  `<foreignObject>` so it composes with the rest of the SVG canvas. The
  underlying rect/path fill is NOT replaced: the video sits on top of the
  shape's bounding box, so fills/strokes/shadows of the carrier shape still
  apply (the foreignObject is positioned at the shape's x/y/width/height
  in local coords + the shape transform).

  This module is render-only and side-effect free. The viewer runtime
  (ui.viewer.shapes) adds the scroll-video motion effect; the inspector
  menu (ui.workspace.sidebar.options.menus.video) authors the slot.

  Byte-identical-when-inactive: when the slot is absent the carrier shape
  renderer emits its existing SVG node and this module contributes nothing."
  (:require
   [app.common.data.macros :as dm]
   [app.common.geom.shapes :as gsh]
   [app.main.data.workspace.video :as dwv]
   [app.util.object :as obj]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(defn ^boolean gif-src?
  "True when `src` looks like an animated GIF (`.gif` extension, case-
  insensitive, ignoring query strings). Animated GIFs render via `<img>`
  (no `<video>` controls/codec needed)."
  [src]
  (if (nil? src)
    false
    (let [s (str/lower (str src))
          ;; strip query/hash before checking the extension
          s (or (first (str/split s "?")) s)
          s (or (first (str/split s "#")) s)]
      (str/ends-with? s ".gif"))))

(mf/defc video-foreign-object*
  "Render a `<foreignObject>` wrapping a `<video>` (or `<img>` for a GIF)
  for the carrier `shape`. Reads the video slot; when absent returns nil
  (caller should guard, but this is nil-safe too). The foreignObject is
  positioned at the shape's x/y with the shape's width/height + transform
  so it lands exactly on the shape's bounding box."
  {::mf/wrap-props false}
  [props]
  (let [shape (unchecked-get props "shape")
        cfg   (dwv/read-video-slot shape)]
    (when (and (map? cfg) (seq (:src cfg)))
      (let [src      (:src cfg)
            poster   (:poster cfg)
            loop?    (boolean (:loop? cfg))
            muted?   (boolean (:muted? cfg))
            controls? (boolean (:controls? cfg))
            autoplay? (boolean (:autoplay? cfg))
            gif?     (gif-src? src)

            x        (dm/get-prop shape :x)
            y        (dm/get-prop shape :y)
            w        (dm/get-prop shape :width)
            h        (dm/get-prop shape :height)
            transform (gsh/transform-str shape)

            ;; Common HTML element attrs. We set width/height to 100% so the
            ;; media fills the foreignObject box (which carries the absolute
            ;; px size). object-fit:cover mirrors how Penpot crops image fills.
            base-style #js {:width "100%"
                            :height "100%"
                            :objectFit "cover"
                            :display "block"
                            :pointerEvents (if controls? "auto" "none")}

            fo-props (-> (obj/create)
                         (obj/set! "x" x)
                         (obj/set! "y" y)
                         (obj/set! "width" w)
                         (obj/set! "height" h)
                         (obj/set! "transform" transform)
                         ;; keep the video from intercepting canvas pointer
                         ;; events unless the user opted into native controls
                         (obj/set! "style" #js {:pointerEvents
                                                (if controls? "auto" "none")}))]
        (if gif?
          ;; Animated GIF: a plain <img> loops natively, no controls/codec.
          [:> :foreignObject fo-props
           [:> :img (-> (obj/create)
                        (obj/set! "src" src)
                        (obj/set! "alt" "")
                        (obj/set! "style" base-style))]]
          [:> :foreignObject fo-props
           [:> :video (-> (obj/create)
                          (obj/set! "src" src)
                          (obj/set! "poster" (or poster ""))
                          (obj/set! "loop" loop?)
                          (obj/set! "muted" muted?)
                          (obj/set! "controls" controls?)
                          (obj/set! "autoPlay" autoplay?)
                          (obj/set! "playsInline" true)
                          (obj/set! "preload" (if (or autoplay? (some? poster)) "auto" "metadata"))
                          (obj/set! "style" base-style))]])))))