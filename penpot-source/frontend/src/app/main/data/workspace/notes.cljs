;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.notes
  "Per-widget Notes / Specifications fields (ALL_APPS_PARITY P2.31).

  Persists a free-form + structured spec annotation map on each shape
  through the standard changes pipeline as shape-level plugin-data, so
  edits are undo/redo-safe and survive save/reload. The whole widget-notes
  map is serialized (`pr-str`) to a string and stored under namespace
  `:ovion` key `\"widget-notes\"` on the shape's `:plugin-data` slot — the
  schema:plugin-data value type is `string`, so the map is serialized.

  Why plugin-data and not a new shape key:
    * `pcb/mod-shape` only forwards a fixed key set and process-change
      :mod-object (changes.cljc) drops every other key, so a custom
      `:widget-notes` field on the shape would not survive the change
      pipeline without editing the shared changes.cljc.
    * `pcb/set-plugin-data` with `object-type :shape` is the sanctioned
      generic per-shape extension point; it is already undo/redo-safe
      and namespace-isolated, requiring zero shared-file edits (mirrors
      data/workspace/collections.cljs for the page-level CMS slot).

  Storage location (per process-change :set-plugin-data, changes.cljc):
    shape :plugin-data :ovion \"widget-notes\"  ->  EDN string of the map.

  The widget-notes map shape:
    {:requirements \"...\"   ; free-form requirement notes
     :copy         \"...\"   ; the exact copy / label text the widget shows
     :colors       \"...\"   ; color / token references
     :placement    \"...\"}   ; placement / layout instructions

  The read helper `read-widget-notes` parses the slot back into a map;
  the menu (`ui.workspace.sidebar.options.menus.notes`) consumes it via
  a derived ref. Exported constants `notes-namespace` / `notes-key` keep
  the menu and a future publish pipeline consistent.

  Publish: the notes are surfaced in the Inspect panel through the
  `copy-spec` action in the notes menu and via a wiring spec for the
  HTML export metadata (the code-gen emitter lives in a forbidden shared
  namespace, so the export hook is specified rather than edited here)."
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

(def notes-namespace
  "Plugin-data namespace keyword under which widget notes are stored on
  the shape. Schema:plugin-data keys namespaces are keywords."
  :ovion)

(def notes-key
  "Plugin-data key (string) under `notes-namespace` for the widget-notes
  map."
  "widget-notes")

;; --- Map helpers ------------------------------------------------------------

(defn empty-notes
  "A fresh empty widget-notes map. Kept as a function (not a constant) so
  callers never mutate a shared reference."
  []
  {:requirements ""
   :copy         ""
   :colors       ""
   :placement    ""})

(def ^:private notes-fields
  [:requirements :copy :colors :placement])

(defn notes-fields-list
  "Return the ordered vector of widget-notes field keywords. Exposed so
  the menu and any publish hook iterate a single source of truth."
  []
 notes-fields)

(defn read-widget-notes
  "Parse a shape's plugin-data widget-notes slot back into a map. Accepts
  either a shape map (reads `:plugin-data`) or a raw stored string.
  Returns `empty-notes` when the slot is absent or unparsable, and
  guarantees every field key is present (fills missing with \"\")."
  ([]
   (empty-notes))
  ([shape-or-str]
   (let [raw (if (map? shape-or-str)
               (dm/get-in shape-or-str [:plugin-data notes-namespace notes-key])
               shape-or-str)
         parsed
         (if (or (nil? raw) (empty? raw))
           (empty-notes)
           (try
             (reader/read-string raw)
             (catch :default _
               (empty-notes))))]
     (into (empty-notes)
           (keep (fn [k] (when-let [v (get parsed k)] [k (str v)]))
                 notes-fields)))))

(defn- write-widget-notes
  "Serialize a widget-notes map to the plugin-data slot string form. Nil
  or an all-empty map serializes to nil so the slot is cleared (a nil
  value in :set-plugin-data removes the key, per process-change)."
  [notes]
  (let [notes (into (empty-notes) notes)]
    (if (every? #(empty? (str (get notes %))) notes-fields)
      nil
      (pr-str notes))))

;; --- Commit helper ----------------------------------------------------------

(defn- commit-widget-notes
  "Build and commit a changeset that writes `new-notes` to every shape in
  `shape-ids`'s plugin-data widget-notes slot, inside one undo transaction.
  Returns an rx stream of potok events (or rx/empty when there are no
  shapes). Mirrors collections.cljs's `commit-cms-data` but for shapes
  (the :set-plugin-data change for object-type :shape requires a page-id)."
  [it state shape-ids new-notes]
  (let [page-id    (:current-page-id state)
        file-id    (:current-file-id state)
        file-data (dsh/lookup-file-data state file-id)
        page      (when page-id (dsh/lookup-page state page-id))]
    (if (or (nil? page) (empty? shape-ids))
      (rx/empty)
      (let [undo-id (js/Symbol)
            value   (write-widget-notes new-notes)
            changes
            (reduce
             (fn [changes shape-id]
               (-> changes
                   (pcb/set-plugin-data :shape shape-id page-id
                                        notes-namespace notes-key value)))
             (-> (pcb/empty-changes it page-id)
                 (pcb/with-file-data file-data)
                 (pcb/with-page page))
             shape-ids)]
        (rx/of (dwu/start-undo-transaction undo-id)
               (dch/commit-changes changes)
               (dwu/commit-undo-transaction undo-id))))))

;; --- Events ----------------------------------------------------------------

(defn set-widget-notes
  "Replace the widget-notes map on every shape in `shape-ids` with
  `new-notes` (a map with :requirements / :copy / :colors / :placement).
  Commit is undo/redo-safe via one undo transaction wrapping all shapes."
  [{:keys [shape-ids notes]}]
  (ptk/reify ::set-widget-notes
    ptk/WatchEvent
    (watch [it state _]
      (commit-widget-notes it state shape-ids notes))))

(defn update-widget-notes
  "Apply `update-fn` to the current widget-notes map of every shape in
  `shape-ids` and persist the result. `update-fn` receives the parsed
  notes map and returns a new notes map. Convenient for single-field
  edits without a read-modify-write race in the caller."
  [{:keys [shape-ids update-fn]}]
  (ptk/reify ::update-widget-notes
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id (:current-page-id state)
            objects (dsh/lookup-page-objects state page-id)]
        (if (or (nil? objects) (empty? shape-ids))
          (rx/empty)
          ;; All selected shapes share the same notes value when editing
          ;; via the sidebar (the menu shows the first shape's notes),
          ;; so we read once from the first selected shape and apply
          ;; update-fn to that shared draft, then write the result to
          ;; every selected shape. This matches the menu's "edits apply
          ;; to all selected" contract.
          (let [first-shape (get objects (first shape-ids))
                current     (read-widget-notes first-shape)
                new-notes   (update-fn current)]
            (commit-widget-notes it state shape-ids new-notes)))))))