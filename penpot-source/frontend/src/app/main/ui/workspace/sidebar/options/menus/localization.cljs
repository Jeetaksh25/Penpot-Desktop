;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.options.menus.localization
  "P2.25 — Localization inspector menu (ALL_APPS_PARITY).

  Authors the `:ovion \"locales\"` file-level slot + the
  `:ovion \"locale-strings\"` shape-level slot, and exposes the canvas-wide
  active-locale switcher. Self-hides unless exactly one text shape is
  selected. Provides:

    - the active-locale switcher (a dropdown of enabled locales — this is
      the canvas-wide locale switch emitting set-active-locale-event)
    - the project's enabled locales (chips) + an add-locale input + a
      remove-locale button per chip (emits add-locale-event /
      remove-locale-event)
    - for the selected text shape: an 'Enable locale strings' toggle
      (emits enable-locale-strings-on-shape-event) so a normal text shape
      becomes locale-managed; once enabled, a per-locale string editor
      (one textarea per enabled locale) emitting set-shape-locale-string-event
      on every edit, plus a 'Remove localization' button
      (clear-locale-strings-on-shape-event)

  Coral accent, Lucide icons (languages / globe / plus / x),
  reduced-motion safe (no motion emitted)."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.workspace.localization :as l10n]
   [app.main.data.workspace.localization.events :as l10nev]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.util.i18n :refer [tr]]
   [clojure.string :as cstr]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

;; Coral + grey accents (Ovion theme) — matches the code-component menu.
(def ^:private coral "#f28b82")
(def ^:private grey  "#7d7d7d")

(def ^:private row-style
  {:display "flex" :align-items "center" :gap "8px" :width "100%"})

(def ^:private label-style
  {:font-size "11px" :color grey :width "72px" :flex-shrink "0"})

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

(def ^:private ghost-btn-style
  (merge coral-btn-style
         {:border "1px solid rgba(125,125,125,0.3)"
          :background "transparent"
          :color grey}))

(def ^:private select-style
  {:flex "1"
   :height "26px"
   :border "1px solid rgba(125,125,125,0.3)"
   :border-radius "6px"
   :background "transparent"
   :color "inherit"
   :font-size "11px"
   :padding "0 6px"})

(def ^:private textarea-style
  {:width "100%"
   :min-height "48px"
   :border "1px solid rgba(125,125,125,0.3)"
   :border-radius "6px"
   :background "transparent"
   :color "inherit"
   :font-size "11px"
   :font-family "inherit"
   :padding "4px 6px"
   :resize "vertical"})

(def ^:private chip-style
  {:display "inline-flex"
   :align-items "center"
   :gap "4px"
   :height "22px"
   :padding "0 6px"
   :border-radius "11px"
   :border "1px solid rgba(242,139,130,0.4)"
   :background "rgba(242,139,130,0.08)"
   :color coral
   :font-size "10px"
   :font-weight "500"})

(def ^:private chip-remove-style
  {:display "inline-flex"
   :align-items "center"
   :justify-content "center"
   :cursor "pointer"
   :opacity "0.7"})

;; --- Lucide icons (lucide.dev, MIT) — 24x24, stroke currentColor, sw 2 ---

(defn- icon-languages
  []
  (mf/html [:svg {:width 14 :height 14 :viewBox "0 0 24 24" :fill "none"
                  :stroke coral :stroke-width 2 :stroke-linecap "round"
                  :stroke-linejoin "round"
                  :style {:flex-shrink "0"}}
            [:path {:d "M5 8l6 6"}]
            [:path {:d "m4 14 6-6 2-3"}]
            [:path {:d "M2 5h12"}]
            [:path {:d "M7 7h6"}]
            [:path {:d "M22 22l-5-10-5 10"}]
            [:path {:d "M14 18h6"}]]))

(defn- icon-globe
  []
  (mf/html [:svg {:width 14 :height 14 :viewBox "0 0 24 24" :fill "none"
                  :stroke coral :stroke-width 2 :stroke-linecap "round"
                  :stroke-linejoin "round"
                  :style {:flex-shrink "0"}}
            [:circle {:cx 12 :cy 12 :r 10}]
            [:path {:d "M12 2a14.5 14.5 0 0 0 0 20 14.5 14.5 0 0 0 0-20"}]
            [:path {:d "M2 12h20"}]]))

(defn- icon-plus
  []
  (mf/html [:svg {:width 12 :height 12 :viewBox "0 0 24 24" :fill "none"
                  :stroke coral :stroke-width 2 :stroke-linecap "round"
                  :stroke-linejoin "round"
                  :style {:flex-shrink "0"}}
            [:path {:d "M5 12h14"}]
            [:path {:d "M12 5v14"}]]))

(defn- icon-x
  []
  (mf/html [:svg {:width 12 :height 12 :viewBox "0 0 24 24" :fill "none"
                  :stroke coral :stroke-width 2 :stroke-linecap "round"
                  :stroke-linejoin "round"
                  :style {:flex-shrink "0"}}
            [:path {:d "M18 6 6 18"}]
            [:path {:d "m6 6 12 12"}]]))

;; --- Derived refs -----------------------------------------------------------
;;
;; Mirror the code-component menu pattern: derived refs over workspace-data
;; (file-level locales) and workspace-page (shape-level slot) so the menu
;; re-renders only when the relevant slice changes.

(defn- locales-ref
  "Derived ref over the file-data enabled-locales vector."
  []
  (l/derived
   (fn [file-data]
     (l10n/read-locales file-data))
   refs/workspace-data
   =))

(defn- locale-strings-ref
  "Derived ref that reads the locale-strings slot for `shape-id` from the
  workspace page objects, so the menu re-renders when the slot changes."
  [shape-id]
  (l/derived
   (fn [page]
     (let [shape (get-in page [:objects shape-id])]
       (l10n/read-locale-strings shape)))
   refs/workspace-page
   =))

;; --- Add-locale input -------------------------------------------------------

(mf/defc add-locale-row*
  "A text input + Add button that emits add-locale-event with the typed
  locale keyword (lowercased, trimmed). Empty input is a no-op."
  [{:keys [on-add]}]
  (let [value* (mf/use-state "")
        on-change (mf/use-fn #(reset! value* (.. % -target -value)))
        on-add-fn
        (mf/use-fn
         (mf/deps @value*)
         (fn []
           (let [raw (cstr/trim (str @value*))]
             (when (not (empty? raw))
               (on-add (keyword (cstr/lower-case raw)))
               (reset! value* "")))))]
    [:div {:style row-style}
     [:input {:type "text"
              :value @value*
              :placeholder (tr "workspace.options.localization.locale-placeholder")
              :class (stl/css :type-input)
              :style {:flex "1"}
              :on-change on-change}]
     [:button {:type "button" :style coral-btn-style :on-click on-add-fn}
      (icon-plus)
      [:span {:style {:margin-left "4px"}}
       (tr "workspace.options.localization.add")]]]))

;; --- Per-locale string editor -----------------------------------------------

(mf/defc locale-string-editor*
  "One textarea row per enabled locale for the selected text shape.
  Emits set-shape-locale-string-event on every edit. The current value is
  read from the slot (reactive), so edits flow back through state."
  [{:keys [shape-id locale current-text]}]
  (let [on-change
        (mf/use-fn
         (mf/deps shape-id locale)
         (fn [e]
           (st/emit! (l10nev/set-shape-locale-string-event
                      shape-id locale (.. e -target -value)))))]
    [:div {:style {:display "flex" :flex-direction "column" :gap "4px"
                   :padding "2px 0"}}
     [:span {:style (assoc label-style :width "auto")}
      (name locale)]
     [:textarea {:value (or current-text "")
                 :placeholder (str (name locale) "…")
                 :class (stl/css :type-input)
                 :style textarea-style
                 :on-change on-change}]]))

;; --- Main menu --------------------------------------------------------------

(mf/defc localization-menu*
  "Inspector menu for the locale model. `shapes` is the vector of currently
  selected shapes. Self-hides (returns nil) unless exactly one text shape is
  selected. Shows the active-locale switcher, the enabled-locale chips with
  add/remove, and (for the selected text shape) the enable-locale-strings
  toggle + per-locale string editor."
  [{:keys [shapes]}]
  (let [shape    (first shapes)
        shape-id (:id shape)
        stype    (:type shape)
        show?    (and (= 1 (count shapes)) (= :text stype))]
    (when show?
      (let [locales     (mf/deref (locales-ref))
            locales     (or locales [:en])
            strings     (mf/deref (locale-strings-ref shape-id))
            has-slot    (some? strings)
            active      (or (mf/deref l10n/active-locale-ref) :en)

            on-set-active
            (mf/use-fn
             (fn [e]
               (let [raw (.. e -target -value)]
                 (when (not (empty? raw))
                   (st/emit! (l10nev/set-active-locale-event (keyword raw)))))))

            on-add-locale
            (mf/use-fn
             (fn [loc]
               (st/emit! (l10nev/add-locale-event loc))))

            on-remove-locale
            (mf/use-fn
             (mf/deps locales)
             (fn [loc]
               (st/emit! (l10nev/remove-locale-event loc))))

            on-enable
            (mf/use-fn
             (mf/deps shape-id)
             #(st/emit! (l10nev/enable-locale-strings-on-shape-event shape-id)))

            on-clear
            (mf/use-fn
             (mf/deps shape-id)
             #(st/emit! (l10nev/clear-locale-strings-on-shape-event shape-id)))]

        [:div {:class (stl/css :element-set)}
         [:div {:class (stl/css :element-title)}
          [:> title-bar* {:collapsable true :collapsed false
                          :title (tr "workspace.options.localization.title")}
           [:span {:style {:display "inline-flex" :align-items "center"
                           :margin-left "6px"}}
            (icon-languages)]]]

         [:div {:class (stl/css :element-set-content)}
          [:div {:class (stl/css :element-group)
                 :style {:display "flex" :flex-direction "column" :gap "8px"}}

           ;; Active-locale switcher (canvas-wide).
           [:div {:style row-style}
            [:span {:style label-style}
             (tr "workspace.options.localization.active-locale")]
            [:select {:value (name active)
                      :style select-style
                      :on-change on-set-active}
             (for [loc locales]
               ^{:key (str loc)}
               [:option {:value (name loc)} (name loc)])]]

           ;; Enabled-locale chips + add-locale input.
           [:div {:style {:display "flex" :flex-direction "column" :gap "6px"
                          :padding "2px 0"}}
            [:div {:style {:font-size "11px" :color grey}}
             (tr "workspace.options.localization.locales")]
            [:div {:style {:display "flex" :flex-wrap "wrap" :gap "4px"
                           :align-items "center"}}
             (doall
              (for [loc locales]
                ^{:key (str loc)}
                [:div {:style chip-style}
                 [:span (name loc)]
                 (when (not= loc :en)
                   [:span {:style chip-remove-style
                           :title (tr "workspace.options.localization.remove-locale")
                           :on-click #(on-remove-locale loc)}
                    (icon-x)])]))
             [:> add-locale-row* {:on-add on-add-locale}]]]

           ;; Per-shape: enable / edit / clear locale strings.
           (if has-slot
             [:*
              [:div {:style {:font-size "11px" :color grey :padding "2px 0"}}
               (tr "workspace.options.localization.strings-for-shape")]
              (doall
               (for [loc locales]
                 ^{:key (str loc)}
                 [:> locale-string-editor*
                  {:shape-id shape-id
                   :locale loc
                   :current-text (get strings loc "")}]))
              [:div {:style (merge row-style {:justify-content "flex-end"
                                              :padding-top "4px"
                                              :gap "6px"})}
               [:button {:type "button" :style ghost-btn-style
                         :title (tr "workspace.options.localization.remove-localization")
                         :on-click on-clear}
                (icon-x)
                [:span {:style {:margin-left "4px"}}
                 (tr "workspace.options.localization.remove-localization")]]]]
             [:div {:style row-style}
              [:button {:type "button" :style coral-btn-style :on-click on-enable}
               (icon-globe)
               [:span {:style {:margin-left "4px"}}
                (tr "workspace.options.localization.enable")]]])]]]))))