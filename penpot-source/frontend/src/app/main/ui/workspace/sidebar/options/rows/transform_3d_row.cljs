;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.options.rows.transform-3d-row
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.store :as st]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.workspace.sidebar.options.menus.input-wrapper-tokens :refer [numeric-input-wrapper*]]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

;; Figma-parity 3D transforms (gap #66). Self-contained, guarded rumext
;; component for the optional :transform-3d map on a shape (rotation-x /
;; rotation-y / rotation-z / perspective). Renders ONLY when
;; `transform-3d` is non-nil, so an absent map = no UI (byte-identical).
;;
;; The renderer 3D matrix / perspective projection is DEFERRED
;; (significant GPU + reproject work); these controls write the
;; :transform-3d map on the shape only. Wiring into the shape sidebar
;; (e.g. the transform menu) is intentionally deferred to avoid
;; file-disjoint conflicts with other feature groups; the component is
;; ready to be `[:> transform-3d-row* …]`'d from any caller.

(mf/defc transform-3d-row*
  {::mf/props {:transform-3d :map
               :on-change :function}}
  [{:keys [transform-3d on-change]}]
  (when (some? transform-3d)
    (let [rx      (:rotation-x transform-3d 0)
          ry      (:rotation-y transform-3d 0)
          rz      (:rotation-z transform-3d 0)
          persp   (:perspective transform-3d 800)

          update-field
          (mf/use-fn
           (mf/deps on-change)
           (fn [field value]
             (when (some? on-change)
               (on-change (assoc transform-3d field value)))))

          on-rx-change  (mf/use-fn (mf/deps update-field) #(update-field :rotation-x %))
          on-ry-change  (mf/use-fn (mf/deps update-field) #(update-field :rotation-y %))
          on-rz-change  (mf/use-fn (mf/deps update-field) #(update-field :rotation-z %))
          on-persp-change (mf/use-fn (mf/deps update-field) #(update-field :perspective %))

          on-reset
          (mf/use-fn
           (mf/deps on-change)
           (fn []
             (when (some? on-change)
               (on-change {:rotation-x 0 :rotation-y 0 :rotation-z 0 :perspective 800}))))]

      [:div {:class (stl/css :element-set)
             :data-testid "transform-3d-row"}
       [:div {:class (stl/css :first-row)}
        [:span {:class (stl/css :label)}
         (tr "workspace.options.transform-3d.title")]
        [:> icon-button* {:variant "ghost"
                          :aria-label (tr "workspace.options.transform-3d.reset")
                          :on-click on-reset
                          :icon i/reload
                          :tooltip-placement "top-left"}]]
       [:div {:class (stl/css :row)}
        [:span {:class (stl/css :label)}
         (tr "workspace.options.transform-3d.rotation-x")]
        [:> numeric-input-wrapper*
         {:attr :rotation-x
          :placeholder "--"
          :on-change on-rx-change
          :value rx}]]
       [:div {:class (stl/css :row)}
        [:span {:class (stl/css :label)}
         (tr "workspace.options.transform-3d.rotation-y")]
        [:> numeric-input-wrapper*
         {:attr :rotation-y
          :placeholder "--"
          :on-change on-ry-change
          :value ry}]]
       [:div {:class (stl/css :row)}
        [:span {:class (stl/css :label)}
         (tr "workspace.options.transform-3d.rotation-z")]
        [:> numeric-input-wrapper*
         {:attr :rotation-z
          :placeholder "--"
          :on-change on-rz-change
          :value rz}]]
       [:div {:class (stl/css :row)}
        [:span {:class (stl/css :label)}
         (tr "workspace.options.transform-3d.perspective")]
        [:> numeric-input-wrapper*
         {:attr :perspective
          :placeholder "--"
          :min 1
          :on-change on-persp-change
          :value persp}]])]))