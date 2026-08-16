;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.options.menus.data-binding
  "Data sets menu (ALL_APPS_PARITY P1.12 / P2.06).

  A self-contained sidebar menu for importing CSV files as named data
  sets and managing them. CSV import uses the browser FileReader API
  (readAsText) + the pure-CLJS parser in data/workspace/data_binding.cljs.
  Data sets persist on file-level plugin-data `:ovion \"data-sets\"` (one
  undo per import / rename / delete). Mounted in the right sidebar when
  nothing is selected (file-level concern) — see the options.cljs mount
  point reported to the lead.

  Byte-identical-when-inactive: with no data-sets slot, the menu shows an
  empty hint and emits zero changes; the file renders exactly as today."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.main.data.workspace.data-binding :as dwdb]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.main.ui.hiccup :as hic]
   [app.util.i18n :refer [tr]]
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

;; Inline Lucide icons (stroke-width 2, currentColor).
(defn- lucide-icon [children]
  (hic/el (into [:svg {:viewBox "0 0 24 24" :fill "none" :stroke "currentColor"
                       :stroke-width 2 :stroke-linecap "round" :stroke-linejoin "round"
                       :width 14 :height 14 :style {:flex-shrink 0}}]
                children)))

(defn- icon-upload []
  (lucide-icon [[:path {:d "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"}]
                [:path {:d "M17 8l-5-5-5 5"}]
                [:path {:d "M12 3v12"}]]))

(defn- icon-trash []
  (lucide-icon [[:path {:d "M3 6h18"}]
                [:path {:d "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6"}]
                [:path {:d "M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"}]]))

(defn- icon-edit []
  (lucide-icon [[:path {:d "M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"}]
                [:path {:d "M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"}]]))

(defn- icon-table []
  (lucide-icon [[:rect {:x "3" :y "3" :width "18" :height "18" :rx "2"}]
                [:path {:d "M3 9h18"}]
                [:path {:d "M3 15h18"}]
                [:path {:d "M9 3v18"}]
                [:path {:d "M15 3v18"}]]))

;; --- Data-sets ref ---------------------------------------------------------

(defn- data-sets-ref
  []
  (l/derived #(dwdb/read-data-sets %) refs/workspace-data =))

;; --- CSV file import -------------------------------------------------------

(mf/defc data-binding-menu*
  [{:keys [shapes] :as props}]
  ;; This is a FILE-level menu — `shapes` is accepted for mount-site
  ;; uniformity but ignored. Self-contained: derefs the file-data ref.
  (let [sets      (mf/deref (data-sets-ref))
        set-names (->> (keys sets)
                       (map (fn [k] (if (keyword? k) (d/name k) (str k))))
                       sort)
        ;; Local UI state.
        importing?* (mf/use-state false)
        import-msg* (mf/use-state nil)
        rename-id*  (mf/use-state nil)
        rename-val* (mf/use-state "")

        on-file
        (mf/use-fn
         (mf/deps)
         (fn [e]
           (let [file (-> e .-target .-files (aget 0))]
             (when (some? file)
               (reset! importing?* true)
               (reset! import-msg* nil)
               (let [reader (js/FileReader.)]
                 (set! (.-onload reader)
                       (fn [ev]
                         (let [text (-> ev .-target .-result)]
                           (st/emit! (dwdb/import-csv
                                      {:name (or (.-name file) "dataset")
                                       :csv-string text}))
                           (reset! importing?* false)
                           (reset! import-msg*
                                   (tr "workspace.options.data-binding.imported")))))
                 (set! (.-onerror reader)
                       (fn [_]
                         (reset! importing?* false)
                         (reset! import-msg*
                                 (tr "workspace.options.data-binding.import-error"))))
                 (.readAsText reader file))))))

        on-start-rename
        (mf/use-fn
         (mf/deps)
         (fn [name]
           (reset! rename-id* name)
           (reset! rename-val* name)))

        on-commit-rename
        (mf/use-fn
         (mf/deps)
         (fn [old-name]
           (let [new-name @rename-val*]
             (when (not= old-name new-name)
               (st/emit! (dwdb/rename-data-set
                          {:old-name old-name :new-name new-name})))
             (reset! rename-id* nil)
             (reset! rename-val* ""))))

        on-cancel-rename
        (mf/use-fn
         (mf/deps)
         (fn []
           (reset! rename-id* nil)
           (reset! rename-val* "")))

        on-delete
        (mf/use-fn
         (mf/deps)
         (fn [name]
           (st/emit! (dwdb/delete-data-set {:name name}))))]

    [:div {:class (stl/css :element-set)}
     [:div {:class (stl/css :element-title)}
      [:> title-bar* {:collapsable true :collapsed false
                      :title (tr "workspace.options.data-binding.title")}]]
     [:div {:class (stl/css :element-set-content)}
      [:div {:class (stl/css :element-group)
             :style {:display "flex" :flex-direction "column" :gap "8px"}}

       ;; Import dropzone (click -> hidden file input).
       [:div {:style {:display "flex" :gap "6px" :align-items "center"}}
        [:label {:style (merge coral-btn-style {:flex "1" :cursor "pointer"})
                 :title (tr "workspace.options.data-binding.import-hint")}
         [:span {:style {:margin-right "4px"}} (icon-upload)]
         (if @importing?*
           (tr "workspace.options.data-binding.importing")
           (tr "workspace.options.data-binding.import"))
         [:input {:type "file" :accept ".csv,text/csv"
                  :style {:display "none"}
                  :on-change on-file}]]]

       (when @import-msg*
         [:div {:style {:font-size "11px" :color grey}}
          @import-msg*])

       ;; Data-set list.
       (when (seq set-names)
         [:div {:style {:display "flex" :flex-direction "column" :gap "4px"
                        :padding-top "4px"
                        :border-top "1px solid rgba(125,125,125,0.2)"}}])

       (for [name set-names]
         (let [dataset (get sets (keyword name))
               n-rows  (count (:rows dataset))]
           [:div {:key name :style (merge row-style
                                          {:justify-content "space-between"
                                           :padding "4px 0"})}
            (if (= @rename-id* name)
              ;; Rename inline.
              [:div {:style row-style}
               [:input {:type "text" :value @rename-val*
                        :class (stl/css :type-input)
                        :style {:flex "1" :font-size "11px"}
                        :on-change #(reset! rename-val* (.. % -target -value))
                        :on-blur #(on-commit-rename name)
                        :on-key-down (fn [e]
                                       (case (.-key e)
                                         "Enter" (on-commit-rename name)
                                         "Escape" (on-cancel-rename)
                                         nil))}]]
              [:div {:style {:display "flex" :align-items "center" :gap "6px"
                             :flex "1" :min-width "0"}}
               [:span {:style {:color coral :flex-shrink 0}} (icon-table)]
               [:span {:style {:font-size "11px" :color "#e0e0e0"
                               :overflow "hidden" :text-overflow "ellipsis"
                               :white-space "nowrap"}}
                name]
               [:span {:style {:font-size "10px" :color grey :flex-shrink 0}}
                (str n-rows " " (tr "workspace.options.data-binding.rows"))]])
            [:div {:style {:display "flex" :gap "4px" :flex-shrink 0}}
             [:button {:type "button" :title (tr "workspace.options.data-binding.rename")
                       :style (merge coral-btn-style
                                    {:height "24px" :padding "0 6px" :border "none"
                                     :background "transparent"})
                       :on-click #(on-start-rename name)}
              (icon-edit)]
             [:button {:type "button" :title (tr "workspace.options.data-binding.delete")
                       :style (merge coral-btn-style
                                    {:height "24px" :padding "0 6px" :border "none"
                                     :background "transparent"})
                       :on-click #(on-delete name)}
              (icon-trash)]]]))

       ;; Empty hint.
       (when (empty? set-names)
         [:div {:style {:display "flex" :gap "6px" :align-items "flex-start"
                        :color grey :font-size "11px" :line-height "1.4"}}
          [:span {:style {:color coral :margin-top "1px"}} (icon-table)]
          [:span (tr "workspace.options.data-binding.empty")]])]]]))