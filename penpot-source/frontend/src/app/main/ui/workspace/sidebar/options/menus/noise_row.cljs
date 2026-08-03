;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.options.menus.noise-row
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.uuid :as uuid]
   [app.main.data.workspace :as udw]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.store :as st]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.numeric-input :refer [numeric-input*]]
   [app.main.ui.ds.controls.select :refer [select*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

;; Figma-parity noise effect (gap #62). A new effect menu alongside
;; blur / shadow / glass. Group V adds an opaque `[:noise]` vector slot
;; ([:vector ::sm/any]) to schema:shape-attrs in shape.cljc; this menu
;; reads / writes that vector via dwsh/update-shapes. Max two noise
;; effects per shape. The renderer noise-texture overlay is deferred
;; (no build to verify); the value round-trips on the shape. The whole
;; section is additive: when the shape has no `:noise` slot nothing
;; renders and existing behavior is byte-identical.

(def ^:private noise-max-count 2)

(def ^:private color-mode-options
  [{:value "mono" :label (tr "workspace.options.noise-options.color-mode.mono")}
   {:value "duo" :label (tr "workspace.options.noise-options.color-mode.duo")}
   {:value "multi" :label (tr "workspace.options.noise-options.color-mode.multi")}])

(defn- create-noise
  []
  {:id (uuid/next)
   :type :noise
   :hidden false
   :color-mode :mono
   :size-x 4
   :size-y 4
   :density 0.5
   :colors [{:color "#808080" :opacity 1}]})

(defn- check-noise-menu-props
  [old-props new-props]
  (and (identical? (unchecked-get old-props "ids")
                   (unchecked-get new-props "ids"))
       (identical? (unchecked-get old-props "type")
                   (unchecked-get new-props "type"))
       (identical? (unchecked-get old-props "values")
                   (unchecked-get new-props "values"))))

(mf/defc noise-row*
  {::mf/wrap [#(mf/memo' %)]}
  [{:keys [index noise on-update on-remove on-toggle-visibility]}]
  (let [hidden? (:hidden noise)
        color-mode (or (:color-mode noise) :mono)

        on-update-param
        (mf/use-fn
         (mf/deps index on-update)
         (fn [attr value]
           (on-update index attr value)))

        on-color-mode-change
        (mf/use-fn
         (mf/deps on-update-param)
         (fn [value]
           (on-update-param :color-mode (keyword value))))

        on-update-size-x  (mf/use-fn (mf/deps on-update-param) #(on-update-param :size-x %))
        on-update-size-y  (mf/use-fn (mf/deps on-update-param) #(on-update-param :size-y %))
        on-update-density (mf/use-fn (mf/deps on-update-param) #(on-update-param :density %))

        on-remove            (mf/use-fn (mf/deps index) #(on-remove index))
        on-toggle-visibility (mf/use-fn (mf/deps index) #(on-toggle-visibility index))]

    [:div {:class (stl/css :noise-option)}
     [:div {:class (stl/css :noise-basic)}
      [:div {:class (stl/css :noise-basic-info)}
       [:> select* {:class (stl/css :noise-color-mode-select)
                    :default-selected (d/name color-mode)
                    :options color-mode-options
                    :disabled hidden?
                    :on-change on-color-mode-change}]]
      [:div {:class (stl/css :noise-basic-actions)}
       [:> icon-button* {:variant "ghost"
                         :aria-label (tr "workspace.options.noise-options.toggle-noise")
                         :on-click on-toggle-visibility
                         :icon (if hidden? i/hide i/shown)}]
       [:> icon-button* {:variant "ghost"
                         :aria-label (tr "workspace.options.noise-options.remove-noise")
                         :on-click on-remove
                         :icon i/remove}]]]

     (when-not hidden?
       [:div {:class (stl/css :noise-params)
              :data-testid "noise.params"}
        [:div {:class (stl/css :noise-param-row)}
         [:span {:class (stl/css :noise-param-label)}
          (tr "workspace.options.noise-options.size-x")]
         [:> numeric-input* {:placeholder "--"
                             :min 0
                             :on-change on-update-size-x
                             :value (:size-x noise)}]]
        [:div {:class (stl/css :noise-param-row)}
         [:span {:class (stl/css :noise-param-label)}
          (tr "workspace.options.noise-options.size-y")]
         [:> numeric-input* {:placeholder "--"
                             :min 0
                             :on-change on-update-size-y
                             :value (:size-y noise)}]]
        [:div {:class (stl/css :noise-param-row)}
         [:span {:class (stl/css :noise-param-label)}
          (tr "workspace.options.noise-options.density")]
         [:> numeric-input* {:placeholder "--"
                             :min 0 :max 1 :step 0.05
                             :on-change on-update-density
                             :value (:density noise)}]]])]))

(mf/defc noise-menu*
  {::mf/wrap [#(mf/memo' % check-noise-menu-props)]}
  [{:keys [ids type values] :as props}]
  (let [noises        (mf/with-memo [values]
                        (if (= :multiple values)
                          values
                          (not-empty (into [] values))))

        has-noise?     (or (= :multiple noises)
                           (some? (seq noises)))

        show-content*   (mf/use-state true)
        show-content?   (deref show-content*)

        toggle-content  (mf/use-fn #(swap! show-content* not))

        on-add-noise
        (mf/use-fn
         (mf/deps ids)
         (fn []
           (when (and (some? noises) (< (count noises) noise-max-count))
             (st/emit! (udw/trigger-bounding-box-cloaking ids))
             (st/emit! (dwsh/update-shapes ids #(assoc % :noise (conj (or (:noise %) []) (create-noise))))))))

        on-remove-all
        (mf/use-fn
         (mf/deps ids)
         (fn []
           (st/emit! (udw/trigger-bounding-box-cloaking ids))
           (st/emit! (dwsh/update-shapes ids #(dissoc % :noise)))))

        on-remove
        (mf/use-fn
         (mf/deps ids)
         (fn [index]
           (st/emit! (udw/trigger-bounding-box-cloaking ids))
           (st/emit! (dwsh/update-shapes ids #(update % :noise (fn [v] (into [] (d/remove-at-index (or v []) index))))))))

        on-toggle-visibility
        (mf/use-fn
         (mf/deps ids)
         (fn [index]
           (st/emit! (udw/trigger-bounding-box-cloaking ids))
           (st/emit! (dwsh/update-shapes ids #(update-in % [:noise index :hidden] not)))))

        on-update
        (mf/use-fn
         (mf/deps ids)
         (fn [index attr value]
           (st/emit! (dwsh/update-shapes ids #(assoc-in % [:noise index attr] value)))))]

    (when-not (= :multiple noises)
      [:section {:class (stl/css :element-set)
                 :aria-label (tr "workspace.options.noise-options.title")}
       [:div {:class (stl/css :element-title)}
        [:> title-bar* {:collapsable  has-noise?
                        :collapsed    (not show-content?)
                        :on-collapsed toggle-content
                        :title        (tr "workspace.options.noise-options.title")
                        :class        (stl/css-case :title-spacing-noise (not has-noise?))}
         (when (and (not has-noise?) (< (count (or noises [])) noise-max-count))
           [:> icon-button*
            {:variant "ghost"
             :aria-label (tr "workspace.options.noise-options.add-noise")
             :on-click on-add-noise
             :icon i/add
             :data-testid "add-noise"}])]]

       (when (and show-content? has-noise?)
         [:div {:class (stl/css :element-set-content)}
          (for [[index noise] (d/enumerate (or noises []))]
            [:> noise-row*
             {:key (dm/str (:id noise))
              :index index
              :noise noise
              :on-update on-update
              :on-remove on-remove
              :on-toggle-visibility on-toggle-visibility}])])])))