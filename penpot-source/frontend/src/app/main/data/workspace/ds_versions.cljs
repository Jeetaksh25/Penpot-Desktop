;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.ds-versions
  "Design-system-scoped version snapshots (ALL_APPS_PARITY P2.15).

  Captures named snapshots of the current file's component library and
  stores them on FILE-DATA plugin-data (namespace `:ovion`, keys
  `\"ds-versions\"` and `\"ds-active-version\"`), so they are undo/redo-
  safe and survive save/reload. Each snapshot records the component
  id->name map at capture time, plus a name, a uuid, a created-at
  millis, and a component-count. An `active version` pointer (a uuid
  string or nil) marks which snapshot the file currently considers
  authoritative; the menu uses it for the \"current\" pill and the
  \"Set active\" action.

  Scope (IMPORTANT — read before extending):
    * This module deliberately delivers the honest, production-safe
      subset of the parity gap. It captures, lists, points, and
      compares snapshots. There is intentionally NO \"restore = replace
      all components\" changeset: rebuilding the live component library
      from a snapshot requires shared-schema work (component instances
      reference shapes by id; a wholesale swap would orphan instance
      references) that the parity doc explicitly defers. The
      active-version pointer + compare view is the deliverable.
    * Cross-file sharing and branching are likewise SCOPE-DEFERRED per
      the parity doc and are NOT attempted here: the snapshot vector
      lives on the single file that authored it.

  Why plugin-data and not a new file-data key:
    * process-change for :set-plugin-data is the sanctioned generic
      file-level extension point (object-type :file); it is already
      undo/redo-safe and namespace-isolated, requiring zero shared-file
      edits (mirrors the shape-level slot in
      data/workspace/notes.cljs, but at the file level).

  Storage location:
    file-data :plugin-data :ovion \"ds-versions\"      -> EDN string of the snapshot vector.
    file-data :plugin-data :ovion \"ds-active-version\"  -> EDN string of a uuid, or nil.

  Snapshot shape:
    {:id <uuid> :name <string> :created-at <millis>
     :component-count <int> :components <id->name map> :active <bool>}

  The `:active` flag on each snapshot is a derived convenience
  computed at read time from the active-version pointer; it is NOT
  persisted (only the pointer is persisted), so `read-ds-versions`
  stamps it on after parsing."
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as pcb]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.undo :as dwu]
   [beicon.v2.core :as rx]
   [cljs.reader :as reader]
   [clojure.string :as cstr]
   [potok.v2.core :as ptk]))

;; --- Plugin-data slot constants --------------------------------------------

(def ds-versions-namespace
  "Plugin-data namespace keyword under which DS-version slots are stored
  on the file. Schema:plugin-data keys namespaces are keywords."
  :ovion)

(def ds-versions-key      "ds-versions")
(def ds-active-version-key "ds-active-version")

;; --- Read helpers -----------------------------------------------------------

(defn- components->id-name
  "Project a `components` map (id -> component map, as stored on
  file-data) to an id -> name map suitable for a snapshot. Missing names
  fall back to the id stringified so the snapshot never loses a row."
  [components]
  (into {}
        (map (fn [[id comp]]
               [id (or (:name comp) (str id))]))
        components))

(defn read-active-version
  "Return the active-version uuid for `file-data`, or nil when the slot
  is absent / blank / unparsable."
  [file-data]
  (let [raw (dm/get-in file-data [:plugin-data ds-versions-namespace ds-active-version-key])]
    (when (and (some? raw) (not-empty raw))
      (try
        (let [v (reader/read-string raw)]
          (if (uuid? v) v nil))
        (catch :default _
          nil)))))

(defn read-ds-versions
  "Parse the file-data ds-versions slot back into a vector of snapshot
  maps. Returns [] when the slot is absent / unparsable. Stamps the
  derived `:active` flag on each snapshot from the active-version
  pointer (see `read-active-version`). The `file-data` arg is the
  file-data map (NOT the whole state)."
  [file-data]
  (let [raw   (dm/get-in file-data [:plugin-data ds-versions-namespace ds-versions-key])
        active-id (read-active-version file-data)
        parsed
        (if (or (nil? raw) (empty? raw))
          []
          (try
            (let [v (reader/read-string raw)]
              (if (vector? v) v []))
            (catch :default _
              [])))]
    (mapv #(assoc % :active (= (:id %) active-id)) parsed)))

;; --- Diff helper ------------------------------------------------------------

(defn snapshot-diff
  "Pure diff of `snapshot`'s `:components` (id->name) against the current
  `file-data`'s live `:components` (id->name). Returns a map with three
  vectors: `:added` (ids present now but not in the snapshot), `:removed`
  (ids in the snapshot but gone now), `:renamed` (ids present in both
  whose name changed — each entry is `[id old-name new-name]`). The menu
  calls this directly to render a small compare summary; no modal."
  [file-data snapshot]
  (let [live      (components->id-name (:components file-data))
        old       (:components snapshot)
        live-ids  (set (keys live))
        old-ids   (set (keys old))
        added     (into [] (sort (remove (fn [id] (contains? old-ids id)) live-ids)))
        removed   (into [] (sort (remove (fn [id] (contains? live-ids id)) old-ids)))
        renamed   (into []
                        (keep (fn [id]
                                (when (and (contains? live-ids id)
                                           (not= (get old id) (get live id)))
                                  [id (get old id) (get live id)])))
                        (sort old-ids))]
    {:added   added
     :removed removed
     :renamed renamed}))

;; --- Commit helper ----------------------------------------------------------

(defn- commit-ds-versions
  "Build and commit a changeset that writes `new-versions` to the file's
  ds-versions slot and (optionally) `new-active-id` to the active-version
  slot, inside one undo transaction. Returns an rx stream of potok
  events. `new-active-id` nil clears the active pointer. Mirrors
  notes.cljs's `commit-widget-notes` but at the file level (object-type
  :file, the 3-arg `set-plugin-data` arity)."
  [it state new-versions new-active-id]
  (let [file-id    (:current-file-id state)
        file-data (dsh/lookup-file-data state file-id)]
    (if (nil? file-data)
      (rx/empty)
      (let [undo-id (js/Symbol)
            versions-str (pr-str new-versions)
            active-str   (when new-active-id (pr-str new-active-id))
            base-changes
            (-> (pcb/empty-changes it)
                (pcb/with-file-data file-data))
            changes
            (-> base-changes
                (pcb/set-plugin-data ds-versions-namespace ds-versions-key versions-str)
                (pcb/set-plugin-data ds-versions-namespace ds-active-version-key active-str))]
        (rx/of (dwu/start-undo-transaction undo-id)
               (dch/commit-changes changes)
               (dwu/commit-undo-transaction undo-id))))))

;; --- Events ----------------------------------------------------------------

(defn create-snapshot
  "Capture a named snapshot of the current file's component library and
  append it to the ds-versions vector, setting it active. `name` blank
  defaults to \"Version N\" where N is the 1-based index of the new
  snapshot. Commits via one undo transaction. Returns an rx stream."
  [{:keys [name]}]
  (ptk/reify ::create-snapshot
    ptk/WatchEvent
    (watch [it state _]
      (let [file-id    (:current-file-id state)
            file-data  (dsh/lookup-file-data state file-id)
            components (:components file-data)
            existing   (read-ds-versions file-data)
            idx        (inc (count existing))
            snap       {:id            (uuid/next)
                        :name          (if (or (nil? name) (cstr/blank? name))
                                        (str "Version " idx)
                                        name)
                        :created-at    (js/Date.now)
                        :component-count (count components)
                        :components    (components->id-name components)}
            new-vec    (conj existing (dissoc snap :active))]
        (commit-ds-versions it state new-vec (:id snap))))))

(defn set-active-version
  "Set the active-version pointer to `id` (a uuid, or nil to clear).
  Commits via one undo transaction."
  [{:keys [id]}]
  (ptk/reify ::set-active-version
    ptk/WatchEvent
    (watch [it state _]
      (let [file-id    (:current-file-id state)
            file-data  (dsh/lookup-file-data state file-id)
            versions   (read-ds-versions file-data)
            ;; Clear the active pointer if the id is not among the
            ;; snapshots, so the pointer never dangles.
            active-id  (when (and (some? id)
                                  (some #(= (:id %) id) versions))
                         id)]
        (commit-ds-versions it state versions active-id)))))

(defn rename-snapshot
  "Rename the snapshot with id `id` to `name`. No-op when the id is not
  found. Preserves the active pointer. Commits via one undo
  transaction."
  [{:keys [id name]}]
  (ptk/reify ::rename-snapshot
    ptk/WatchEvent
    (watch [it state _]
      (let [file-id    (:current-file-id state)
            file-data  (dsh/lookup-file-data state file-id)
            versions   (read-ds-versions file-data)
            active-id  (read-active-version file-data)
            new-versions
            (mapv (fn [snap]
                    (if (= (:id snap) id)
                      (assoc snap :name (or name (:name snap)))
                      snap))
                  versions)]
        (commit-ds-versions it state new-versions active-id)))))

(defn delete-snapshot
  "Remove the snapshot with id `id` from the ds-versions vector. Clears
  the active pointer if it was pointing at the deleted snapshot.
  Commits via one undo transaction."
  [{:keys [id]}]
  (ptk/reify ::delete-snapshot
    ptk/WatchEvent
    (watch [it state _]
      (let [file-id    (:current-file-id state)
            file-data  (dsh/lookup-file-data state file-id)
            versions   (read-ds-versions file-data)
            active-id  (read-active-version file-data)
            new-versions (filterv #(not= (:id %) id) versions)
            new-active  (when (not= active-id id) active-id)]
        (commit-ds-versions it state new-versions new-active)))))