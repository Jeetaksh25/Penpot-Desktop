;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.options.rows.shader-row
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.store :as st]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.select :refer [select*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.workspace.sidebar.options.menus.input-wrapper-tokens :refer [numeric-input-wrapper*]]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

;; Figma-parity shader effects / shader fills (gap #64). Self-contained,
;; guarded rumext component for the shader-effect / shader-fill preset
;; picker (clouds / halftone / noise) plus an opaque :shader-params map
;; editor. Renders ONLY when `shader-effect` (or `shader-fill`) is
;; non-nil, so an absent shader = no UI (byte-identical).
;;
;; The WebGPU / fragment-shader renderer is DEFERRED (significant GPU
;; work); these controls write the [:vector ::sm/any] slot on the shape
;; (or the :shader-fill map on the fill) only. Wiring into a parent fill
;; or effects menu is intentionally deferred to avoid file-disjoint
;; conflicts with other feature groups; the component is ready to be
;; `[:> shader-row* …]`'d from any caller.

(def ^:private shader-preset-options
  [{:value :clouds   :label (tr "workspace.options.shader-options.preset.clouds")}
   {:value :halftone :label (tr "workspace.options.shader-options.preset.halftone")}
   {:value :noise    :label (tr "workspace.options.shader-options.preset.noise")}])

(mf/defc shader-row*
  {::mf/props {:shader-effect :map
               :on-change :function}}
  [{:keys [shader-effect on-change]}]
  (when (some? shader-effect)
    (let [preset  (:shader-preset shader-effect :clouds)
          params  (:shader-params shader-effect)

          on-preset-change
          (mf/use-fn
           (mf/deps on-change)
           (fn [value]
             (when (some? on-change)
               (on-change (assoc shader-effect :shader-preset (keyword value))))))

          on-param-change
          (mf/use-fn
           (mf/deps on-change params)
           (fn [key value]
             (when (some? on-change)
               (on-change (assoc shader-effect :shader-params (assoc params key value))))))

          on-scale-change
          (mf/use-fn (mf/deps on-param-change) #(on-param-change :scale %))

          on-contrast-change
          (mf/use-fn (mf/deps on-param-change) #(on-param-change :contrast %))]

      [:div {:class (stl/css :element-set)
             :data-testid "shader-row"}
       [:div {:class (stl/css :first-row)}
        [:span {:class (stl/css :label)}
         (tr "workspace.options.shader-options.title")]]
       [:div {:class (stl/css :row)}
        [:span {:class (stl/css :label)}
         (tr "workspace.options.shader-options.preset")]
        [:> select*
         {:class (stl/css :shader-preset-select)
          :default-selected (name preset)
          :options shader-preset-options
          :on-change on-preset-change}]]
       [:div {:class (stl/css :row)}
        [:span {:class (stl/css :label)}
         (tr "workspace.options.shader-options.scale")]
        [:> numeric-input-wrapper*
         {:attr :scale
          :placeholder "--"
          :min 0
          :on-change on-scale-change
          :value (:scale params 1)}]]
       [:div {:class (stl/css :row)}
        [:span {:class (stl/css :label)}
         (tr "workspace.options.shader-options.contrast")]
        [:> numeric-input-wrapper*
         {:attr :contrast
          :placeholder "--"
          :min 0 :max 1
          :on-change on-contrast-change
          :value (:contrast params 0.5)}]])]))