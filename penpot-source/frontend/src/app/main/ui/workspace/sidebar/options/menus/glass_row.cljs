;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.options.menus.glass-row
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
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.tooltip.tooltip :refer [tooltip*]]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

;; Figma-parity glass effect (gap #61). A new effect menu alongside
;; blur / shadow. Group V adds an opaque `[:glass]` vector slot
;; ([:vector ::sm/any]) to schema:shape-attrs in shape.cljc; this menu
;; reads / writes that vector via dwsh/update-shapes. Max one glass
;; effect per layer. The renderer glass shader (refraction + dispersion
;; + frost noise) is deferred (significant GPU work, no build to
;; verify); the value round-trips on the shape. The whole section is
;; additive: when the shape has no `:glass` slot nothing renders and
;; existing behavior is byte-identical.

(def ^:private glass-max-count 1)

(defn- create-glass
  []
  {:id (uuid/next)
   :type :glass
   :hidden false
   :refraction 0.5
   :dispersion 0
   :frost 0
   :splay 0
   :light-angle 0
   :light-intensity 0.5
   :depth 0.5})

(defn- check-glass-menu-props
  [old-props new-props]
  (and (identical? (unchecked-get old-props "ids")
                   (unchecked-get new-props "ids"))
       (identical? (unchecked-get old-props "type")
                   (unchecked-get new-props "type"))
       (identical? (unchecked-get old-props "values")
                   (unchecked-get new-props "values"))))

(mf/defc glass-row*
  {::mf/wrap [#(mf/memo' %)]}
  [{:keys [index glass on-update on-remove on-toggle-visibility]}]
  (let [hidden? (:hidden glass)

        on-update-param
        (mf/use-fn
         (mf/deps index on-update)
         (fn [attr value]
           (on-update index attr value)))

        on-update-refraction  (mf/use-fn (mf/deps on-update-param) #(on-update-param :refraction %))
        on-update-dispersion  (mf/use-fn (mf/deps on-update-param) #(on-update-param :dispersion %))
        on-update-frost      (mf/use-fn (mf/deps on-update-param) #(on-update-param :frost %))
        on-update-splay      (mf/use-fn (mf/deps on-update-param) #(on-update-param :splay %))
        on-update-light-angle (mf/use-fn (mf/deps on-update-param) #(on-update-param :light-angle %))
        on-update-light-intensity (mf/use-fn (mf/deps on-update-param) #(on-update-param :light-intensity %))
        on-update-depth      (mf/use-fn (mf/deps on-update-param) #(on-update-param :depth %))

        on-remove       (mf/use-fn (mf/deps index) #(on-remove index))
        on-toggle-visibility (mf/use-fn (mf/deps index) #(on-toggle-visibility index))]

    [:div {:class (stl/css :glass-option)}
     [:div {:class (stl/css :glass-basic)}
      [:div {:class (stl/css :glass-basic-info)}
       [:span {:class (stl/css :glass-basic-label)}
        (tr "workspace.options.glass-options.title")]]
      [:div {:class (stl/css :glass-basic-actions)}
       [:> icon-button* {:variant "ghost"
                         :aria-label (tr "workspace.options.glass-options.toggle-glass")
                         :on-click on-toggle-visibility
                         :icon (if hidden? i/hide i/shown)}]
       [:> icon-button* {:variant "ghost"
                         :aria-label (tr "workspace.options.glass-options.remove-glass")
                         :on-click on-remove
                         :icon i/remove}]]]

     (when-not hidden?
       [:div {:class (stl/css :glass-params)
              :data-testid "glass.params"}
        [:div {:class (stl/css :glass-param-row)}
         [:span {:class (stl/css :glass-param-label)}
          (tr "workspace.options.glass-options.refraction")]
         [:> numeric-input* {:placeholder "--"
                             :min 0 :max 1 :step 0.05
                             :on-change on-update-refraction
                             :value (:refraction glass)}]]
        [:div {:class (stl/css :glass-param-row)}
         [:span {:class (stl/css :glass-param-label)}
          (tr "workspace.options.glass-options.dispersion")]
         [:> numeric-input* {:placeholder "--"
                             :min 0 :max 1 :step 0.05
                             :on-change on-update-dispersion
                             :value (:dispersion glass)}]]
        [:div {:class (stl/css :glass-param-row)}
         [:span {:class (stl/css :glass-param-label)}
          (tr "workspace.options.glass-options.frost")]
         [:> numeric-input* {:placeholder "--"
                             :min 0 :max 1 :step 0.05
                             :on-change on-update-frost
                             :value (:frost glass)}]]
        [:div {:class (stl/css :glass-param-row)}
         [:span {:class (stl/css :glass-param-label)}
          (tr "workspace.options.glass-options.splay")]
         [:> numeric-input* {:placeholder "--"
                             :min 0 :max 1 :step 0.05
                             :on-change on-update-splay
                             :value (:splay glass)}]]
        [:div {:class (stl/css :glass-param-row)}
         [:span {:class (stl/css :glass-param-label)}
          (tr "workspace.options.glass-options.light-angle")]
         [:> numeric-input* {:placeholder "--"
                             :min 0 :max 360 :step 1
                             :on-change on-update-light-angle
                             :value (:light-angle glass)}]]
        [:div {:class (stl/css :glass-param-row)}
         [:span {:class (stl/css :glass-param-label)}
          (tr "workspace.options.glass-options.light-intensity")]
         [:> numeric-input* {:placeholder "--"
                             :min 0 :max 1 :step 0.05
                             :on-change on-update-light-intensity
                             :value (:light-intensity glass)}]]
        [:div {:class (stl/css :glass-param-row)}
         [:span {:class (stl/css :glass-param-label)}
          (tr "workspace.options.glass-options.depth")]
         [:> numeric-input* {:placeholder "--"
                             :min 0 :max 1 :step 0.05
                             :on-change on-update-depth
                             :value (:depth glass)}]]])]))

(mf/defc glass-menu*
  {::mf/wrap [#(mf/memo' % check-glass-menu-props)]}
  [{:keys [ids type values] :as props}]
  (let [glasses        (mf/with-memo [values]
                         (if (= :multiple values)
                           values
                           (not-empty (into [] values))))

        has-glass?     (or (= :multiple glasses)
                           (some? (seq glasses)))

        show-content*   (mf/use-state true)
        show-content?   (deref show-content*)

        toggle-content  (mf/use-fn #(swap! show-content* not))

        on-add-glass
        (mf/use-fn
         (mf/deps ids)
         (fn []
           ;; First-effect seeding: the section now always mounts (see the
           ;; shape option pages), so glasses is nil for an empty slot.
           ;; Guard on (or glasses []) so count never throws on nil and the
           ;; first glass can be seeded from a nil :glass slot.
           (when (< (count (or glasses [])) glass-max-count)
             (st/emit! (udw/trigger-bounding-box-cloaking ids))
             (st/emit! (dwsh/update-shapes ids #(assoc % :glass [(create-glass)]))))))

        on-remove-all
        (mf/use-fn
         (mf/deps ids)
         (fn []
           (st/emit! (udw/trigger-bounding-box-cloaking ids))
           (st/emit! (dwsh/update-shapes ids #(dissoc % :glass)))))

        on-remove
        (mf/use-fn
         (mf/deps ids)
         (fn [index]
           (st/emit! (udw/trigger-bounding-box-cloaking ids))
           (st/emit! (dwsh/update-shapes ids #(update % :glass (fn [v] (into [] (d/remove-at-index (or v []) index))))))))

        on-toggle-visibility
        (mf/use-fn
         (mf/deps ids)
         (fn [index]
           (st/emit! (udw/trigger-bounding-box-cloaking ids))
           (st/emit! (dwsh/update-shapes ids #(update-in % [:glass index :hidden] not)))))

        on-update
        (mf/use-fn
         (mf/deps ids)
         (fn [index attr value]
           (st/emit! (dwsh/update-shapes ids #(assoc-in % [:glass index attr] value)))))]

    (when-not (= :multiple glasses)
      [:section {:class (stl/css :element-set)
                 :aria-label (tr "workspace.options.glass-options.title")}
       [:div {:class (stl/css :element-title)}
        [:> title-bar* {:collapsable  has-glass?
                        :collapsed    (not show-content?)
                        :on-collapsed toggle-content
                        :title        (tr "workspace.options.glass-options.title")
                        :class        (stl/css-case :title-spacing-glass (not has-glass?))}
         (when (and (not has-glass?) (< (count (or glasses [])) glass-max-count))
           [:> icon-button*
            {:variant "ghost"
             :aria-label (tr "workspace.options.glass-options.add-glass")
             :on-click on-add-glass
             :icon i/add
             :data-testid "add-glass"}])]]

       (when (and show-content? has-glass?)
         [:div {:class (stl/css :element-set-content)}
          (for [[index glass] (d/enumerate (or glasses []))]
            [:> glass-row*
             {:key (dm/str (:id glass))
              :index index
              :glass glass
              :on-update on-update
              :on-remove on-remove
              :on-toggle-visibility on-toggle-visibility}])])])))