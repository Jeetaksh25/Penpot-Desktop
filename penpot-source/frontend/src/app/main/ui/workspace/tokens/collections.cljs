;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

;; Figma-parity variable collections (gap #43).
;;
;; This is a self-contained, additive scaffold for the Collection CRUD UI.
;; The Collection record + ICollection protocol live in
;; `app.common.types.tokens-lib` (added additively above the TokenSets
;; layer). Wiring Collection storage into the TokensLib deftype (and thus
;; the tokens sidebar / persistence path) is DEFERRED — it would reshape
;; the internal lib structure and the sidebar integration is high
;; blast-radius. This panel is therefore NOT mounted anywhere yet; it is a
;; ready-to-wire component that takes a `collections` seq + CRUD callbacks
;; so the sidebar integration (a later round) only needs to plumb props.
;;
;; v1 scope: render + local-state add/rename/remove. Modes and set
;; membership editing are stubbed as no-op rows (the protocol methods exist
;; on Collection but no data event persists them yet).

(ns app.main.ui.workspace.tokens.collections
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.types.tokens-lib :as ctob]
   [app.main.ui.context :as ctx]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.util.i18n :refer [tr]]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(mf/defc collection-row*
  {::mf/private true}
  [{:keys [collection on-rename on-remove can-edit?]}]
  (let [name*      (mf/use-state (:name collection))
        editing?*  (mf/use-state false)
        name       (deref name*)
        editing?   (deref editing?*)
        set-ids    (ctob/get-set-ids collection)
        modes      (ctob/get-modes collection)

        start-edit
        (mf/use-fn #(reset! editing?* true))

        commit-edit
        (mf/use-fn
         (mf/deps on-rename collection name)
         (fn []
           (when can-edit?
             (on-rename collection (str/trim name)))
           (reset! editing?* false)))

        on-input-change
        (mf/use-fn #(reset! name* %))

        on-remove-click
        (mf/use-fn
         (mf/deps on-remove collection)
         (fn []
           (when can-edit?
             (on-remove collection))))]

    [:div {:class (stl/css :collection-row)}
     (if editing?
       [:input {:class (stl/css :collection-name-input)
                :value name
                :on-change on-input-change
                :on-blur commit-edit}]
       [:span {:class (stl/css :collection-name)
               :on-double-click start-edit}
        (:name collection)])

     [:div {:class (stl/css :collection-meta)}
      (tr "workspace.tokens.collections.sets-count" (count set-ids))
      " · "
      (tr "workspace.tokens.collections.modes-count" (count modes))]

     (when ^boolean can-edit?
       [:div {:class (stl/css :collection-actions)}
        [:button {:class (stl/css :collection-edit-btn)
                  :title (tr "workspace.tokens.collections.rename")
                  :on-click start-edit}
         (tr "workspace.tokens.collections.rename")]
        [:button {:class (stl/css :collection-remove-btn)
                  :title (tr "workspace.tokens.collections.remove")
                  :on-click on-remove-click}
         (tr "workspace.tokens.collections.remove")]])]))

(mf/defc collections-panel*
  "Render a list of variable Collections with add/rename/remove controls.
  Pure presentational component — all persistence is delegated to the
  `on-add` / `on-rename` / `on-remove` callbacks so this file stays free of
  data-event wiring (deferred)."
  [{:keys [collections on-add on-rename on-remove]}]
  (let [can-edit?    (mf/use-ctx ctx/can-edit?)
        new-name*    (mf/use-state "")
        new-name     (deref new-name*)

        on-input-change
        (mf/use-fn #(reset! new-name* %))

        on-add-click
        (mf/use-fn
         (mf/deps on-add new-name)
         (fn []
           (let [trimmed (str/trim new-name)]
             (when (and can-edit? (not (str/blank? trimmed)) (some? on-add))
               (on-add trimmed)
               (reset! new-name* "")))))]

    [:div {:class (stl/css :collections-panel)}
     [:> title-bar*
      {:title (tr "workspace.tokens.collections.title")
       :open  true}]

     (when ^boolean can-edit?
       [:div {:class (stl/css :collection-add-row)}
        [:input {:class (stl/css :collection-add-input)
                 :value new-name
                 :placeholder (tr "workspace.tokens.collections.add-placeholder")
                 :on-change on-input-change}]
        [:button {:class (stl/css :collection-add-btn)
                  :disabled (str/blank? (str/trim new-name))
                  :on-click on-add-click}
         (tr "workspace.tokens.collections.add")]])

     [:ul {:class (stl/css :collections-list)}
      (for [collection collections]
        [:> collection-row*
         {:key        (str (:id collection))
          :collection collection
          :can-edit?  can-edit?
          :on-rename  on-rename
          :on-remove  on-remove}])

      (when (empty? collections)
        [:li {:class (stl/css :collections-empty)}
         (tr "workspace.tokens.collections.empty")])]]))