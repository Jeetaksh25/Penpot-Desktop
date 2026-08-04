;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.code-components
  "P0.14 — Code-component host (ALL_APPS_PARITY). An iframe-based React/code
  component host for the canvas. A rect (or frame) carries a shape-level
  plugin-data slot `:ovion \"code-component\"` = pr-str of:

    {:id    <registry-id-keyword>
     :props {<prop-keyword> <value>}}

  pointing at an entry in the FILE-level registry, stored as plugin-data
  `:ovion \"code-components\"` = pr-str of:

    {<id-keyword> {:name        <string>
                   :bundle-url  <string>
                   :props-schema {<prop-keyword>
                                   {:type    (:string|:number|:boolean|:color)
                                    :default <val>
                                    :label   <string>}}}}

  This module is the single host interface consumed by:
    - ui.shapes.code-component       (render: foreignObject + sandboxed iframe)
    - ui.workspace.sidebar.options.menus.code-component (inspector menu)
    - P1.35 AI code-gen + P0.19 Storybook sync (other agents this wave)

  Exported symbols (stable interface — DO NOT rename):
    register-component              pure changes fn
    unregister-component            pure changes fn
    register-component-event        ptk event (one undo)
    unregister-component-event      ptk event (one undo)
    read-registry                   [file-data] -> registry map (nil-safe)
    apply-to-shape-event            [shape-id registry-id props] (one undo)
    clear-from-shape-event          [shape-id] (one undo)
    read-slot                       [shape] -> {:id :props} | nil
    bundle-url-for                  [registry-id file-data] -> url | nil

  Byte-identical-when-inactive: a shape without the slot renders exactly as
  today (read-slot returns nil -> the carrier shape renderer falls through
  to its pre-existing form). A file with no registry has read-registry return
  nil/empty — no plugin-data is written until a component is registered."
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as pcb]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.undo :as dwu]
   [beicon.v2.core :as rx]
   [cljs.reader :as reader]
   [potok.v2.core :as ptk]))

;; --- Plugin-data slot constants --------------------------------------------

(def ovion-namespace :ovion)
(def registry-key "code-components")
(def slot-key "code-component")

;; --- Read helpers -----------------------------------------------------------

(defn read-registry
  "Parse the file-level code-component registry back into a map
  `{<id-keyword> {:name :bundle-url :props-schema}}`. Accepts a file-data
  map (reads :plugin-data) or a raw stored string. Returns nil when absent
  or unparsable (nil = no registry = nothing to render)."
  ([]
   nil)
  ([file-data-or-str]
   (let [raw (if (map? file-data-or-str)
               (dm/get-in file-data-or-str [:plugin-data ovion-namespace registry-key])
               file-data-or-str)]
     (if (or (nil? raw) (empty? raw))
       nil
       (try
         (reader/read-string raw)
         (catch :default _ nil))))))

(defn read-slot
  "Parse a shape's code-component slot back into `{:id <kw> :props {..}}`.
  Accepts a shape map (reads :plugin-data) or a raw stored string. Returns
  nil when absent or unparsable (nil = no code-component = render normally)."
  ([]
   nil)
  ([shape-or-str]
   (let [raw (if (map? shape-or-str)
               (dm/get-in shape-or-str [:plugin-data ovion-namespace slot-key])
               shape-or-str)]
     (if (or (nil? raw) (empty? raw))
       nil
       (try
         (reader/read-string raw)
         (catch :default _ nil))))))

(defn bundle-url-for
  "Look up the bundle-url for `registry-id` (a keyword) in the file-level
  registry. Returns nil when the id is missing or the registry is absent.
  `file-data` is the current file-data map."
  [registry-id file-data]
  (let [registry (read-registry file-data)]
    (when (and (map? registry) (keyword? registry-id))
      (dm/get-in registry [registry-id :bundle-url]))))

(defn- component-name-for
  "Look up a registered component's display name (for the missing-bundle
  placeholder). Returns a fallback string when absent."
  [registry-id file-data]
  (let [registry (read-registry file-data)]
    (if (and (map? registry) (keyword? registry-id))
      (or (dm/get-in registry [registry-id :name])
          (str (name registry-id)))
      (if (keyword? registry-id) (name registry-id) "component"))))

;; --- Pure changes functions -------------------------------------------------
;;
;; These take a `changes` value that already carries file-data context
;; (via pcb/with-file-data) and return updated changes. The event wrappers
;; below set up the context + undo transaction.

(defn register-component
  "Pure changes fn. Adds/updates registry entry `id` (a keyword) with the
  given `name`, `bundle-url`, and `props-schema`. Idempotent on `id`
  (re-registering the same id overwrites the entry). Merges into the
  existing registry. `changes` must carry file-data (via pcb/with-file-data)."
  [changes id name bundle-url props-schema]
  (let [file-data (::file-data (meta changes))
        registry  (or (read-registry file-data) {})
        entry     {:name        (str name)
                   :bundle-url  (str bundle-url)
                   :props-schema (or props-schema {})}
        new-reg   (assoc registry (keyword id) entry)]
    (pcb/set-plugin-data changes ovion-namespace registry-key (pr-str new-reg))))

(defn unregister-component
  "Pure changes fn. Removes registry entry `id` (a keyword) from the
  registry. No-op when the id is absent. `changes` must carry file-data."
  [changes id]
  (let [file-data (::file-data (meta changes))
        registry  (or (read-registry file-data) {})
        kid       (keyword id)
        new-reg   (if (contains? registry kid) (dissoc registry kid) registry)]
    (pcb/set-plugin-data changes ovion-namespace registry-key (pr-str new-reg))))

(defn- set-shape-slot
  "Pure changes fn: write the slot on shape `shape-id` (page `page-id`).
  `slot` is `{:id <kw> :props {..}}` or nil to clear. `changes` must carry
  file-data + page context (via pcb/with-file-data + pcb/with-page)."
  [changes shape-id page-id slot]
  (let [value (if (nil? slot) nil (pr-str slot))]
    (pcb/set-plugin-data changes :shape shape-id page-id ovion-namespace slot-key value)))

;; --- Events (one undo transaction) ------------------------------------------

(defn- commit-file-plugin-data
  "Build + commit a file-level plugin-data change in one undo transaction.
  `f` is applied to `changes` (with file-data context) and must return
  updated changes."
  [it state f]
  (let [file-id   (:current-file-id state)
        file-data (dsh/lookup-file-data state file-id)]
    (if (nil? file-data)
      (rx/empty)
      (let [undo-id (js/Symbol)
            changes (-> (pcb/empty-changes it)
                        (pcb/with-file-data file-data)
                        (f))]
        (rx/of (dwu/start-undo-transaction undo-id)
               (dch/commit-changes changes)
               (dwu/commit-undo-transaction undo-id))))))

(defn- commit-shape-plugin-data
  "Build + commit a shape-level plugin-data change in one undo transaction."
  [it state shape-id value]
  (let [page-id   (:current-page-id state)
        page      (dsh/lookup-page state)
        file-id   (:current-file-id state)
        file-data (dsh/lookup-file-data state file-id)]
    (if (or (nil? page) (nil? shape-id))
      (rx/empty)
      (let [undo-id (js/Symbol)
            changes (-> (pcb/empty-changes it)
                        (pcb/with-file-data file-data)
                        (pcb/with-page page)
                        (set-shape-slot shape-id page-id value))]
        (rx/of (dwu/start-undo-transaction undo-id)
               (dch/commit-changes changes)
               (dwu/commit-undo-transaction undo-id))))))

(defn register-component-event
  "ptk event: register a code component in the file registry in one undo
  transaction. `id` is a keyword, `props-schema` is
  `{<prop-kw> {:type :string|:number|:boolean|:color :default _ :label _}}`."
  [id name bundle-url props-schema]
  (ptk/reify ::register-component
    ptk/WatchEvent
    (watch [it state _]
      (commit-file-plugin-data
       it state
       (fn [changes]
         (register-component changes id name bundle-url props-schema))))))

(defn unregister-component-event
  "ptk event: remove `id` (keyword) from the file registry in one undo
  transaction."
  [id]
  (ptk/reify ::unregister-component
    ptk/WatchEvent
    (watch [it state _]
      (commit-file-plugin-data
       it state
       (fn [changes]
         (unregister-component changes id))))))

(defn apply-to-shape-event
  "ptk event: set the code-component slot on shape `shape-id` to
  `{:id registry-id :props props}` in one undo transaction. `registry-id`
  is a keyword, `props` is a map."
  [shape-id registry-id props]
  (ptk/reify ::apply-code-component
    ptk/WatchEvent
    (watch [it state _]
      (let [slot (if (nil? registry-id)
                   nil
                   {:id (keyword registry-id)
                    :props (or props {})})]
        (commit-shape-plugin-data it state shape-id slot)))))

(defn clear-from-shape-event
  "ptk event: remove the code-component slot from shape `shape-id`."
  [shape-id]
  (ptk/reify ::clear-code-component
    ptk/WatchEvent
    (watch [it state _]
      (commit-shape-plugin-data it state shape-id nil))))