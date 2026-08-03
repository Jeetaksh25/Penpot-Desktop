;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.options.rows.brush-row
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.store :as st]
   [app.main.ui.components.numeric-input :as deprecated-input]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.select :refer [select*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.workspace.sidebar.options.menus.input-wrapper-tokens :refer [numeric-input-wrapper*]]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

;; Figma-parity brush tool settings row (gap #52). Self-contained, guarded
;; rumext component for the brush-asset editor (size / opacity / spacing /
;; mode / scatter / source-shape-id). It renders ONLY when a non-nil
;; `brush` is passed, so a missing :brush asset = no UI (byte-identical).
;; The path-following renderer (scatter / stretch along a stroke path) is
;; DEFERRED; these controls write the brush-asset map only.
;;
;; Wiring into a parent menu (e.g. a dedicated brush panel or the shape
;; sidebar) is intentionally deferred to avoid file-disjoint conflicts with
;; other feature groups; the component is ready to be `[:> brush-row* …]`'d
;; from any caller that owns a brush asset.

(def ^:private brush-mode-options
  [{:value :stretch  :label (tr "workspace.options.brush-options.mode.stretch")}
   {:value :scatter  :label (tr "workspace.options.brush-options.mode.scatter")}])

(mf/defc brush-row*
  {::mf/props {:brush :map
               :on-change :function}}
  [{:keys [brush on-change]}]
  (when (some? brush)
    (let [mode     (:mode brush :stretch)
          size     (:size brush 8)
          opacity  (:opacity brush 1)
          spacing  (:spacing brush 0.1)
          scatter  (:scatter brush 0)
          source-id (:source-shape-id brush)

          update-field
          (mf/use-fn
           (mf/deps on-change)
           (fn [field value]
             (when (some? on-change)
               (on-change (assoc brush field value)))))

          on-mode-change
          (mf/use-fn
           (mf/deps update-field)
           (fn [value]
             (update-field :mode (keyword value))))

          on-size-change
          (mf/use-fn (mf/deps update-field) #(update-field :size %))

          on-opacity-change
          (mf/use-fn (mf/deps update-field) #(update-field :opacity %))

          on-spacing-change
          (mf/use-fn (mf/deps update-field) #(update-field :spacing %))

          on-scatter-change
          (mf/use-fn (mf/deps update-field) #(update-field :scatter %))]

      [:div {:class (stl/css :element-set)
             :data-testid "brush-row"}
       [:div {:class (stl/css :first-row)}
        [:span {:class (stl/css :label)}
         (tr "workspace.options.brush-options.title")]]
       [:div {:class (stl/css :row)}
        [:span {:class (stl/css :label)}
         (tr "workspace.options.brush-options.mode")]
        [:> select*
         {:class (stl/css :brush-mode-select)
          :default-selected (name mode)
          :options brush-mode-options
          :on-change on-mode-change}]]
       [:div {:class (stl/css :row)}
        [:span {:class (stl/css :label)}
         (tr "workspace.options.brush-options.size")]
        [:> numeric-input-wrapper*
         {:attr :size
          :placeholder "--"
          :min 0
          :on-change on-size-change
          :value size}]]
       [:div {:class (stl/css :row)}
        [:span {:class (stl/css :label)}
         (tr "workspace.options.brush-options.opacity")]
        [:> numeric-input-wrapper*
         {:attr :opacity
          :placeholder "--"
          :min 0 :max 1
          :on-change on-opacity-change
          :value opacity}]]
       [:div {:class (stl/css :row)}
        [:span {:class (stl/css :label)}
         (tr "workspace.options.brush-options.spacing")]
        [:> numeric-input-wrapper*
         {:attr :spacing
          :placeholder "--"
          :min 0 :max 1
          :on-change on-spacing-change
          :value spacing}]]
       (when (= mode :scatter)
         [:div {:class (stl/css :row)}
          [:span {:class (stl/css :label)}
           (tr "workspace.options.brush-options.scatter")]
          [:> numeric-input-wrapper*
           {:attr :scatter
            :placeholder "--"
            :min 0
            :on-change on-scatter-change
            :value scatter}]])
       (when (some? source-id)
         [:div {:class (stl/css :row)}
          [:span {:class (stl/css :label)}
           (tr "workspace.options.brush-options.source-shape")]
          [:span {:class (stl/css :value-text)} (str source-id)]])])))