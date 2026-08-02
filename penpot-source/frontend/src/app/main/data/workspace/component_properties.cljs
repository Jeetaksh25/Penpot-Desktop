;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

;; Figma-parity typed component properties (Figma_Parity.md gap #1) —
;; the data/events layer. Mirrors the variant-property event pattern in
;; `data/workspace/variants.cljs` (same changes-builder + undo-transaction
;; primitives). These events edit a main component's :component-properties
;; definitions and set per-instance :component-property-values overrides.
;;
;; v1 scope: authoring + persistence fully wired. Runtime override
;; application (apply-property-overrides) is a pure helper in
;; `app.common.types.component-property` and is not yet wired into the
;; render/sync path (deferred to polish #9). See that namespace + the
;; Figma_Parity.md entry for the limitation note.

(ns app.main.data.workspace.component-properties
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.types.component-property :as ctcp]
   [app.main.data.changes :as dch]
   [app.main.data.event :as ev]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.undo :as dwu]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(defn add-property
  "Add a new typed component property definition to a main component."
  [component-id {:keys [name type default-value targets preferred-instances] :as opts}]
  (ptk/reify ::add-component-property
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id (:current-page-id state)
            data    (dsh/lookup-file-data state)
            objects (dsh/lookup-page-objects state)
            prop    (ctcp/make-property
                    {:name (or name "Property 1")
                     :type (or type :boolean)
                     :default-value default-value
                     :targets targets
                     :preferred-instances preferred-instances})
            changes (-> (pcb/empty-changes it page-id)
                        (pcb/with-objects objects)
                        (pcb/with-library-data data)
                        (pcb/update-component component-id
                          #(update % :component-properties (fnil conj []) prop)))
            undo-id (js/Symbol)]
        (rx/of
         (dwu/start-undo-transaction undo-id)
         (dch/commit-changes changes)
         (dwu/commit-undo-transaction undo-id)
         (ev/event {::ev/name "add-component-property"
                    ::ev/origin "workspace:design-tab-component"}))))))

(defn update-property
  "Update an existing typed component property definition (by property id)."
  [component-id property-id updates]
  (ptk/reify ::update-component-property
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id (:current-page-id state)
            data    (dsh/lookup-file-data state)
            objects (dsh/lookup-page-objects state)
            changes (-> (pcb/empty-changes it page-id)
                        (pcb/with-objects objects)
                        (pcb/with-library-data data)
                        (pcb/update-component component-id
                          (fn [component]
                            (update component :component-properties
                                    (fn [props]
                                      (mapv #(if (= (:id %) property-id)
                                               (merge % updates)
                                               %)
                                             props))))))
            undo-id (js/Symbol)]
        (rx/of
         (dwu/start-undo-transaction undo-id)
         (dch/commit-changes changes)
         (dwu/commit-undo-transaction undo-id)
         (ev/event {::ev/name "update-component-property"
                    ::ev/origin "workspace:design-tab-component"}))))))

(defn remove-property
  "Remove a typed component property definition by id."
  [component-id property-id]
  (ptk/reify ::remove-component-property
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id (:current-page-id state)
            data    (dsh/lookup-file-data state)
            objects (dsh/lookup-page-objects state)
            changes (-> (pcb/empty-changes it page-id)
                        (pcb/with-objects objects)
                        (pcb/with-library-data data)
                        (pcb/update-component component-id
                          (fn [component]
                            (update component :component-properties
                                    (fn [props]
                                      (vec (remove #(= (:id %) property-id) props)))))))
            undo-id (js/Symbol)]
        (rx/of
         (dwu/start-undo-transaction undo-id)
         (dch/commit-changes changes)
         (dwu/commit-undo-transaction undo-id)
         (ev/event {::ev/name "remove-component-property"
                    ::ev/origin "workspace:design-tab-component"}))))))

(defn set-property-value
  "Set a single property value on an instance root shape (the override)."
  [shape-id prop-name value]
  (ptk/reify ::set-component-property-value
    ptk/WatchEvent
    (watch [it state _]
      (let [undo-id (js/Symbol)]
        (rx/of
         (dwu/start-undo-transaction undo-id)
         (dwsh/update-shapes [shape-id]
           (fn [shape]
             (assoc shape :component-property-values
                    (assoc (or (:component-property-values shape) {})
                           prop-name value))))
         (dwu/commit-undo-transaction undo-id)
         (ev/event {::ev/name "set-component-property-value"
                    ::ev/origin "workspace:design-tab-component"}))))))