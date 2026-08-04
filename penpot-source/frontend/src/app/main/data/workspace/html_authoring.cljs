;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.html-authoring
  "HTML Authoring fields (ALL_APPS_PARITY P0.13).

  Persists three small HTML-authoring hints on each shape through the
  standard changes pipeline as shape-level plugin-data, so edits are
  undo/redo-safe and survive save/reload. Each value is serialized
  (`pr-str`) to a string and stored under namespace `:ovion` on the
  shape's `:plugin-data` slot — the schema:plugin-data value type is
  `string`.

  Why plugin-data and not a new shape key:
    * `pcb/mod-shape` only forwards a fixed key set and process-change
      :mod-object (changes.cljc) drops every other key, so a custom
      field on the shape would not survive the change pipeline without
      editing the shared changes.cljc.
    * `pcb/set-plugin-data` with object-type :shape is the sanctioned
      generic per-shape extension point; it is already undo/redo-safe
      and namespace-isolated, requiring zero shared-file edits (mirrors
      data/workspace/notes.cljs for the per-widget notes slot).

  Storage location (per process-change :set-plugin-data, changes.cljc):
    shape :plugin-data :ovion \"semantic-tag\"  ->  EDN string of the tag.
    shape :plugin-data :ovion \"css-class\"     ->  EDN string of the class.
    shape :plugin-data :ovion \"custom-css\"    ->  EDN string of the css.

  The read helpers parse each slot back into a string (or nil); the menu
  (`ui.workspace.sidebar.options.menus.html-authoring`) consumes them.
  Exported constants keep the menu and a future publish pipeline
  consistent.

  Scope: these slots are authoring metadata surfaced in the Inspect
  panel and the HTML export; they do NOT alter the rendered shape. The
  publish hook that emits them in HTML export metadata lives in a
  forbidden shared namespace, so it is specified rather than edited
  here."
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

(def html-authoring-namespace
  "Plugin-data namespace keyword under which HTML-authoring slots are
  stored on the shape. Schema:plugin-data keys namespaces are keywords."
  :ovion)

(def semantic-tag-key "semantic-tag")
(def css-class-key    "css-class")
(def custom-css-key   "custom-css")

;; --- Read helpers -----------------------------------------------------------

(defn- read-slot
  "Parse a single plugin-data slot on `shape` under `key`. Returns the
  stored string, or nil when the slot is absent / blank / unparsable.
  `pr-str` of a string yields an EDN string literal (e.g. `\"div\"`), so
  `reader/read-string` round-trips it back to a string. Guarded so a
  corrupt value never throws in the UI."
  [shape key]
  (let [raw (dm/get-in shape [:plugin-data html-authoring-namespace key])]
    (when (and (some? raw) (not-empty raw))
      (try
        (let [v (reader/read-string raw)]
          (if (string? v) v (str v)))
        (catch :default _
          nil)))))

(defn read-semantic-tag
  "Return the semantic-tag slot for `shape` (string or nil)."
  [shape]
  (read-slot shape semantic-tag-key))

(defn read-css-class
  "Return the css-class slot for `shape` (string or nil)."
  [shape]
  (read-slot shape css-class-key))

(defn read-custom-css
  "Return the custom-css slot for `shape` (string or nil)."
  [shape]
  (read-slot shape custom-css-key))

;; --- Commit helper ----------------------------------------------------------

(defn- commit-slot
  "Build and commit a changeset that writes `value` to every shape in
  `shape-ids`'s plugin-data slot `key`, inside one undo transaction.
  Returns an rx stream of potok events (or rx/empty when there are no
  shapes). Mirrors notes.cljs's `commit-widget-notes` but for a single
  string slot. When `value` is blank/nil, stores nil so the slot is
  cleared (a nil value in :set-plugin-data removes the key, per
  process-change)."
  [it state shape-ids key value]
  (let [page-id    (:current-page-id state)
        file-id    (:current-file-id state)
        file-data (dsh/lookup-file-data state file-id)
        page      (when page-id (dsh/lookup-page state page-id))]
    (if (or (nil? page) (empty? shape-ids))
      (rx/empty)
      (let [undo-id (js/Symbol)
            stored  (if (or (nil? value) (empty? (str value)))
                      nil
                      (pr-str (str value)))
            changes
            (reduce
             (fn [changes shape-id]
               (-> changes
                   (pcb/set-plugin-data :shape shape-id page-id
                                        html-authoring-namespace key stored)))
             (-> (pcb/empty-changes it page-id)
                 (pcb/with-file-data file-data)
                 (pcb/with-page page))
             shape-ids)]
        (rx/of (dwu/start-undo-transaction undo-id)
               (dch/commit-changes changes)
               (dwu/commit-undo-transaction undo-id))))))

;; --- Events ----------------------------------------------------------------

(defn set-semantic-tag
  "Set the semantic-tag slot on every shape in `shape-ids` to `value`
  (a string or nil). Commit is undo/redo-safe via one undo transaction
  wrapping all shapes. Blank/nil clears the slot."
  [{:keys [shape-ids value]}]
  (ptk/reify ::set-semantic-tag
    ptk/WatchEvent
    (watch [it state _]
      (commit-slot it state shape-ids semantic-tag-key value))))

(defn set-css-class
  "Set the css-class slot on every shape in `shape-ids` to `value`.
  Commit is undo/redo-safe via one undo transaction wrapping all
  shapes. Blank/nil clears the slot."
  [{:keys [shape-ids value]}]
  (ptk/reify ::set-css-class
    ptk/WatchEvent
    (watch [it state _]
      (commit-slot it state shape-ids css-class-key value))))

(defn set-custom-css
  "Set the custom-css slot on every shape in `shape-ids` to `value`.
  Commit is undo/redo-safe via one undo transaction wrapping all
  shapes. Blank/nil clears the slot."
  [{:keys [shape-ids value]}]
  (ptk/reify ::set-custom-css
    ptk/WatchEvent
    (watch [it state _]
      (commit-slot it state shape-ids custom-css-key value))))