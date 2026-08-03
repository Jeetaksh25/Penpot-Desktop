;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.shapes.mask
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.geom.rect :as grc]
   [app.main.ui.context :as muc]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(defn mask-id [render-id mask]
  (dm/str render-id "-" (:id mask) "-mask"))

(defn mask-url [render-id mask]
  (dm/str "url(#" (mask-id render-id mask) ")"))

;; Figma-parity mask variants (gap #26). A separate id/url pair for the
;; luminance mask so a mask group can carry both an alpha <mask> and a
;; luminance <mask> without id collision (only one is referenced at a
;; time, chosen by :mask-mode in group.cljs).
(defn luminance-mask-id [render-id mask]
  (dm/str render-id "-" (:id mask) "-lmask"))

(defn luminance-mask-url [render-id mask]
  (dm/str "url(#" (luminance-mask-id render-id mask) ")"))

(defn clip-id [render-id mask]
  (dm/str render-id "-" (:id mask) "-clip"))

(defn clip-url [render-id mask]
  (dm/str "url(#" (clip-id render-id mask) ")"))

(defn filter-id [render-id mask]
  (dm/str render-id "-" (:id mask) "-filter"))

(defn filter-url [render-id mask]
  (dm/str "url(#" (filter-id render-id mask) ")"))

(defn set-white-fill
  [shape]
  (let [update-color
        (fn [data]
          (-> data
              (dissoc :fill-color :fill-opacity :fill-color-gradient)
              (assoc :fills [{:fill-color "#FFFFFF" :fill-opacity 1}])))]
    (-> shape
        (d/update-when :position-data #(mapv update-color %))
        (assoc :stroke-color "#FFFFFF" :stroke-opacity 1))))

(defn- point->str
  [point]
  (dm/str (dm/get-prop point :x) "," (dm/get-prop point :y)))

(defn mask-factory
  [shape-wrapper]
  (mf/fnc mask-shape
    {::mf/wrap-props false}
    [props]
    (let [mask       (unchecked-get props "mask")
          render-id  (mf/use-ctx muc/render-id)

          ;; Figma-parity mask variants (gap #26). :mask-mode is passed in
          ;; from group.cljs (it lives on the mask GROUP shape, not the
          ;; mask child). :alpha (default/absent) reproduces the legacy
          ;; alpha mask byte-for-byte. :vector is a hard clip by the mask
          ;; outline (no <mask> element, clip only). :luminance masks by
          ;; brightness via a <mask mask-type "luminance"> that renders
          ;; the mask shape with its real fills (no white-flood filter).
          mask-mode  (or (unchecked-get props "mask-mode")
                         (:mask-mode mask)
                         :alpha)

          svg-text?  (and ^boolean (cfh/text-shape? mask)
                          ^boolean (some? (:position-data mask)))

          points     (dm/get-prop mask :points)
          points-str (mf/with-memo [points]
                       (->> (map point->str points)
                            (str/join " ")))

          bounds     (mf/with-memo [points]
                       (grc/points->rect points))

          bx         (dm/get-prop bounds :x)
          by         (dm/get-prop bounds :y)
          bw         (dm/get-prop bounds :width)
          bh         (dm/get-prop bounds :height)

          shape      (mf/with-memo [mask]
                       (-> mask
                           (dissoc :shadow :blur)
                           (assoc :is-mask? true)))

          vector?    ^boolean (= :vector mask-mode)
          luminance? ^boolean (= :luminance mask-mode)]

      [:defs
       ;; The alpha white-flood filter is only needed for the :alpha mode.
       (when-not (or vector? luminance?)
         [:filter {:id (filter-id render-id mask)}
          [:feFlood {:flood-color "white"
                     :result "FloodResult"}]
          [:feComposite {:in "FloodResult"
                         :in2 "SourceGraphic"
                         :operator "in"
                         :result "comp"}]])
       ;; Clip path is necessary so the elements inside the mask won't affect
       ;; the events outside. Clip hides the elements but mask doesn't (like display vs visibility)
       ;; we cannot use clips instead of mask because clips can only be simple shapes
       [:clipPath {:class "mask-clip-path"
                   :id (clip-id render-id mask)}
        [:polyline {:points points-str}]]

       ;; :vector mode is clip-only (no <mask> element) — the clipPath
       ;; above already hard-clips by the outline.
       (when-not vector?
         (if luminance?
           ;; Luminance mask: render the mask shape with its real fills so
           ;; the mask's brightness drives visibility (mask-type luminance).
           [:mask {:class "mask-shape"
                   :id (luminance-mask-id render-id mask)
                   :x bx
                   :y by
                   :width bw
                   :height bh

                   :data-old-x bx
                   :data-old-y by
                   :data-old-width bw
                   :data-old-height bh
                   :mask-type "luminance"
                   :mask-units "userSpaceOnUse"}
            [:g {}
             [:& shape-wrapper {:shape shape}]]]

           ;; :alpha / absent — the legacy alpha mask, byte-identical to
           ;; the pre-gap-#26 output.
           [:mask {:class "mask-shape"
                   :id (mask-id render-id mask)
                   :x bx
                   :y by
                   :width bw
                   :height bh

                   ;; This is necesary to prevent a race condition in the dynamic-modifiers whether the modifier
                   ;; triggers afte the render
                   :data-old-x bx
                   :data-old-y by
                   :data-old-width bw
                   :data-old-height bh
                   :mask-units "userSpaceOnUse"}

            [:g {:filter (when-not ^boolean svg-text?
                           (filter-url render-id mask))}
             [:& shape-wrapper {:shape shape}]]]))])))

