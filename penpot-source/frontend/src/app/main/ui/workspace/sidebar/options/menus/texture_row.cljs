;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.options.menus.texture-row
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
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

;; Figma-parity texture effect (gap #63). A new effect menu alongside
;; blur / shadow / glass / noise. Group V adds an opaque `[:texture]`
;; vector slot ([:vector ::sm/any]) to schema:shape-attrs in shape.cljc;
;; this menu reads / writes that vector via dwsh/update-shapes. Max one
;; texture effect per layer. The renderer distress / texture overlay
;; (clipped to shape bounds, with shadow interaction) is deferred (no
;; build to verify); the value round-trips on the shape. The whole
;; section is additive: when the shape has no `:texture` slot nothing
;; renders and existing behavior is byte-identical.

(def ^:private texture-max-count 1)

(defn- create-texture
  []
  {:id (uuid/next)
   :type :texture
   :hidden false
   :size-x 4
   :size-y 4
   :radius 0
   :clip-to-shape true})

(defn- check-texture-menu-props
  [old-props new-props]
  (and (identical? (unchecked-get old-props "ids")
                   (unchecked-get new-props "ids"))
       (identical? (unchecked-get old-props "type")
                   (unchecked-get new-props "type"))
       (identical? (unchecked-get old-props "values")
                   (unchecked-get new-props "values"))))

(mf/defc texture-row*
  {::mf/wrap [#(mf/memo' %)]}
  [{:keys [index texture on-update on-remove on-toggle-visibility]}]
  (let [hidden? (:hidden texture)
        clip-to-shape? (boolean (:clip-to-shape texture))

        on-update-param
        (mf/use-fn
         (mf/deps index on-update)
         (fn [attr value]
           (on-update index attr value)))

        on-update-size-x  (mf/use-fn (mf/deps on-update-param) #(on-update-param :size-x %))
        on-update-size-y  (mf/use-fn (mf/deps on-update-param) #(on-update-param :size-y %))
        on-update-radius  (mf/use-fn (mf/deps on-update-param) #(on-update-param :radius %))
        on-toggle-clip    (mf/use-fn (mf/deps on-update-param)
                                     (fn []
                                       (on-update-param :clip-to-shape (not clip-to-shape?))))

        on-remove            (mf/use-fn (mf/deps index) #(on-remove index))
        on-toggle-visibility (mf/use-fn (mf/deps index) #(on-toggle-visibility index))]

    [:div {:class (stl/css :texture-option)}
     [:div {:class (stl/css :texture-basic)}
      [:div {:class (stl/css :texture-basic-info)}
       [:span {:class (stl/css :texture-basic-label)}
        (tr "workspace.options.texture-options.title")]]
      [:div {:class (stl/css :texture-basic-actions)}
       [:> icon-button* {:variant "ghost"
                         :aria-label (tr "workspace.options.texture-options.toggle-texture")
                         :on-click on-toggle-visibility
                         :icon (if hidden? i/hide i/shown)}]
       [:> icon-button* {:variant "ghost"
                         :aria-label (tr "workspace.options.texture-options.remove-texture")
                         :on-click on-remove
                         :icon i/remove}]]]

     (when-not hidden?
       [:div {:class (stl/css :texture-params)
              :data-testid "texture.params"}
        [:div {:class (stl/css :texture-param-row)}
         [:span {:class (stl/css :texture-param-label)}
          (tr "workspace.options.texture-options.size-x")]
         [:> numeric-input* {:placeholder "--"
                             :min 0
                             :on-change on-update-size-x
                             :value (:size-x texture)}]]
        [:div {:class (stl/css :texture-param-row)}
         [:span {:class (stl/css :texture-param-label)}
          (tr "workspace.options.texture-options.size-y")]
         [:> numeric-input* {:placeholder "--"
                             :min 0
                             :on-change on-update-size-y
                             :value (:size-y texture)}]]
        [:div {:class (stl/css :texture-param-row)}
         [:span {:class (stl/css :texture-param-label)}
          (tr "workspace.options.texture-options.radius")]
         [:> numeric-input* {:placeholder "--"
                             :min 0
                             :on-change on-update-radius
                             :value (:radius texture)}]]
        [:div {:class (stl/css :texture-param-row)}
         [:label {:class (stl/css :texture-clip-toggle)}
          [:input {:type "checkbox"
                   :checked clip-to-shape?
                   :on-change on-toggle-clip}]
          [:span {:class (stl/css :texture-clip-label)}
           (tr "workspace.options.texture-options.clip-to-shape")]]]])]))

(mf/defc texture-menu*
  {::mf/wrap [#(mf/memo' % check-texture-menu-props)]}
  [{:keys [ids type values] :as props}]
  (let [textures       (mf/with-memo [values]
                         (if (= :multiple values)
                           values
                           (not-empty (into [] values))))

        has-texture?    (or (= :multiple textures)
                            (some? (seq textures)))

        show-content*   (mf/use-state true)
        show-content?   (deref show-content*)

        toggle-content  (mf/use-fn #(swap! show-content* not))

        on-add-texture
        (mf/use-fn
         (mf/deps ids)
         (fn []
           ;; First-effect seeding: the section now always mounts (see the
           ;; shape option pages), so textures is nil for an empty slot.
           ;; Guard on (or textures []) so count never throws on nil and the
           ;; first texture can be seeded from a nil :texture slot.
           (when (< (count (or textures [])) texture-max-count)
             (st/emit! (udw/trigger-bounding-box-cloaking ids))
             (st/emit! (dwsh/update-shapes ids #(assoc % :texture [(create-texture)]))))))

        on-remove-all
        (mf/use-fn
         (mf/deps ids)
         (fn []
           (st/emit! (udw/trigger-bounding-box-cloaking ids))
           (st/emit! (dwsh/update-shapes ids #(dissoc % :texture)))))

        on-remove
        (mf/use-fn
         (mf/deps ids)
         (fn [index]
           (st/emit! (udw/trigger-bounding-box-cloaking ids))
           (st/emit! (dwsh/update-shapes ids #(update % :texture (fn [v] (into [] (d/remove-at-index (or v []) index))))))))

        on-toggle-visibility
        (mf/use-fn
         (mf/deps ids)
         (fn [index]
           (st/emit! (udw/trigger-bounding-box-cloaking ids))
           (st/emit! (dwsh/update-shapes ids #(update-in % [:texture index :hidden] not)))))

        on-update
        (mf/use-fn
         (mf/deps ids)
         (fn [index attr value]
           (st/emit! (dwsh/update-shapes ids #(assoc-in % [:texture index attr] value)))))]

    (when-not (= :multiple textures)
      [:section {:class (stl/css :element-set)
                 :aria-label (tr "workspace.options.texture-options.title")}
       [:div {:class (stl/css :element-title)}
        [:> title-bar* {:collapsable  has-texture?
                        :collapsed    (not show-content?)
                        :on-collapsed toggle-content
                        :title        (tr "workspace.options.texture-options.title")
                        :class        (stl/css-case :title-spacing-texture (not has-texture?))}
         (when (and (not has-texture?) (< (count (or textures [])) texture-max-count))
           [:> icon-button*
            {:variant "ghost"
             :aria-label (tr "workspace.options.texture-options.add-texture")
             :on-click on-add-texture
             :icon i/add
             :data-testid "add-texture"}])]]

       (when (and show-content? has-texture?)
         [:div {:class (stl/css :element-set-content)}
          (for [[index texture] (d/enumerate (or textures []))]
            [:> texture-row*
             {:key (dm/str (:id texture))
              :index index
              :texture texture
              :on-update on-update
              :on-remove on-remove
              :on-toggle-visibility on-toggle-visibility}])])])))