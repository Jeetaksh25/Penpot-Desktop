;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.options.menus.ds-versions
  "DS Versions menu (ALL_APPS_PARITY P2.15).

  Sidebar panel for capturing named snapshots of the current file's
  component library, listing them, setting an active version pointer,
  and comparing a snapshot against the current library. Snapshots are
  persisted on file-data plugin-data (see
  data/workspace/ds-versions.cljs); the active-version pointer and a
  compare summary render inline (no modal).

  Lucide icons are inlined (viewBox 0 0 24 24, stroke-width 2,
  currentColor). Coral accent #f28b82 for chips, the create button, and
  the active pill; grey #7d7d7d for secondary text. i18n keys are
  placeholders the lead adds to en.po."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.workspace.ds-versions :as ddv]
   [app.main.refs :as refs]
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

(defn- icon-branch []
  ;; A git-branch-ish icon to label the section chip.
  (lucide-icon [[:line {:x1 6 :y1 3 :x2 6 :y2 15}]
                [:circle {:cx 18 :cy 18 :r 3}]
                [:circle {:cx 6 :cy 18 :r 3}]
                [:path {:d "M18 15a9 9 0 0 1-9-9"}]]))

(defn- icon-plus []
  (lucide-icon [[:path {:d "M12 5v14"}] [:path {:d "M5 12h14"}]]))

(defn- icon-check []
  (lucide-icon [[:path {:d "M20 6 9 17l-5-5"}]]))

(defn- icon-diff []
  (lucide-icon [[:path {:d "M12 3v18"}]
                [:path {:d "M5 8h14"}]
                [:path {:d "m5 14 7 7 7-7"}]
                [:path {:d "m5 10 7-7 7 7"}]]))

;; --- Coral accent (Ovion brand) --------------------------------------------

(def ^:private coral "#f28b82")
(def ^:private neutral-600 "var(--token-color-neutral-600, #7d7d7d)")

(def ^:private coral-chip-style
  {:display "inline-flex"
   :align-items "center"
   :gap "6px"
   :padding "2px 8px"
   :border-radius "999px"
   :background "rgba(242,139,130,0.12)"
   :color coral
   :font-size "11px"
   :font-weight "500"
   :line-height "1.4"})

(def ^:private active-pill-style
  {:display "inline-flex"
   :align-items "center"
   :gap "4px"
   :padding "1px 8px"
   :border-radius "999px"
   :background coral
   :color "#fff"
   :font-size "10px"
   :font-weight "600"
   :line-height "1.4"})

(def ^:private coral-btn-style
  {:display "inline-flex"
   :align-items "center"
   :gap "4px"
   :padding "4px 10px"
   :border-radius "6px"
   :background coral
   :color "#fff"
   :font-size "11px"
   :font-weight "600"
   :line-height "1.4"
   :border "none"
   :cursor "pointer"})

(def ^:private ghost-btn-style
  {:display "inline-flex"
   :align-items "center"
   :gap "4px"
   :padding "4px 10px"
   :border-radius "6px"
   :background "transparent"
   :color neutral-600
   :font-size "11px"
   :font-weight "500"
   :line-height "1.4"
   :border "1px solid var(--input-border-color)"
   :cursor "pointer"})

;; --- Component --------------------------------------------------------------

(mf/defc ds-versions-menu*
  [_props]
  ;; Mounted unconditionally (like plugin-center-menu*); reads the
  ;; current file-data via the derived `refs/workspace-data` ref.
  (let [file-data (mf/deref refs/workspace-data)
        versions  (ddv/read-ds-versions file-data)
        active-id (ddv/read-active-version file-data)

        open*   (mf/use-state true)
        open?   (deref open*)
        toggle  (mf/use-fn #(swap! open* not))

        name*   (mf/use-state "")
        name-val (deref name*)
        on-name (mf/use-fn (fn [e]
                             (reset! name* (.. e -target -value))))

        on-create
        (mf/use-fn
         (mf/deps name-val)
         (fn []
           (st/emit! (ddv/create-snapshot {:name name-val}))
           (reset! name* "")))

        ;; id -> diff map, so the compare summary persists across
        ;; re-renders until the user toggles it off.
        diffs*  (mf/use-state {})
        diffs   (deref diffs*)

        on-compare
        (mf/use-fn
         (mf/deps file-data)
         (fn [snap]
           (let [sid (:id snap)]
             (if (get diffs sid)
               (swap! diffs* dissoc sid)
               (let [d (ddv/snapshot-diff file-data snap)]
                 (swap! diffs* assoc sid d))))))

        on-set-active
        (mf/use-fn
         (fn [snap]
           (st/emit! (ddv/set-active-version {:id (:id snap)}))))]

    [:div {:class (stl/css :element-set)}
     [:div {:class (stl/css :element-title)}
      [:> title-bar* {:collapsable true
                      :collapsed (not open?)
                      :on-collapsed toggle
                      :title (tr "workspace.options.ds-versions.title")}]]

     (when open?
       [:div {:class (stl/css :element-set-content)}
        ;; Section chip header
        [:div {:style {:display "flex"
                       :align-items "center"
                       :gap "8px"
                       :margin-bottom "6px"}}
         [:span {:style coral-chip-style}
          (icon-branch)
          (tr "workspace.options.ds-versions.section")]]

        ;; Create version row
        [:div {:class (stl/css :ds-versions-create)
               :style {:display "flex"
                       :align-items "center"
                       :gap "6px"
                       :margin-bottom "8px"}}
         [:input {:type "text"
                  :class (stl/css :ds-versions-name-input)
                  :placeholder (tr "workspace.options.ds-versions.name")
                  :value name-val
                  :on-change on-name
                  :on-key-down #(when (= (.. % -key) "Enter")
                                  (on-create))}]
         [:button {:type "button"
                   :style coral-btn-style
                   :on-click on-create}
          (icon-plus)
          (tr "workspace.options.ds-versions.create")]]

        ;; Hint line
        [:div {:style {:margin-bottom "8px"
                       :color neutral-600
                       :font-size "11px"
                       :line-height "1.4"}}
         (tr "workspace.options.ds-versions.hint")]

        ;; List
        (if (seq versions)
          [:div {:class (stl/css :ds-versions-list)
                 :style {:display "flex"
                         :flex-direction "column"
                         :gap "6px"}}
           (for [snap versions]
             (let [sid      (:id snap)
                   active?  (:active snap)
                   diff     (get diffs sid)]
               [:div {:key (str sid)
                      :class (stl/css :ds-versions-row)
                      :style {:display "flex"
                              :flex-direction "column"
                              :gap "4px"
                              :padding "8px"
                              :border "1px solid var(--input-border-color)"
                              :border-radius "6px"}}
                ;; name + active pill
                [:div {:style {:display "flex"
                               :align-items "center"
                               :justify-content "space-between"
                               :gap "8px"}}
                 [:span {:style {:font-size "12px"
                                 :font-weight "600"
                                 :color "var(--color-foreground-primary)"}}
                  (or (:name snap) (tr "workspace.options.ds-versions.name"))]
                 (when active?
                   [:span {:style active-pill-style}
                    (icon-check)
                    (tr "workspace.options.ds-versions.current")])]
                ;; meta row
                [:div {:style {:display "flex"
                               :align-items "center"
                               :gap "8px"
                               :color neutral-600
                               :font-size "11px"}}
                 [:span (tr "workspace.options.ds-versions.timestamp")]
                 [:span (when-let [ts (:created-at snap)]
                          (let [d (js/Date. ts)]
                            (.toLocaleDateString d)))]
                 [:span "·"]
                 [:span (tr "workspace.options.ds-versions.name")]
                 [:span (str (:component-count snap 0))]]
                ;; actions
                [:div {:style {:display "flex"
                               :align-items "center"
                               :gap "6px"}}
                 [:button {:type "button"
                           :style ghost-btn-style
                           :on-click #(on-set-active snap)}
                  (tr "workspace.options.ds-versions.restore")]
                 [:button {:type "button"
                           :style ghost-btn-style
                           :on-click #(on-compare snap)}
                  (tr "workspace.options.ds-versions.compare")]]
                ;; inline compare summary
                (when diff
                  [:div {:style {:margin-top "4px"
                                 :padding "6px"
                                 :background "var(--input-background-color)"
                                 :border-radius "4px"
                                 :font-size "11px"
                                 :color neutral-600
                                 :line-height "1.5"}}
                   [:div (str "+ " (count (:added diff)))]
                   [:div (str "- " (count (:removed diff)))]
                   [:div (str "~ " (count (:renamed diff)))]])]))]

          ;; Empty state
          [:div {:class (stl/css :ds-versions-empty)
                 :style {:padding "12px"
                         :text-align "center"
                         :color neutral-600
                         :font-size "11px"
                         :line-height "1.5"}}
           (tr "workspace.options.ds-versions.empty")])])]))
