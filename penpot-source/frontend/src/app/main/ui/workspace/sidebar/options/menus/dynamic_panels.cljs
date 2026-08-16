;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.options.menus.dynamic-panels
  "Dynamic Panels authoring menu (P1.14).

  Sidebar panel for turning a selected frame into a generic N-state
  container (decoupled from the component system). The user adds named
  states to the frame and toggles which child shapes are visible per
  state. Panel states are persisted as shape plugin-data (see
  data/workspace/dynamic_panels.cljs); at runtime a :set-panel-state
  interaction action switches the frame's active state (see
  viewer/shapes.cljs dispatch)."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.dynamic-panels :as dwdp]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.main.ui.hiccup :as hic]
   [app.util.i18n :refer [tr]]
   [clojure.string :as cstr]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

;; Coral accent (matches the Ovion AI surfaces + theme).
(def ^:private coral "#f28b82")
(def ^:private grey  "#7d7d7d")

(def ^:private coral-chip-style
  {:display "inline-flex"
   :align-items "center"
   :gap "5px"
   :padding "2px 8px"
   :border-radius "999px"
   :background "rgba(242,139,130,0.12)"
   :color coral
   :font-size "11px"
   :font-weight "500"
   :line-height "1.4"})

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

;; Inline Lucide icons (viewBox 0 0 24 24, stroke-width 2, currentColor).
(defn- lucide-icon [children]
  (hic/el (into [:svg {:viewBox "0 0 24 24" :fill "none" :stroke "currentColor"
                       :stroke-width 2 :stroke-linecap "round" :stroke-linejoin "round"
                       :width 16 :height 16 :style {:flex-shrink 0}}]
                children)))

(defn- icon-plus []    (lucide-icon [[:path {:d "M12 5v14"}] [:path {:d "M5 12h14"}]]))
(defn- icon-trash []   (lucide-icon [[:path {:d "M3 6h18"}]
                                    [:path {:d "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6"}]
                                    [:path {:d "M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"}]]))
(defn- icon-panels []  (lucide-icon [[:rect {:x 3 :y 3 :width 7 :height 7 :rx 1}]
                                     [:rect {:x 14 :y 3 :width 7 :height 7 :rx 1}]
                                     [:rect {:x 3 :y 14 :width 7 :height 7 :rx 1}]
                                     [:rect {:x 14 :y 14 :width 7 :height 7 :rx 1}]]))
(defn- icon-eye []     (lucide-icon [[:path {:d "M2 12s3-7 10-7 10 7 10 7-3 7-10 7-10-7-10-7Z"}]
                                    [:circle {:cx 12 :cy 12 :r 3}]]))
(defn- icon-eye-off [] (lucide-icon [[:path {:d "M9.9 4.24A9.1 9.1 0 0 1 12 4c7 0 10 8 10 8a13 13 0 0 1-1.67 2.68"}]
                                    [:path {:d "M6.61 6.61A13 13 0 0 0 2 12s3 8 10 8a9.1 9.1 0 0 0 5.39-1.61"}]
                                    [:path {:d "M14.12 14.12A3 3 0 1 1 9.88 9.88"}]
                                    [:line {:x1 2 :y1 2 :x2 22 :y2 22}]]))

;; Per-frame panel-states ref derived from the workspace page objects.
;; Reads the shape's plugin-data slot so the menu re-renders on edits.
(defn- panel-states-ref
  [shape-id]
  (l/derived
   (fn [page]
     (let [shape (get-in page [:objects shape-id])]
       (dwdp/read-panel-states shape)))
   refs/workspace-page
   =))

(defn- active-state-ref
  [shape-id]
  (l/derived
   (fn [page]
     (let [shape (get-in page [:objects shape-id])]
       (dwdp/read-active-state shape)))
   refs/workspace-page
   =))

(mf/defc state-row*
  [{:keys [shape-id state-name child-ids all-children active? on-toggle-child
           on-remove on-set-active on-rename]}]
  (let [editing* (mf/use-state false)
        editing  (deref editing*)
        name*    (mf/use-state state-name)
        name     (deref name*)

        commit-rename
        (mf/use-fn
         (mf/deps name state-name on-rename)
         (fn []
           (let [nm (cstr/trim name)]
             (when (and (seq nm) (not= nm state-name))
               (on-rename nm)))
           (reset! editing* false)))

        on-change  (mf/use-fn #(reset! name* (.. % -target -value)))
        on-blur    (mf/use-fn commit-rename)
        on-key     (mf/use-fn
                    (fn [e]
                      (when (= "Enter" (.. e -key))
                        (commit-rename))))
        on-edit    (mf/use-fn #(reset! editing* true))]

    [:div {:style {:border-top "1px solid var(--token-color-neutral-200, #e5e5e5)"
                   :padding "6px 0"
                   :margin-top "6px"}}
     [:div {:style {:display "flex" :align-items "center" :gap "6px"}}
      [:span {:style {:flex "1" :font-weight "500" :font-size "12px"
                      :color (when active? coral)}
              :on-click on-set-active}
       (if editing
         [:input {:type "text" :value name
                  :class (stl/css :type-input)
                  :style {:flex "1"}
                  :on-change on-change
                  :on-blur on-blur
                  :on-key-down on-key}]
         state-name)]
      [:button {:type "button" :style coral-btn-style
                :title (tr "workspace.options.dynamic-panels.set-active")
                :on-click on-set-active}
       (icon-eye)]
      [:button {:type "button" :style coral-btn-style
                :title (tr "workspace.options.dynamic-panels.rename")
                :on-click on-edit}
       (lucide-icon [[:path {:d "M12 20h9"}]
                     [:path {:d "M16.5 3.5a2.12 2.12 0 0 1 3 3L7 19l-4 1 1-4Z"}]])]
      [:button {:type "button" :style coral-btn-style
                :title (tr "workspace.options.dynamic-panels.remove")
                :on-click on-remove}
       (icon-trash)]]

     ;; Child visibility toggles
     (when (seq all-children)
       [:div {:style {:padding "4px 0 0 18px"
                      :display "flex"
                      :flex-direction "column"
                      :gap "3px"}}
        (for [child all-children]
          (let [cid  (:id child)
                vis? (some #(= % cid) child-ids)]
            [:div {:key (str cid)
                   :style {:display "flex" :align-items "center" :gap "6px"
                           :font-size "11px" :cursor "pointer"}
                   :on-click #(on-toggle-child state-name cid)}
             [:span {:style {:color (if vis? coral grey)}}
              (if vis? (icon-eye) (icon-eye-off))]
             [:span {:style {:flex "1" :color (if vis? "inherit" grey)}}
              (or (:name child) (str cid))]]))])]))

(mf/defc dynamic-panels-menu*
  [{:keys [shapes]}]
  (let [shape     (first shapes)
        shape-id  (:id shape)
        is-frame? (= :frame (:type shape))]
    (if (not is-frame?)
      ;; Only frames can be dynamic panels.
      nil
      (let [states   (mf/deref (panel-states-ref shape-id))
            active   (mf/deref (active-state-ref shape-id))
            children (when shape-id
                       (let [page (mf/deref refs/workspace-page)
                             objs (:objects page)]
                         (->> (:shapes shape)
                              (keep #(get objs %))
                              (filter (fn [c] (uuid? (:id c)))))))
            has-states? (seq states)

            new-name* (mf/use-state "")
            new-name  (deref new-name*)]

        (when is-frame?
          [:div {:class (stl/css :element-set)}
           [:div {:class (stl/css :element-title)}
            [:> title-bar* {:collapsable true :collapsed false
                            :title (tr "workspace.options.dynamic-panels.title")}]]
           [:div {:class (stl/css :element-set-content)}
            [:div {:class (stl/css :element-group)
                   :style {:display "flex" :flex-direction "column" :gap "6px"}}

             ;; Make-panel CTA when no states yet.
             (if (not has-states?)
               [:div {:style {:display "flex" :align-items "center" :gap "8px"}}
                [:span {:style coral-chip-style} (icon-panels)
                 (tr "workspace.options.dynamic-panels.empty")]
                [:button {:type "button" :style (merge coral-btn-style
                                                       {:width "auto" :padding "0 10px"
                                                        :font-size "11px" :font-weight "500"})
                          :on-click #(st/emit! (dwdp/make-panel {:shape-id shape-id}))}
                 (tr "workspace.options.dynamic-panels.make-panel")]]
               [:<>
                ;; Add state row.
                [:div {:style {:display "flex" :gap "6px" :align-items "center"}}
                 [:input {:type "text" :value new-name
                          :placeholder (tr "workspace.options.dynamic-panels.add-state")
                          :class (stl/css :type-input) :style {:flex "1"}
                          :on-change #(reset! new-name* (.. % -target -value))}]
                 [:button {:type "button" :style coral-btn-style
                           :title (tr "workspace.options.dynamic-panels.add-state")
                           :on-click (fn []
                                       (let [nm (cstr/trim new-name)]
                                         (when (seq nm)
                                           (st/emit! (dwdp/add-panel-state
                                                      {:shape-id shape-id :name nm}))
                                           (reset! new-name* ""))))}
                  (icon-plus)]]

                ;; State rows.
                (for [[state-name child-ids] states]
                  (let [on-toggle-child
                        (mf/use-fn
                         (mf/deps shape-id states)
                         (fn [sname cid]
                           (let [cur (get states sname [])
                                 has (some #(= % cid) cur)
                                 nxt (if has
                                       (vec (remove #(= % cid) cur))
                                       (conj (vec cur) cid))]
                             (st/emit! (dwdp/set-state-children
                                        {:shape-id shape-id :name sname :child-ids nxt})))))
                        on-remove
                        (mf/use-fn
                         (mf/deps shape-id)
                         #(st/emit! (dwdp/remove-panel-state
                                     {:shape-id shape-id :name state-name})))
                        on-set-active
                        (mf/use-fn
                         (mf/deps shape-id)
                         #(st/emit! (dwdp/set-active-panel-state
                                     {:shape-id shape-id :name state-name})))
                        on-rename
                        (mf/use-fn
                         (mf/deps shape-id)
                         #(st/emit! (dwdp/rename-panel-state
                                     {:shape-id shape-id
                                      :old-name state-name
                                      :new-name %})))]
                    [:& state-row*
                     {:key (str state-name)
                      :shape-id shape-id
                      :state-name state-name
                      :child-ids child-ids
                      :all-children children
                      :active? (= active state-name)
                      :on-toggle-child on-toggle-child
                      :on-remove on-remove
                      :on-set-active on-set-active
                      :on-rename on-rename}]))])]

            ;; Hint.
            [:div {:style {:margin-top "8px"
                           :display "flex" :gap "6px" :align-items "flex-start"
                           :color grey :font-size "11px" :line-height "1.4"}}
             [:span {:style {:color coral :margin-top "1px"}} (icon-panels)]
             [:span (tr "workspace.options.dynamic-panels.hint")]]]])))))