;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.collections
  "CMS Collections authorable events (ALL_APPS_PARITY P0.05).

  Persists the page's CMS data (`{:collections [] :bindings []}` map)
  through the standard changes pipeline as page-level plugin-data, so
  edits are undo/redo-safe and survive save/reload. The whole cms-data
  map is serialized (`pr-str`) to a string and stored under namespace
  `:ovion` key `\"cms-data\"` on the page's `:plugin-data` slot — the
  schema:plugin-data value type is `string`, so the map is serialized
  (uuids round-trip via the CLJS reader's `#uuid` tagged literal).

  Why plugin-data and not a new page key:
    * `pcb/mod-page` only forwards a fixed key set
      `{:name :background :pixel-grid-color :pixel-grid-opacity}` and
      `process-change :mod-page` (changes.cljc:859) drops every other
      key — so `:cms-data` on the page would not survive the change
      pipeline without editing the shared changes.cljc.
    * `pcb/set-plugin-data` with `object-type :page` is the sanctioned
      generic per-page extension point; it is already undo/redo-safe
      and namespace-isolated, requiring zero shared-file edits.

  Storage location (per process-change :set-plugin-data, changes.cljc:901):
    page :plugin-data :ovion \"cms-data\"  →  EDN string of cms-data.

  The read helper `read-cms-data` parses the slot back into a map; the
  menu (`ui.workspace.sidebar.options.menus.cms`) consumes it via a
  derived ref. Exported constants `cms-namespace` / `cms-key` keep the
  menu and a future publish pipeline consistent."
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as pcb]
   [app.common.types.collection :as ctcol]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.undo :as dwu]
   [beicon.v2.core :as rx]
   [cljs.reader :as reader]
   [potok.v2.core :as ptk]))

;; --- Plugin-data slot constants --------------------------------------------

(def cms-namespace
  "Plugin-data namespace keyword under which CMS data is stored on the
  page. Schema:plugin-data keys namespaces are keywords."
  :ovion)

(def cms-key
  "Plugin-data key (string) under `cms-namespace` for the cms-data map."
  "cms-data")

;; --- Read / write helpers --------------------------------------------------

(defn read-cms-data
  "Parse the page's plugin-data CMS slot back into a cms-data map.
  Accepts either a page map (reads `:plugin-data`) or a raw stored
  string. Returns empty-cms-data when the slot is absent or unparsable."
  ([]
   ctcol/empty-cms-data)
  ([page-or-str]
   (let [raw (if (map? page-or-str)
               (dm/get-in page-or-str [:plugin-data cms-namespace cms-key])
               page-or-str)]
     (if (or (nil? raw) (empty? raw))
       ctcol/empty-cms-data
       (try
         (reader/read-string raw)
         (catch :default _
           ctcol/empty-cms-data))))))

(defn- write-cms-data
  "Serialize a cms-data map to the plugin-data slot string form."
  [cms]
  (pr-str cms))

(defn- current-cms-data
  "Read the current page's CMS data from its plugin-data slot, defaulting
  to empty-cms-data when unset."
  [page]
  (if (nil? page)
    ctcol/empty-cms-data
    (read-cms-data page)))

(defn- commit-cms-data
  "Build and commit a changeset that writes `new-cms` to the current
  page's plugin-data CMS slot, inside one undo transaction. Returns an
  rx stream of potok events (or rx/empty when the page is nil)."
  [it state new-cms]
  (let [page     (dsh/lookup-page state)
        file-id  (:current-file-id state)
        file-data (dsh/lookup-file-data state file-id)]
    (if (nil? page)
      (rx/empty)
      (let [undo-id (js/Symbol)
            changes (-> (pcb/empty-changes it)
                        (pcb/with-file-data file-data)
                        (pcb/with-page page)
                        (pcb/set-plugin-data :page
                                             (:id page)
                                             cms-namespace
                                             cms-key
                                             (write-cms-data new-cms)))]
        (rx/of (dwu/start-undo-transaction undo-id)
               (dch/commit-changes changes)
               (dwu/commit-undo-transaction undo-id))))))

;; --- Events ----------------------------------------------------------------

(defn create-collection
  "Create a new empty collection named `name` (defaults to \"Collection\")."
  ([]
   (create-collection nil))
  ([name]
   (ptk/reify ::create-collection
     ptk/WatchEvent
     (watch [it state _]
       (let [page (dsh/lookup-page state)
             cms  (current-cms-data page)
             col  (ctcol/make-collection name)]
         (commit-cms-data it state (ctcol/add-collection cms col)))))))

(defn add-collection-field
  "Add a field named `name` of `type` to collection `collection-id`.
  `type` is a schema:field-type keyword."
  [{:keys [collection-id name type]}]
  (ptk/reify ::add-collection-field
    ptk/WatchEvent
    (watch [it state _]
      (let [page  (dsh/lookup-page state)
            cms   (current-cms-data page)
            field (ctcol/make-field name type)]
        (commit-cms-data it state (ctcol/add-field cms collection-id field))))))

(defn add-collection-item
  "Add an empty item to collection `collection-id`."
  [{:keys [collection-id]}]
  (ptk/reify ::add-collection-item
    ptk/WatchEvent
    (watch [it state _]
      (let [page (dsh/lookup-page state)
            cms  (current-cms-data page)
            item (ctcol/make-item)]
        (commit-cms-data it state (ctcol/add-item cms collection-id item))))))

(defn bind-shape
  "Bind shape `shape-id` to `collection-id`.`field-id`. Replaces any
  existing binding for `shape-id` (a shape binds to at most one
  field). `item-id` is optional (nil for collection-list repeatable
  regions)."
  [{:keys [shape-id collection-id field-id item-id]}]
  (ptk/reify ::bind-shape
    ptk/WatchEvent
    (watch [it state _]
      (let [page    (dsh/lookup-page state)
            cms     (current-cms-data page)
            binding (ctcol/make-binding shape-id collection-id field-id item-id)]
        (commit-cms-data it state (ctcol/add-binding cms binding))))))

(defn unbind-shape
  "Remove the binding for shape `shape-id`, if any."
  [{:keys [shape-id]}]
  (ptk/reify ::unbind-shape
    ptk/WatchEvent
    (watch [it state _]
      (let [page (dsh/lookup-page state)
            cms  (current-cms-data page)]
        (commit-cms-data it state (ctcol/remove-binding cms shape-id))))))