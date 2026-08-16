;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.options.menus.repeaters
  "Repeater configurator menu (ALL_APPS_PARITY P1.12).

  A self-contained sidebar menu for binding the selected shape (a text
  shape, or a frame/group with tagged text children) to a named data set
  and choosing which data-set column fills which text field. The config
  is persisted as shape plugin-data `:ovion \"repeater\"` via
  data/workspace/repeaters.cljs. `Apply` runs `apply-repeater` (duplicate
  per row + fill fields); `Clear` runs `clear-repeater` (remove slot +
  generated children). Self-hides unless exactly one shape is selected.

  Field binding: each text shape inside the template carries an
  `:ovion \"field\"` tag (a field key). This menu lists those field keys
  and lets the user map each to a data-set column. Tagging a child text
  field is also done here (select a text child -> set its field key) via
  `dwr/set-field-tag`.

  Byte-identical-when-inactive: no repeater slot -> the shape renders
  normally; the menu only emits changes on explicit user action."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.files.helpers :as cfh]
   [app.main.data.workspace.data-binding :as dwdb]
   [app.main.data.workspace.repeaters :as dwr]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.main.ui.hiccup :as hic]
   [app.util.i18n :refer [tr]]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

;; Coral + grey accents (Ovion theme).
(def ^:private coral "#f28b82")
(def ^:private grey  "#7d7d7d")

(def ^:private coral-btn-style
  {:display "inline-flex"
   :align-items "center"
   :justify-content "center"
   :height "26px"
   :padding "0 10px"
   :border-radius "6px"
   :border "1px solid rgba(242,139,130,0.4)"
   :background "rgba(242,139,130,0.08)"
   :color coral
   :cursor "pointer"
   :font-size "11px"
   :font-weight "500"})

(def ^:private row-style
  {:display "flex" :align-items "center" :gap "6px"})

(def ^:private label-style
  {:font-size "11px" :color grey :width "64px" :flex-shrink "0"})

;; Inline Lucide icons.
(defn- lucide-icon [children]
  (hic/el (into [:svg {:viewBox "0 0 24 24" :fill "none" :stroke "currentColor"
                       :stroke-width 2 :stroke-linecap "round" :stroke-linejoin "round"
                       :width 14 :height 14 :style {:flex-shrink 0}}]
                children)))

(defn- icon-repeat []
  (lucide-icon [[:path {:d "M17 2l4 4-4 4"}]
                [:path {:d "M3 11v-1a4 4 0 0 1 4-4h14"}]
                [:path {:d "M7 22l-4-4 4-4"}]
                [:path {:d "M21 13v1a4 4 0 0 1-4 4H3"}]]))

(defn- icon-trash []
  (lucide-icon [[:path {:d "M3 6h18"}]
                [:path {:d "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6"}]
                [:path {:d "M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"}]]))

(defn- icon-play []
  (lucide-icon [[:polygon {:points "5 3 19 12 5 21 5 3"}]]))

;; --- Refs ------------------------------------------------------------------

(defn- repeater-ref
  [shape-id]
  (l/derived
   (fn [page]
     (let [shape (get-in page [:objects shape-id])]
       (dwr/read-repeater shape)))
   refs/workspace-page
   =))

(defn- data-sets-ref
  []
  (l/derived #(dwdb/read-data-sets %) refs/workspace-data =))

;; --- Helpers ---------------------------------------------------------------

(defn- template-text-fields
  "Return a vector of `[<text-shape-id> <field-key>]` for every text shape
  in the template's subtree (including the template itself if it is a text
  shape) that carries an `:ovion \"field\"` tag. Uses the page objects."
  [objects shape-id]
  (let [ids (cfh/get-children-ids-with-self objects shape-id)]
    (into []
          (keep (fn [id]
                  (let [shape (get objects id)]
                    (when (cfh/text-shape? shape)
                      (when-let [fk (dwr/read-field-tag shape)]
                        [id fk])))))
          ids)))

;; --- Menu ------------------------------------------------------------------

(mf/defc repeaters-menu*
  [{:keys [shapes]}]
  ;; Self-hides unless exactly one shape is selected.
  (when (= 1 (count shapes))
    (let [shape    (first shapes)
          shape-id (:id shape)
          objects  (mf/deref refs/workspace-page-objects)
          cfg      (mf/deref (repeater-ref shape-id))
          sets     (mf/deref (data-sets-ref))
          set-names (->> (keys sets)
                         (map (fn [k] (if (keyword? k) (d/name k) (str k))))
                         sort)
          fields   (template-text-fields objects shape-id)

          ;; Local editing state for the config form (data-set + column
          ;; per field + gap). Seeded from the persisted cfg.
          edit-data-set* (mf/use-state (some-> cfg :data-set d/name))
          edit-cols*     (mf/use-state
                          (into {}
                                (for [[id fk] fields]
                                  [fk (get-in cfg [:fields fk] "")])))
          edit-gap-w*    (mf/use-state (or (:gap-w cfg) 0))
          edit-gap-h*    (mf/use-state (or (:gap-h cfg) 0))

          current-ds-name @edit-data-set*
          current-ds (when (not (str/blank? current-ds-name))
                       (get sets (keyword current-ds-name)))
          headers    (:headers current-ds)

          on-pick-data-set
          (mf/use-fn
           (mf/deps)
           (fn [e]
             (reset! edit-data-set* (.. e -target -value))))

          on-pick-column
          (mf/use-fn
           (mf/deps)
           (fn [field-key e]
             (swap! edit-cols* assoc field-key (.. e -target -value))))

          on-gap
          (mf/use-fn
           (mf/deps)
           (fn [which e]
             (let [v (js/parseInt (.. e -target -value) 10)]
               (case which
                 :w (reset! edit-gap-w* (or v 0))
                 :h (reset! edit-gap-h* (or v 0))
                 nil))))

          on-apply
          (mf/use-fn
           (mf/deps shape-id current-ds-name fields @edit-cols* @edit-gap-w* @edit-gap-h*)
           (fn []
             (let [fields-map (into {}
                                    (keep (fn [[_id fk]]
                                            (let [col (get @edit-cols* fk)]
                                              (when (not (str/blank? col))
                                                [fk col]))))
                                    fields)
                   new-cfg {:data-set (keyword current-ds-name)
                            :fields   fields-map
                            :gap-w    @edit-gap-w*
                            :gap-h    @edit-gap-h*}]
               (st/emit! (dwr/set-repeater {:shape-id shape-id :cfg new-cfg}))
               (st/emit! (dwr/apply-repeater {:shape-id shape-id})))))

          on-clear
          (mf/use-fn
           (mf/deps shape-id)
           (fn []
             (st/emit! (dwr/clear-repeater {:shape-id shape-id}))))]

      [:div {:class (stl/css :element-set)}
       [:div {:class (stl/css :element-title)}
        [:> title-bar* {:collapsable true :collapsed false
                        :title (tr "workspace.options.repeaters.title")}]]
       [:div {:class (stl/css :element-set-content)}
        [:div {:class (stl/css :element-group)
               :style {:display "flex" :flex-direction "column" :gap "8px"}}

         ;; Data-set picker.
         [:div {:style row-style}
          [:span {:style label-style}
           (tr "workspace.options.repeaters.data-set")]
          [:select {:value (or current-ds-name "")
                    :class (stl/css :type-input) :style {:flex "1"}
                    :on-change on-pick-data-set}
           [:option {:value ""} (tr "workspace.options.repeaters.none")]
           (for [nm set-names]
             [:option {:key nm :value nm} nm])]]

         ;; Column binding per field (only when a data set is chosen).
         (when (and (seq fields) (seq headers))
           [:div {:style {:display "flex" :flex-direction "column" :gap "4px"
                          :padding "4px 0"
                          :border-top "1px solid rgba(125,125,125,0.2)"}}
            [:div {:style {:font-size "11px" :color grey}}
             (tr "workspace.options.repeaters.field-binding")]
            (for [[id fk] fields]
              [:div {:key (str id) :style row-style}
               [:span {:style (merge label-style {:width "72px"})
                       :title (str fk)}
                (str fk)]
               [:select {:value (or (get @edit-cols* fk) "")
                         :class (stl/css :type-input) :style {:flex "1"}
                         :on-change #(on-pick-column fk %)}
                [:option {:value ""} (tr "workspace.options.repeaters.no-column")]
                (for [h headers]
                  [:option {:key (str h) :value (str h)} (str h)])]])])

         ;; Gap inputs.
         (when (seq set-names)
           [:div {:style {:display "flex" :gap "8px"}}
            [:div {:style (merge row-style {:flex "1"})}
             [:span {:style label-style} (tr "workspace.options.repeaters.gap-w")]
             [:input {:type "number" :value @edit-gap-w*
                      :class (stl/css :type-input) :style {:flex "1"}
                      :on-change #(on-gap :w %)}]]
            [:div {:style (merge row-style {:flex "1"})}
             [:span {:style label-style} (tr "workspace.options.repeaters.gap-h")]
             [:input {:type "number" :value @edit-gap-h*
                      :class (stl/css :type-input) :style {:flex "1"}
                      :on-change #(on-gap :h %)}]]])

         ;; Apply / Clear.
         (when (and (not (str/blank? current-ds-name)) (seq fields))
           [:div {:style row-style}
            [:button {:type "button"
                      :style (merge coral-btn-style {:background coral :color "#fff"
                                                     :border-color coral :flex "1"})
                      :on-click on-apply}
             [:span {:style {:margin-right "4px"}} (icon-play)]
             (tr "workspace.options.repeaters.apply")]
            (when cfg
              [:button {:type "button" :title (tr "workspace.options.repeaters.clear")
                        :style coral-btn-style :on-click on-clear}
               (icon-trash)])])

         ;; Empty / hint states.
         (when (empty? set-names)
           [:div {:style {:display "flex" :gap "6px" :align-items "flex-start"
                          :color grey :font-size "11px" :line-height "1.4"}}
            [:span {:style {:color coral :margin-top "1px"}} (icon-repeat)]
            [:span (tr "workspace.options.repeaters.no-data-sets")]])

         (when (and (seq set-names) (empty? fields))
           [:div {:style {:color grey :font-size "11px" :line-height "1.4"}}
            (tr "workspace.options.repeaters.no-fields")])]]])))