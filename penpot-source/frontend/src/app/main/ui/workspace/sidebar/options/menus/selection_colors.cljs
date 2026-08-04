;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.options.menus.selection-colors
  "Selection Colors panel (ALL_APPS_PARITY P2.33).

  When two or more layers are selected, aggregates every fill + stroke
  color across the selection (data/workspace/selection_colors.cljs) and
  shows them sortable by frequency or by color. Clicking a row opens the
  existing color picker (the same `:colorpicker` modal used by
  color_row.cljs / fill.cljs) to choose a replacement; the picked color
  is emitted via `dwsc/replace-color`, which rewrites every matching
  fill/stroke across the selected shapes in one undo transaction.

  Mounted only for multi-selection (`>= 2` selected); the per-shape
  fill.cljs / stroke.cljs menus cover single selection. Coral #f28b82
  is the Ovion brand accent; Lucide icons inline (stroke-width 2,
  currentColor); reduced-motion users get instant transitions."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.main.data.modal :as modal]
   [app.main.data.workspace.selection-colors :as dwsc]
   [app.main.store :as st]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

;; --- Inline Lucide icons (viewBox 0 0 24 24, stroke-width 2, currentColor) --

(defn- lucide-icon
  [children]
  [:svg {:viewBox "0 0 24 24"
         :fill "none"
         :stroke "currentColor"
         :stroke-width 2
         :stroke-linecap "round"
         :stroke-linejoin "round"
         :width 14
         :height 14
         :style {:flex-shrink 0}}
   children])

(defn- icon-palette []    (lucide-icon [[:circle {:cx 13.5 :cy 6.5 :r 0.5 :fill "currentColor"}]
                                        [:circle {:cx 17.5 :cy 10.5 :r 0.5 :fill "currentColor"}]
                                        [:circle {:cx 8.5 :cy 7.5 :r 0.5 :fill "currentColor"}]
                                        [:circle {:cx 6.5 :cy 12.5 :r 0.5 :fill "currentColor"}]
                                        [:path {:d "M12 2C6.5 2 2 6.5 2 12s4.5 10 10 10c.926 0 1.648-.746 1.648-1.688 0-.437-.18-.835-.437-1.125-.29-.289-.438-.652-.438-1.125a1.64 1.64 0 0 1 1.668-1.668h1.996c3.051 0 5.555-2.503 5.555-5.554C21.965 6.012 17.461 2 12 2z"}]]))

(defn- icon-paintbrush [] (lucide-icon [[:path {:d "M9.06 11.9l8.07-8.06a2.85 2.85 0 1 1 4.03 4.03l-8.06 8.08"}]
                                        [:path {:d "M7.07 14.94c-1.66 0-3 1.35-3 3.02 0 1.33-2.5 1.52-2 2.02 1.08 1.1 2.49 2.02 4 2.02 2.2 0 4-1.8 4-4.04a3.01 3.01 0 0 0-3-3.02z"}]]))

;; --- Coral accent (Ovion brand) --------------------------------------------

(def ^:private coral "#f28b82")
(def ^:private neutral-600 "var(--token-color-neutral-600, #7d7d7d)")

;; --- Swatch background helpers ---------------------------------------------

(defn- gradient-css
  "Build a CSS `linear-gradient(...)` string from a gradient map's stops
  so the swatch previews the gradient. Falls back to the first stop hex
  when the stops vector is empty/missing."
  [gradient hex]
  (let [stops (:stops gradient)]
    (if (seq stops)
      (let [parts (map (fn [s]
                         (str (:color s)
                              " "
                              (* (or (:offset s) 0) 100)
                              "%"))
                       stops)]
        (str "linear-gradient(90deg, " (str/join ", " parts) ")"))
      (or hex "#000000"))))

(defn- swatch-background
  "Return a CSS `background` value for an aggregated entry."
  [{:keys [kind hex gradient]}]
  (if (= kind :gradient)
    (gradient-css gradient hex)
    (or hex "#000000")))

;; --- Component --------------------------------------------------------------

(mf/defc selection-colors-menu*
  "Selection Colors panel. `shapes` is the vector of selected shape maps
  passed by the options panel (same prop shape as notes-menu*). Hidden
  for single selection (count < 2) — returns nil so the menu occupies no
  space; the per-shape fill/stroke menus cover that case."
  [{:keys [shapes]}]
  (let [shape-ids (mf/use-memo (mf/deps shapes)
                               #(into [] (keep :id) shapes))
        n-selected (count shape-ids)]

    ;; Mount ONLY for multi-selection (>= 2). Single selection is covered
    ;; by the per-shape fill.cljs / stroke.cljs menus.
    (when (>= n-selected 2)
      (let [;; Build an id->shape map for the pure aggregator. `shapes`
            ;; already carries the current fills/strokes so no store read
            ;; is needed for aggregation.
            objects   (mf/use-memo (mf/deps shapes)
                                   #(into {} (map (juxt :id identity)) shapes))

            raw-entries (mf/use-memo (mf/deps objects shape-ids)
                                     #(dwsc/aggregate-colors objects shape-ids))

            sort-mode* (mf/use-state :by-frequency)
            sort-mode  (deref sort-mode*)

            entries   (mf/use-memo (mf/deps raw-entries sort-mode)
                                   #(dwsc/sort-colors sort-mode raw-entries))

            has-colors? (seq entries)

            open*     (mf/use-state true)
            open?     (deref open*)
            toggle    (mf/use-fn #(swap! open* not))

            set-by-frequency
            (mf/use-fn (fn [] (reset! sort-mode* :by-frequency)))

            set-by-color
            (mf/use-fn (fn [] (reset! sort-mode* :by-color)))

            open-picker
            (mf/use-fn
             (mf/deps shape-ids)
             (fn [entry event]
               (let [cpos    (dom/get-client-position event)
                     type    (:type entry)
                     old-key (:color entry)
                     old-kind (:kind entry)
                     seed    (if (= :gradient old-kind)
                               {:gradient (:gradient entry)
                                :opacity (:opacity entry 1)}
                               {:color (:hex entry)
                                :opacity (:opacity entry 1)})
                     props   {:x (:x cpos)
                              :y (:y cpos)
                              :on-change
                              (fn [color]
                                (st/emit!
                                 (dwsc/replace-color
                                  {:shape-ids shape-ids
                                   :type type
                                   :old-key old-key
                                   :old-kind old-kind
                                   :new-color color})))
                              :origin :sidebar
                              :data seed}]
                 (modal/show! :colorpicker props))))]

        [:div {:class (stl/css :element-set)}
         [:div {:class (stl/css :element-title)}
          [:> title-bar* {:collapsable has-colors?
                          :collapsed (not open?)
                          :on-collapsed toggle
                          :title (tr "workspace.options.selection-colors.title")}]]


         (when open?
           [:div {:class (stl/css :element-set-content)}
            ;; Header: palette chip + sort toggle (Frequency / Color)
            [:div {:class (stl/css :selection-colors-header)}
             [:span {:class (stl/css :selection-colors-chip)}
              (icon-palette)
              (tr "workspace.options.selection-colors.label")]
             [:div {:class (stl/css :selection-colors-sort-toggle)
                    :role "group"
                    :aria-label (tr "workspace.options.selection-colors.sort.aria")}
              [:button {:type "button"
                        :class (stl/css-case :selection-colors-sort-btn true
                                             :selection-colors-sort-btn-active
                                             (= sort-mode :by-frequency))
                        :aria-pressed (= sort-mode :by-frequency)
                        :on-click set-by-frequency}
               (tr "workspace.options.selection-colors.sort.frequency")]
              [:button {:type "button"
                        :class (stl/css-case :selection-colors-sort-btn true
                                             :selection-colors-sort-btn-active
                                             (= sort-mode :by-color))
                        :aria-pressed (= sort-mode :by-color)
                        :on-click set-by-color}
               (tr "workspace.options.selection-colors.sort.color")]]]

            (if has-colors?
              ;; Scrollable list of swatch rows.
              [:div {:class (stl/css :selection-colors-list)
                     :role "list"}
               (for [entry entries]
                 (let [{:keys [color kind hex type count]} entry
                       type-label (case type
                                    :fill   (tr "workspace.options.selection-colors.type.fill")
                                    :stroke (tr "workspace.options.selection-colors.type.stroke"))]
                   [:button {:key (str (name type) "-" color)
                             :type "button"
                             :class (stl/css :selection-colors-row)
                             :role "listitem"
                             :aria-label (tr "workspace.options.selection-colors.row.aria"
                                             type-label (if (= kind :gradient)
                                                          (tr "workspace.options.selection-colors.kind.gradient")
                                                          hex)
                                             count)
                             :on-click #(open-picker entry %)}
                    [:span {:class (stl/css :selection-colors-swatch)
                            :style {:background (swatch-background entry)}
                            :aria-hidden true}]
                    [:span {:class (stl/css :selection-colors-type-chip)}
                     type-label]
                    [:span {:class (stl/css :selection-colors-count)}
                     count]]))]

              ;; Empty state.
              [:div {:class (stl/css :selection-colors-empty)}
               [:span {:style {:color coral :margin-bottom "4px"}}
                (icon-paintbrush)]
               [:span (tr "workspace.options.selection-colors.empty")]])

            ;; Hint
            [:div {:class (stl/css :selection-colors-hint)}
             [:span {:style {:color coral :margin-top "1px"}} (icon-paintbrush)]
             [:span (tr "workspace.options.selection-colors.hint")]]])]))))