;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.viewport.pixel-preview
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.math :as mth]
   [app.main.ui.css-cursors :as cur]
   [app.util.dom :as dom]
   [app.util.object :as obj]
   [beicon.v2.core :as rx]
   [rumext.v2 :as mf]))

;; Private scratch canvas factory, mirroring pixel_overlay.cljs:46-55
;; but sizing to the device-pixel buffer (vport-size * dpr). A single
;; lazily-created canvas is resized in place across renders.
(defn- resize-canvas
  [canvas width height]
  (let [resized (volatile! false)]
    (when-not (= (unchecked-get canvas "width") width)
      (obj/set! canvas "width" width)
      (vreset! resized true))
    (when-not (= (unchecked-get canvas "height") height)
      (obj/set! canvas "height" height)
      (vreset! resized true))
    canvas))

(def ^:private get-preview-canvas
  ((fn []
     (let [internal-state #js {:canvas nil}]
       (fn [width height]
         (let [canvas (unchecked-get internal-state "canvas")]
           (if canvas
             (resize-canvas canvas width height)
             (let [new-canvas (js/document.createElement "canvas")]
               (obj/set! internal-state "canvas" new-canvas)
               (resize-canvas new-canvas width height)))))))))

;; Figma-parity pixel-preview overlay (gap #46). Rasterizes the #render
;; SVG to a device-pixel <canvas> and composites it on top of the canvas
;; with image-rendering:pixelated, so users see the actual 1:1 device
;; rasterization. Mounted only under the :pixel-preview layout flag
;; (default off); see viewport.cljs for the guard. When the flag is off
;; the component is never mounted, so zero DOM/SVG/canvas is added —
;; byte-identical to today.
(mf/defc pixel-preview*
  [{:keys [vport viewport-ref zoom vbox]}]
  (let [dpr              (or js/window.devicePixelRatio 1)
        cw               (mth/ceil (* (:width vport 0) dpr))
        ch               (mth/ceil (* (:height vport 0) dpr))
        canvas           (mf/use-ref nil)
        update-str       (mf/use-memo #(rx/subject))

        render-frame
        (mf/use-fn
         (mf/deps cw ch)
         (fn []
           (when-let [svg-node (dom/get-element "render")]
             (when-let [canvas-node (mf/ref-val canvas)]
               (when-let [ctx (.getContext canvas-node "2d")]
                 (let [image (js/Image.)
                       data-uri (dom/svg-node->data-uri svg-node)]
                   (set! (.-imageSmoothingEnabled ctx) false)
                   (set! (.-onload image)
                         (fn []
                           (.clearRect ctx 0 0 cw ch)
                           (.drawImage ctx image 0 0 cw ch)))
                   (set! (.-onerror image) (fn []))
                   (set! (.-src image) data-uri)))))))

        handle-svg-change
        (mf/use-fn
         (fn []
           (rx/push! update-str :update)))]

    ;; Keep the visible canvas sized to the device-pixel buffer.
    (mf/use-effect
     (mf/deps cw ch)
     (fn []
       (when-let [node (mf/ref-val canvas)]
         (obj/set! node "width" cw)
         (obj/set! node "height" ch))))

    ;; Debounced re-raster on SVG mutation (attributes/childList/subtree/
    ;; characterData) — mirrors pixel_overlay.cljs:218-237.
    (mf/use-effect
     (fn []
       (let [sub (->> update-str
                      (rx/debounce 10)
                      (rx/subs! (fn [] (render-frame))))]
         #(rx/dispose! sub))))

    ;; MutationObserver on #render — drives the debounced re-raster.
    (mf/use-effect
     (fn []
       (let [config #js {:attributes true
                         :childList true
                         :subtree true
                         :characterData true}
             svg-node (dom/get-element "render")
             observer (js/MutationObserver. handle-svg-change)]
         (when svg-node
           (.observe observer svg-node config)
           (handle-svg-change))
         #(.disconnect observer))))

    ;; Re-raster when viewport size, zoom, or viewBox changes.
    (mf/use-effect
     (mf/deps (:width vport) (:height vport) zoom vbox)
     (fn []
       (render-frame)))

    [:div {:id "pixel-preview"
           :class (stl/css :pixel-preview)}
     [:canvas {:ref canvas
               :width cw
               :height ch
               :style #js {:position "absolute"
                           :left 0
                           :top 0
                           :width (dm/str (:width vport 0) "px")
                           :height (dm/str (:height vport 0) "px")
                           :pointer-events "none"}}]]))