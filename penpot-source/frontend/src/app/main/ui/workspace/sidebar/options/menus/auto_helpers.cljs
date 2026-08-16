;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.options.menus.auto-helpers
  "Auto smart helpers menu (ALL_APPS_PARITY P1.07).

  Sidebar panel listing the four Lunacy-style ambient-automation toggles
  (auto shape color, auto z-index, auto text color, auto-refresh of
  generated content on duplicate). Each toggle is a per-shape boolean
  persisted through the changes pipeline as shape plugin-data (see
  `data/workspace/auto_helpers.cljs`). A coral 'Apply now' button emits
  `apply-auto-helpers`, which runs the enabled heuristics on the current
  selection in one undo transaction.

  Mirrors the structure of `menus/notes` (the widget-notes panel): a
  collapsible title-bar section inside `.element-set`, coral #f28b82
  accent, inline Lucide icons (viewBox 0 0 24 24, stroke-width 2,
  currentColor), reduced-motion friendly."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.main.data.workspace.auto-helpers :as dwah]
   [app.main.store :as st]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.main.ui.hiccup :as hic]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

;; --- Inline Lucide icons (viewBox 0 0 24 24, stroke-width 2, currentColor) --

(defn- lucide-icon
  [children]
  (hic/el (into [:svg {:viewBox "0 0 24 24"
                       :fill "none"
                       :stroke "currentColor"
                       :stroke-width 2
                       :stroke-linecap "round"
                       :stroke-linejoin "round"
                       :width 14
                       :height 14
                       :style {:flex-shrink 0}}]
                children)))

;; palette / paintbrush — auto shape color
(defn- icon-auto-color []
  (lucide-icon [[:path {:d "M19 11h-1V7a2 2 0 0 0-2-2H4a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-4h1"}]
                [:path {:d "M6 9h4"}]
                [:path {:d "M6 13h4"}]
                [:path {:d "M6 17h2"}]
                [:circle {:cx 16 :cy 14 :r 3}]]))

;; layers / bring-to-front — auto z-index
(defn- icon-auto-z-index []
  (lucide-icon [[:rect {:x 3 :y 3 :width 14 :height 14 :rx 2}]
                [:path {:d "M21 7v12a2 2 0 0 1-2 2H7"}]]))

;; type / contrast — auto text color
(defn- icon-auto-text-color []
  (lucide-icon [[:path {:d "M4 7V5h16v2"}]
                [:path {:d "M9 19h6"}]
                [:path {:d "M12 5v14"}]]))

;; refresh-cw — auto-refresh on duplicate
(defn- icon-auto-refresh []
  (lucide-icon [[:path {:d "M21 12a9 9 0 1 1-3-6.7"}]
                [:path {:d "M21 3v6h-6"}]]))

;; sparkles / wand — Apply now
(defn- icon-apply []
  (lucide-icon [[:path {:d "M12 3l1.9 5.1L19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9z"}]
                [:path {:d "M19 15l.8 2.2L22 18l-2.2.8L19 21l-.8-2.2L16 18l2.2-.8z"}]]))

;; --- Coral accent (Ovion brand) --------------------------------------------

(def ^:private coral "#f28b82")
(def ^:private neutral-600 "var(--token-color-neutral-600, #7d7d7d)")

;; --- Toggle row config ------------------------------------------------------

(def ^:private toggle-config
  "Ordered vector of {:key :label-key :icon} for each auto-helper toggle.
  The label-key is an i18n key (see en.po wiring)."
  [{:key :auto-color      :label-key "workspace.options.auto-helpers.toggle.auto-color"
    :icon icon-auto-color}
   {:key :auto-z-index    :label-key "workspace.options.auto-helpers.toggle.auto-z-index"
    :icon icon-auto-z-index}
   {:key :auto-text-color :label-key "workspace.options.auto-helpers.toggle.auto-text-color"
    :icon icon-auto-text-color}
   {:key :auto-refresh    :label-key "workspace.options.auto-helpers.toggle.auto-refresh"
    :icon icon-auto-refresh}])

;; --- Component --------------------------------------------------------------

(mf/defc auto-helpers-menu*
  "Renders the four auto-helper toggle rows + a coral 'Apply now' button.
  Toggles are read from the first selected shape's plugin-data slot and
  edits apply to all selected shapes. The Apply button emits
  `dwah/apply-auto-helpers` for the current selection."
  [{:keys [shapes]}]
  (let [first-shape (first shapes)
        shape-ids   (mf/use-memo (mf/deps shapes)
                                 #(into [] (keep :id) shapes))

        toggles     (dwah/read-auto-helpers first-shape)

        open*       (mf/use-state true)
        open?       (deref open*)
        toggle-open (mf/use-fn #(swap! open* not))

        on-toggle
        (mf/use-fn
         (mf/deps shape-ids)
         (fn [toggle-key current-val]
           (st/emit! (dwah/toggle-auto-helper
                      {:shape-ids shape-ids
                       :toggle-key toggle-key
                       :enabled? (not current-val)}))))

        on-apply
        (mf/use-fn
         (mf/deps shape-ids)
         (fn []
           (st/emit! (dwah/apply-auto-helpers {:shape-ids shape-ids}))))]

    [:div {:class (stl/css :element-set)}
     [:div {:class (stl/css :element-title)}
      [:> title-bar* {:collapsable true
                      :collapsed (not open?)
                      :on-collapsed toggle-open
                      :title (tr "workspace.options.auto-helpers.title")}]]

     (when open?
       [:div {:class (stl/css :element-set-content)}
        ;; Toggle rows
        [:div {:class (stl/css :element-group)
               :style {:display "flex"
                       :flex-direction "column"
                       :gap "6px"}}
         (for [{:keys [key label-key icon]} toggle-config]
           (let [enabled (boolean (get toggles key))]
             [:label {:key (d/name key)
                      :class (stl/css :auto-helpers-row)}
              [:span {:class (stl/css :auto-helpers-label)}
               [:span {:style {:color coral :display "inline-flex"}}
                (icon)]
               (tr label-key)]
              [:input {:type "checkbox"
                       :class (stl/css :auto-helpers-checkbox)
                       :checked enabled
                       :on-change #(on-toggle key enabled)}]]))]

        ;; Apply now button
        [:button {:type "button"
                  :class (stl/css :auto-helpers-apply-btn)
                  :on-click on-apply}
         [:span {:style {:display "inline-flex"
                         :align-items "center"
                         :gap "6px"}}
          [:span {:style {:color coral :display "inline-flex"}}
           (icon-apply)]
          (tr "workspace.options.auto-helpers.apply-now")]]

        ;; Hint
        [:div {:style {:margin-top "8px"
                       :display "flex"
                       :gap "6px"
                       :align-items "flex-start"
                       :color neutral-600
                       :font-size "11px"
                       :line-height "1.4"}}
         [:span {:style {:color coral :margin-top "1px"}} (icon-auto-color)]
         [:span (tr "workspace.options.auto-helpers.hint")]]])]))