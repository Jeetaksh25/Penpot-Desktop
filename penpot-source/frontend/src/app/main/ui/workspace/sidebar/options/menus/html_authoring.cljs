;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.options.menus.html-authoring
  "HTML Authoring menu (ALL_APPS_PARITY P0.13).

  Sidebar panel for authoring three HTML-export hints on the currently
  selected shape(s): a semantic tag (from a whitelist), a CSS class
  string, and a free-form custom CSS block. The values are persisted
  through the changes pipeline as shape plugin-data (see
  data/workspace/html_authoring.cljs); edits apply to all selected
  shapes and are committed on blur (so typing does not spam undo
  steps), and Escape cancels back to the last persisted value.

  Lucide icons are inlined (viewBox 0 0 24 24, stroke-width 2,
  currentColor). Coral accent #f28b82 for chips and focus rings; grey
  #7d7d7d for secondary text. i18n keys are placeholders the lead adds
  to en.po."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.workspace.html-authoring :as dha]
   [app.main.store :as st]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [clojure.string :as cstr]
   [rumext.v2 :as mf]))

;; --- Semantic-tag whitelist ------------------------------------------------

(def ^:private semantic-tag-whitelist
  "Ordered whitelist of semantic HTML tags offered in the select. The
  leading empty option maps to nil (cleared slot). Kept as a vector so
  the select preserves this order."
  ["" "div" "header" "main" "section" "nav" "article" "aside" "footer"
   "figure" "figcaption" "ul" "ol" "li" "blockquote"
   "h1" "h2" "h3" "h4" "h5" "h6" "p" "span"])

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

(defn- icon-code []
  ;; A `<code>` / angle-bracket icon to label the section chip.
  (lucide-icon [[:path {:d "m16 18 6-6-6-6"}]
                [:path {:d "m8 6-6 6 6 6"}]]))

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

;; --- Component --------------------------------------------------------------

(mf/defc html-authoring-menu*
  [{:keys [shapes]}]
  (let [;; `shapes` is the vector of selected shape maps passed by the
        ;; options panel. Use the first selected shape as the source of
        ;; truth for the displayed values (all selected shapes receive
        ;; the same value on commit).
        first-shape (first shapes)
        shape-id    (:id first-shape)
        shape-ids   (mf/use-memo (mf/deps shapes)
                                #(into [] (keep :id) shapes))

        ;; Read the current slots from the first selected shape. The
        ;; values are resynced whenever the selection (shape-id) or the
        ;; persisted slots change (undo / redo / external edit).
        cur-tag  (dha/read-semantic-tag first-shape)
        cur-cls  (dha/read-css-class first-shape)
        cur-css  (dha/read-custom-css first-shape)

        ;; Local drafts, one per slot. Initialized from the current
        ;; persisted value and resynced on selection / persisted-value
        ;; change.
        tag*   (mf/use-state (or cur-tag ""))
        cls*   (mf/use-state (or cur-cls ""))
        css*   (mf/use-state (or cur-css ""))

        _      (mf/use-effect
                (mf/deps shape-id cur-tag cur-cls cur-css)
                (fn []
                  (reset! tag* (or cur-tag ""))
                  (reset! cls* (or cur-cls ""))
                  (reset! css* (or cur-css ""))))

        open*  (mf/use-state true)
        open?  (deref open*)
        toggle (mf/use-fn #(swap! open* not))

        commit-tag
        (mf/use-fn
         (mf/deps shape-ids cur-tag)
         (fn []
           (let [v @tag*]
             (when (not= v (or cur-tag ""))
               (st/emit! (dha/set-semantic-tag
                          {:shape-ids shape-ids :value v}))))))

        commit-cls
        (mf/use-fn
         (mf/deps shape-ids cur-cls)
         (fn []
           (let [v @cls*]
             (when (not= v (or cur-cls ""))
               (st/emit! (dha/set-css-class
                          {:shape-ids shape-ids :value v}))))))

        commit-css
        (mf/use-fn
         (mf/deps shape-ids cur-css)
         (fn []
           (let [v @css*]
             (when (not= v (or cur-css ""))
               (st/emit! (dha/set-custom-css
                          {:shape-ids shape-ids :value v}))))))

        on-change
        (mf/use-fn
         (fn [state* event]
           (reset! state* (dom/get-target-val event))))

        on-key-down
        (mf/use-fn
         (mf/deps cur-tag cur-cls cur-css)
         (fn [event]
           (when (kbd/esc? event)
             (dom/prevent-default event)
             (reset! tag* (or cur-tag ""))
             (reset! cls* (or cur-cls ""))
             (reset! css* (or cur-css ""))
             (dom/blur! (dom/get-target event)))))]

    (when first-shape
      [:div {:class (stl/css :element-set)}
       [:div {:class (stl/css :element-title)}
        [:> title-bar* {:collapsable true
                        :collapsed (not open?)
                        :on-collapsed toggle
                        :title (tr "workspace.options.html-authoring.title")}]]

       (when open?
         [:div {:class (stl/css :element-set-content)}
          ;; Section chip header
          [:div {:style {:display "flex"
                         :align-items "center"
                         :gap "8px"
                         :margin-bottom "6px"}}
           [:span {:style coral-chip-style}
            (icon-code)
            (tr "workspace.options.html-authoring.section")]]

          ;; Fields
          [:div {:class (stl/css :element-group)
                 :style {:display "flex"
                         :flex-direction "column"
                         :gap "6px"}}

           ;; Semantic tag (select)
           [:div {:class (stl/css :html-authoring-field)}
            [:label {:class (stl/css :html-authoring-field-label)
                     :for "html-authoring-semantic-tag"}
             (tr "workspace.options.html-authoring.semantic-tag")]
            [:select {:id "html-authoring-semantic-tag"
                      :class (stl/css :html-authoring-select)
                      :value @tag*
                      :on-change #(on-change tag* %)
                      :on-blur commit-tag
                      :on-key-down on-key-down}
             (for [tag semantic-tag-whitelist]
               [:option {:key (or tag "none")
                         :value tag}
                (if (cstr/blank? tag) "—" tag)])]
            [:div {:class (stl/css :html-authoring-hint)}
             (tr "workspace.options.html-authoring.semantic-tag-hint")]]

           ;; CSS class (text input)
           [:div {:class (stl/css :html-authoring-field)}
            [:label {:class (stl/css :html-authoring-field-label)
                     :for "html-authoring-css-class"}
             (tr "workspace.options.html-authoring.css-class")]
            [:input {:id "html-authoring-css-class"
                     :type "text"
                     :class (stl/css :html-authoring-input)
                     :value @cls*
                     :on-change #(on-change cls* %)
                     :on-blur commit-cls
                     :on-key-down on-key-down}]
            [:div {:class (stl/css :html-authoring-hint)}
             (tr "workspace.options.html-authoring.css-class-hint")]]

           ;; Custom CSS (textarea)
           [:div {:class (stl/css :html-authoring-field)}
            [:label {:class (stl/css :html-authoring-field-label)
                     :for "html-authoring-custom-css"}
             (tr "workspace.options.html-authoring.custom-css")]
            [:textarea {:id "html-authoring-custom-css"
                        :class (stl/css :html-authoring-textarea)
                        :rows 4
                        :value @css*
                        :style {:font-family "var(--token-font-mono, ui-monospace, monospace)"}
                        :on-change #(on-change css* %)
                        :on-blur commit-css
                        :on-key-down on-key-down}]
            [:div {:class (stl/css :html-authoring-hint)}
             (tr "workspace.options.html-authoring.custom-css-hint")]]]])])))