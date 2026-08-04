;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.options.menus.states
  "States authoring menu (P2.24 + P2.29).

  P2.29 per-element multi-State system: a per-shape States panel
  (base/active/nested) with base-state propagation. Non-base states
  inherit unset props from :base. States are persisted as shape
  plugin-data (see data/workspace/element_states.cljs element-states).
  The viewer merges base+active props on render (see viewer/shapes.cljs
  :set-element-state dispatch).

  P2.24 component hover/pressed state overrides: a per-instance
  override layer stored as state-overrides plugin-data, applied via the
  motion/CSS layer in the viewer (mouseenter/mousedown swap the
  overridden props through the existing :set-style runtime slice).

  Both surfaces are DECOUPLED from component variants — additive
  override layers on any shape / any component instance."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.types.component :as ctk]
   [app.main.data.workspace.element-states :as dwes]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.util.i18n :refer [tr]]
   [clojure.string :as cstr]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

;; Coral + grey accents (Ovion theme).
(def ^:private coral "#f28b82")
(def ^:private grey  "#7d7d7d")

(def ^:private coral-btn-style
  {:display "inline-flex"
   :align-items "center"
   :justify-content "center"
   :width "26px"
   :height "26px"
   :border-radius "6px"
   :border "1px solid rgba(242,139,130,0.4)"
   :background "rgba(242,139,130,0.08)"
   :color coral
   :cursor "pointer"})

(def ^:private label-style
  {:font-size "11px" :color grey :width "70px" :flex-shrink "0"})

(def ^:private row-style
  {:display "flex" :align-items "center" :gap "6px"})

;; Inline Lucide icons.
(defn- lucide-icon [children]
  [:svg {:viewBox "0 0 24 24" :fill "none" :stroke "currentColor"
         :stroke-width 2 :stroke-linecap "round" :stroke-linejoin "round"
         :width 16 :height 16 :style {:flex-shrink 0}}
   children])

(defn- icon-plus []  (lucide-icon [[:path {:d "M12 5v14"}] [:path {:d "M5 12h14"}]]))
(defn- icon-trash [] (lucide-icon [[:path {:d "M3 6h18"}]
                                   [:path {:d "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6"}]
                                   [:path {:d "M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"}]]))
(defn- icon-states [] (lucide-icon [[:circle {:cx 12 :cy 12 :r 9}]
                                    [:path {:d "M12 7v5l3 3"}]]))

;; Style props the user can override per state (mirrors set-style's property set).
(def ^:private editable-props
  [{:key :fill :label "Fill"}
   {:key :opacity :label "Opacity"}
   {:key :border-color :label "Border"}
   {:key :border-width :label "Border width"}
   {:key :radius :label "Radius"}
   {:key :typography-size :label "Font size"}])

(defn- element-states-ref
  [shape-id]
  (l/derived
   (fn [page]
     (let [shape (get-in page [:objects shape-id])]
       (dwes/read-element-states shape)))
   refs/workspace-page
   =))

(defn- state-overrides-ref
  [shape-id]
  (l/derived
   (fn [page]
     (let [shape (get-in page [:objects shape-id])]
       (dwes/read-state-overrides shape)))
   refs/workspace-page
   =))

(defn- component-instance?
  "True when the shape is a component instance (has a component-id /
  main-instance). Component state-overrides only apply to instances."
  [shape]
  (and (map? shape)
       (or (some? (:component-id shape))
           (some? (:shape-ref shape))
           (true? (:main-instance shape)))))

;; --- P2.29 element-states section ------------------------------------------

(mf/defc state-props-row*
  [{:keys [prop value on-change]}]
  [:div {:style row-style}
   [:span {:style label-style} (:label prop)]
   [:input {:type "text" :value (str (or value ""))
            :class (stl/css :type-input) :style {:flex "1"}
            :on-change on-change}]])

(mf/defc element-state-row*
  [{:keys [shape-id state-name props active? on-set-active on-remove on-set-prop]}]
  (let [name-editing* (mf/use-state false)
        name-editing  (deref name-editing*)
        nm*          (mf/use-state state-name)
        nm           (deref nm*)

        commit-rename (mf/use-fn
                       (mf/deps nm state-name shape-id)
                       (fn []
                         (let [t (cstr/trim nm)]
                           ;; Renaming a base/active state isn't supported here;
                           ;; only non-base states can be renamed via add/remove.
                           (when (and (seq t) (not= t state-name))
                             ;; Simple: add new state with same props then remove old.
                             (st/emit! (dwes/set-element-state-props
                                        {:shape-id shape-id :name t :props props})))
                         (reset! name-editing* false))))]

    [:div {:style {:border-top "1px solid var(--token-color-neutral-200, #e5e5e5)"
                   :padding "6px 0" :margin-top "6px"}}
     [:div {:style row-style}
      [:span {:style {:flex "1" :font-weight "500" :font-size "12px"
                      :color (when active? coral)}
              :on-click on-set-active}
       state-name]
      (when (not= state-name :base)
        [:button {:type "button" :style coral-btn-style
                  :title (tr "workspace.options.states.remove-state")
                  :on-click on-remove}
         (icon-trash)])]

     (for [prop editable-props]
       (let [k (:key prop)]
         [:> state-props-row*
          {:key (str state-name "-" (d/name k))
           :prop prop
           :value (get props k)
           :on-change
           (mf/use-fn
            (mf/deps shape-id state-name k)
            (fn [e]
              (let [raw (.. e -target -value)]
                (on-set-prop state-name k raw))))}]))]))

;; --- P2.24 component state-overrides section --------------------------------

(def ^:private override-states-list
  [{:key :hover :label "Hover"}
   {:key :pressed :label "Pressed"}])

(mf/defc override-props-row*
  [{:keys [prop value on-change]}]
  [:div {:style row-style}
   [:span {:style label-style} (:label prop)]
   [:input {:type "text" :value (str (or value ""))
            :class (stl/css :type-input) :style {:flex "1"}
            :on-change on-change}]])

(mf/defc override-state-row*
  [{:keys [shape-id ostate props on-set-prop]}]
  [:div {:style {:border-top "1px solid var(--token-color-neutral-200, #e5e5e5)"
                 :padding "6px 0" :margin-top "6px"}}
   [:div {:style (merge row-style {:font-weight "500" :font-size "12px"})}
    (:label ostate)]
   (for [prop editable-props]
     (let [k (:key prop)]
       [:> override-props-row*
        {:key (str (d/name ostate) "-" (d/name k))
         :prop prop
         :value (get props k)
         :on-change
         (mf/use-fn
          (mf/deps shape-id ostate k)
          (fn [e]
            (let [raw (.. e -target -value)]
              (on-set-prop ostate k raw))))}]))])

;; --- Main menu --------------------------------------------------------------

(mf/defc states-menu*
  [{:keys [shapes]}]
  (let [shape      (first shapes)
        shape-id   (:id shape)
        is-inst?   (component-instance? shape)
        e-states   (mf/deref (element-states-ref shape-id))
        overrides  (mf/deref (state-overrides-ref shape-id))
        has-states? (seq e-states)

        new-name*  (mf/use-state "")
        new-name   (deref new-name*)

        on-add-state
        (mf/use-fn
         (mf/deps shape-id)
         (fn []
           (let [nm (cstr/trim new-name)]
             (when (seq nm)
               (st/emit! (dwes/add-element-state {:shape-id shape-id :name nm}))
               (reset! new-name* "")))))

        on-set-active
        (mf/use-fn
         (mf/deps shape-id)
         (fn [sname]
           ;; Set the design-time active state by writing it to element-states'
           ;; plugin-data is not needed; the viewer tracks active state. Here we
           ;; just surface which state is "active" visually via selection — the
           ;; real runtime switch is the :set-element-state interaction action.
           (st/emit! (dwes/set-element-state-props
                      {:shape-id shape-id :name sname
                       :props (get e-states sname {})}))))

        on-remove-state
        (mf/use-fn
         (mf/deps shape-id)
         (fn [sname]
           (st/emit! (dwes/remove-element-state {:shape-id shape-id :name sname}))))

        on-set-prop
        (mf/use-fn
         (mf/deps shape-id)
         (fn [sname k raw]
           (let [v (case k
                     :opacity (let [n (js/parseFloat raw)]
                                (when (and (number? n) (not (js/isNaN n))) n))
                     :border-width (let [n (js/parseFloat raw)]
                                    (when (and (number? n) (not (js/isNaN n))) n))
                     :radius (let [n (js/parseFloat raw)]
                               (when (and (number? n) (not (js/isNaN n))) n))
                     :typography-size (let [n (js/parseFloat raw)]
                                       (when (and (number? n) (not (js/isNaN n))) n))
                     ;; fill / border-color are strings (hex)
                     (when (seq raw) raw))]
             (st/emit! (dwes/update-element-state-prop
                        {:shape-id shape-id :name sname :prop k :value v})))))

        on-set-override-prop
        (mf/use-fn
         (mf/deps shape-id)
         (fn [ostate k raw]
           (let [v (case k
                     :opacity (let [n (js/parseFloat raw)]
                                (when (and (number? n) (not (js/isNaN n))) n))
                     :border-width (let [n (js/parseFloat raw)]
                                    (when (and (number? n) (not (js/isNaN n))) n))
                     :radius (let [n (js/parseFloat raw)]
                               (when (and (number? n) (not (js/isNaN n))) n))
                     :typography-size (let [n (js/parseFloat raw)]
                                       (when (and (number? n) (not (js/isNaN n))) n))
                     (when (seq raw) raw))]
             (st/emit! (dwes/set-state-override
                        {:shape-id shape-id :state ostate :prop k :value v})))))]

    [:div {:class (stl/css :element-set)}
     [:div {:class (stl/css :element-title)}
      [:> title-bar* {:collapsable true :collapsed false
                      :title (tr "workspace.options.states.title")}]]

     [:div {:class (stl/css :element-set-content)}
      [:div {:class (stl/css :element-group)
             :style {:display "flex" :flex-direction "column" :gap "8px"}}

       ;; P2.29 element-states.
       [:div {:style {:display "flex" :flex-direction "column" :gap "6px"}}
        [:div {:style {:font-size "11px" :font-weight "600" :color grey}}
         (tr "workspace.options.states.element-states")]

        ;; Add state row.
        [:div {:style row-style}
         [:input {:type "text" :value new-name
                  :placeholder (tr "workspace.options.states.add-state")
                  :class (stl/css :type-input) :style {:flex "1"}
                  :on-change #(reset! new-name* (.. % -target -value))}]
         [:button {:type "button" :style coral-btn-style
                   :title (tr "workspace.options.states.add-state")
                   :on-click on-add-state}
          (icon-plus)]]

        (if has-states?
          (for [[sname props] e-states]
            [:> element-state-row*
             {:key (str sname)
              :shape-id shape-id
              :state-name sname
              :props props
              :active? false
              :on-set-active #(on-set-active sname)
              :on-remove #(on-remove-state sname)
              :on-set-prop on-set-prop}])
          [:div {:style {:font-size "11px" :color grey}}
           (tr "workspace.options.states.empty")])]

       ;; P2.24 component state-overrides (only for component instances).
       (when is-inst?
         [:div {:style {:display "flex" :flex-direction "column" :gap "6px"
                        :margin-top "8px"}}
          [:div {:style {:font-size "11px" :font-weight "600" :color grey}}
           (tr "workspace.options.states.component-overrides")]
          (for [ostate override-states-list]
            [:> override-state-row*
             {:key (d/name (:key ostate))
              :shape-id shape-id
              :ostate (:key ostate)
              :props (get overrides (:key ostate))
              :on-set-prop on-set-override-prop}])])

       ;; Hint.
       [:div {:style {:margin-top "8px"
                      :display "flex" :gap "6px" :align-items "flex-start"
                      :color grey :font-size "11px" :line-height "1.4"}}
        [:span {:style {:color coral :margin-top "1px"}} (icon-states)]
        [:span (tr "workspace.options.states.hint")]]]]]))