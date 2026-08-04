;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.options.menus.notes
  "Per-widget Notes / Specifications menu (ALL_APPS_PARITY P2.31).

  Sidebar panel for authoring the widget-notes map (requirements / copy /
  colors / placement) on the currently-selected shape(s). The notes are
  persisted through the changes pipeline as shape plugin-data (see
  data/workspace/notes.cljs); edits apply to all selected shapes and are
  committed on blur (so typing does not spam undo steps). A 'Copy spec'
  action copies the notes as a formatted spec block to the clipboard, and
  the structured map is the same one the publish pipeline (wiring spec)
  emits in HTML export metadata."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.main.data.event :as-alias ev]
   [app.main.data.workspace.notes :as dwn]
   [app.main.store :as st]
   [app.main.ui.components.copy-button :refer [copy-button*]]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [clojure.string :as cstr]
   [rumext.v2 :as mf]))

;; --- Field metadata ---------------------------------------------------------

(def ^:private field-config
  "Ordered vector of {:key :label-key :placeholder-key} for each widget-notes
  field. The label/placeholder strings are i18n keys (see en.po wiring)."
  [{:key :requirements :label-key "workspace.options.notes.field.requirements"
    :placeholder-key "workspace.options.notes.field.requirements.placeholder"}
   {:key :copy         :label-key "workspace.options.notes.field.copy"
    :placeholder-key "workspace.options.notes.field.copy.placeholder"}
   {:key :colors       :label-key "workspace.options.notes.field.colors"
    :placeholder-key "workspace.options.notes.field.colors.placeholder"}
   {:key :placement    :label-key "workspace.options.notes.field.placement"
    :placeholder-key "workspace.options.notes.field.placement.placeholder"}])

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

(defn- icon-notes []  (lucide-icon [[:path {:d "M3 7h18"}]
                                    [:path {:d "M3 12h18"}]
                                    [:path {:d "M3 17h12"}]]))
(defn- icon-copy []   (lucide-icon [[:rect {:x 9 :y 9 :width 13 :height 13 :rx 2}]
                                    [:path {:d "M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"}]]))

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

;; --- Spec formatting (for the Copy spec action) ----------------------------

(defn- format-spec
  "Render the widget-notes map as a human-readable spec block suitable for
  pasting into a ticket / spec doc. Empty fields are omitted so a partial
  spec copies cleanly. Returns a string."
  [notes shape-name]
  (let [lines
        (keep
         (fn [{:keys [key label-key]}]
           (let [v (cstr/trim (str (get notes key "")))]
             (when (seq v)
               (str (tr label-key) ":\n" v))))
         field-config)]
    (if (seq lines)
      (str (tr "workspace.options.notes.spec.header" (or shape-name "")) "\n\n"
           (cstr/join "\n\n" lines))
      (tr "workspace.options.notes.spec.empty"))))

;; --- Component --------------------------------------------------------------

(mf/defc notes-menu*
  [{:keys [shapes]}]
  (let [;; `shapes` is the vector of selected shape maps passed by the
        ;; options panel. Use the first selected shape as the source of
        ;; truth for the displayed notes (all selected shapes receive the
        ;; same value on commit).
        first-shape (first shapes)
        shape-id    (:id first-shape)
        shape-name  (:name first-shape)
        shape-ids   (mf/use-memo (mf/deps shapes)
                                #(into [] (keep :id) shapes))

        ;; Read the current notes from the first selected shape. The value
        ;; is resynced whenever the selection (shape-id) or the persisted
        ;; slot changes (undo / redo / external edit).
        current     (dwn/read-widget-notes first-shape)

        ;; Local drafts, one per field. Initialized from `current` and
        ;; resynced on selection / persisted-value change.
        reqs*   (mf/use-state (:requirements current ""))
        copy*   (mf/use-state (:copy current ""))
        cols*   (mf/use-state (:colors current ""))
        plac*   (mf/use-state (:placement current ""))

        _       (mf/use-effect
                 (mf/deps shape-id current)
                 (fn []
                   (reset! reqs* (:requirements current ""))
                   (reset! copy* (:copy current ""))
                   (reset! cols* (:colors current ""))
                   (reset! plac* (:placement current ""))))

        open*   (mf/use-state true)
        open?   (deref open*)
        toggle  (mf/use-fn #(swap! open* not))

        draft-map
        (mf/use-fn
         (fn []
           {:requirements @reqs*
            :copy @copy*
            :colors @cols*
            :placement @plac*}))

        commit
        (mf/use-fn
         (mf/deps shape-ids current draft-map)
         (fn []
           (let [draft (draft-map)]
             ;; Only emit when the draft actually differs from the
             ;; persisted value, so a no-op blur does not create a
             ;; spurious undo step. `current` is in the deps so the
             ;; closure is fresh after every commit / undo / redo.
             (when (not= draft current)
               (st/emit! (dwn/set-widget-notes
                          {:shape-ids shape-ids :notes draft}))))))

        on-change
        (mf/use-fn
         (fn [state* event]
           (reset! state* (dom/get-target-val event))))

        on-blur
        (mf/use-fn
         (mf/deps commit)
         (fn [] (commit)))

        on-key-down
        (mf/use-fn
         (mf/deps commit)
         (fn [event]
           (when (kbd/esc? event)
             (dom/prevent-default event)
             (reset! reqs* (:requirements current ""))
             (reset! copy* (:copy current ""))
             (reset! cols* (:colors current ""))
             (reset! plac* (:placement current ""))
             (dom/blur! (dom/get-target event)))))

        copy-spec-fn
        (mf/use-fn
         (mf/deps draft-map shape-name)
         (fn []
           (format-spec (draft-map) shape-name)))

        on-spec-copied
        (mf/use-fn
         (fn []
           (st/emit! (ev/event {::ev/name "copy-widget-spec"}))))]

    [:div {:class (stl/css :element-set)}
     [:div {:class (stl/css :element-title)}
      [:> title-bar* {:collapsable true
                      :collapsed (not open?)
                      :on-collapsed toggle
                      :title (tr "workspace.options.notes.title")}]]
     (when open?
       [:div {:class (stl/css :element-set-content)}
        ;; Spec-chip header
        [:div {:style {:display "flex"
                       :align-items "center"
                       :justify-content "space-between"
                       :gap "8px"
                       :margin-bottom "6px"}}
         [:span {:style coral-chip-style}
          (icon-notes)
          (tr "workspace.options.notes.spec.label")]
         [:> copy-button* {:data copy-spec-fn
                           :aria-label (tr "workspace.options.notes.copy-spec")
                           :class (stl/css :notes-copy-btn)
                           :on-copied on-spec-copied}
          [:span {:style {:display "inline-flex"
                          :align-items "center"
                          :gap "4px"
                          :font-size "11px"
                          :color coral}}
           (icon-copy)
           (tr "workspace.options.notes.copy-spec")]]]

        ;; Fields
        [:div {:class (stl/css :element-group)
               :style {:display "flex"
                       :flex-direction "column"
                       :gap "6px"}}
         (for [{:keys [key label-key placeholder-key]} field-config]
           (let [state* (case key
                          :requirements reqs*
                          :copy copy*
                          :colors cols*
                          :placement plac*)
                 value (deref state*)]
             [:div {:key (d/name key)
                    :class (stl/css :notes-field)}
              [:label {:class (stl/css :notes-field-label)
                       :for (str "notes-" (d/name key) "-input")}
               (tr label-key)]
              [:textarea {:id (str "notes-" (d/name key) "-input")
                          :class (stl/css :notes-textarea)
                          :rows 2
                          :value value
                          :placeholder (tr placeholder-key)
                          :on-change #(on-change state* %)
                          :on-blur on-blur
                          :on-key-down on-key-down}]]))]

        ;; Hint
        [:div {:style {:margin-top "8px"
                       :display "flex"
                       :gap "6px"
                       :align-items "flex-start"
                       :color neutral-600
                       :font-size "11px"
                       :line-height "1.4"}}
         [:span {:style {:color coral :margin-top "1px"}} (icon-notes)]
         [:span (tr "workspace.options.notes.hint")]]])]))