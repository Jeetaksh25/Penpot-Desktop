;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.ai-branches
  "AI Agent branches (ALL_APPS_PARITY P2.08).

  A branch tree of concurrent / iterative AI explorations, persisted on
  FILE-DATA plugin-data (namespace `:ovion`, key `\"ai-branches\"`) so it
  is undo/redo-safe and survives save/reload — exactly the pattern
  `prompt_library.cljs` uses for user presets.

  Storage location:
    file-data :plugin-data :ovion \"ai-branches\" -> EDN string of the
    branch vector.

  Branch shape:
    {:id         <string>    unique (uuid string)
     :parent-id  <string?>   nil for a root branch, else the parent branch id
     :prompt     <string>    the prompt that produced this branch
     :status     <keyword>   :active | :done | :error | :discarded
     :result     <string?>   short human label of the result (frame title /
                             error text); nil while :active
     :created    <number>    js/Date.now at creation (for stable ordering)

  The tree is a flat vector; the viewer reconstructs the tree from
  :parent-id. A branch with status :active is being generated right now
  (coral indicator); :done is finished (grey); :error failed; :discarded
  is hidden from the active tree but kept for undo safety.

  Byte-identical-when-inactive: when no branch events are ever emitted, the
  plugin-data slot is absent and `read-branches` returns [] — zero impact
  on files that never use agent branches."
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as pcb]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.undo :as dwu]
   [beicon.v2.core :as rx]
   [cljs.reader :as reader]
   [potok.v2.core :as ptk]))

;; --- Plugin-data slot constants --------------------------------------------

(def branches-namespace
  "Plugin-data namespace keyword under which the AI branch tree is stored
  on the file. Mirrors `prompt-library-namespace`."
  :ovion)

(def branches-key
  "Plugin-data key (string) under `branches-namespace` for the branch
  vector."
  "ai-branches")

;; --- Read helpers -----------------------------------------------------------

(defn read-branches
  "Parse the file-data ai-branches slot back into a vector of branch maps.
  Returns [] when the slot is absent / unparsable. The `file-data` arg is
  the file-data map (NOT the whole state)."
  [file-data]
  (let [raw (dm/get-in file-data [:plugin-data branches-namespace branches-key])]
    (if (or (nil? raw) (empty? raw))
      []
      (try
        (let [v (reader/read-string raw)]
          (if (vector? v) v []))
        (catch :default _
          [])))))

(defn active-branches
  "Return the subset of branches that are not :discarded, in creation
  order. Convenience for the viewer (discarded branches are kept for undo
  but hidden from the active tree)."
  [branches]
  (into [] (remove #(= :discarded (:status %))) branches))

(defn branches->tree
  "Build a nested tree `{:branch <branch> :children [<node> ...]}` from the
  flat branch vector, rooted at the branches whose :parent-id is nil.
  Discarded branches are excluded. Children are ordered by :created."
  [branches]
  (let [by-parent (group-by :parent-id (active-branches branches))]
    (letfn [(node [b]
              {:branch b
               :children (mapv node (sort-by :created (get by-parent (:id b) [])))})]
      (mapv node (sort-by :created (get by-parent nil []))))))

;; --- Commit helper ----------------------------------------------------------

(defn- commit-branches
  "Build and commit a changeset that writes `new-branches` to the file's
  ai-branches slot, inside one undo transaction. Returns an rx stream of
  potok events. Mirrors `prompt_library/commit-user-presets`."
  [it state new-branches]
  (let [file-id    (:current-file-id state)
        file-data (dsh/lookup-file-data state file-id)]
    (if (nil? file-data)
      (rx/empty)
      (let [undo-id (uuid/next)
            ;; nil out the slot when the branch vector is empty so the
            ;; plugin-data map doesn't carry a stale empty-string entry.
            value   (if (empty? new-branches) nil (pr-str new-branches))
            changes (-> (pcb/empty-changes it)
                        (pcb/with-file-data file-data)
                        (pcb/set-plugin-data branches-namespace
                                              branches-key
                                              value))]
        (rx/of (dwu/start-undo-transaction undo-id)
               (dch/commit-changes changes)
               (dwu/commit-undo-transaction undo-id))))))

;; --- Internal: mutate + commit ---------------------------------------------

(defn- replace-branches
  "Internal WatchEvent helper. `xf` is applied to the current branch vector
  and the result is committed in one undo transaction."
  [xf]
  (ptk/reify ::replace-branches
    ptk/WatchEvent
    (watch [it state _]
      (let [file-id    (:current-file-id state)
            file-data (dsh/lookup-file-data state file-id)
            existing  (read-branches file-data)
            new-vec   (xf existing)]
        (when (not= existing new-vec)
          (commit-branches it state new-vec))))))

;; --- Events ----------------------------------------------------------------

(defn add-branch
  "Append a new branch. `{:id :prompt :parent-id :created}` — `:id` is the
  caller-owned unique branch id (a uuid string); `:parent-id` nil creates a
  root branch; `:created` defaults to `js/Date.now`. The branch starts as
  :active. Returns an rx stream. The caller owns the id so it can later
  emit `mark-branch-done` / `mark-branch-error` / `discard-branch` with the
  SAME id after the generation resolves."
  [{:keys [id prompt parent-id created]}]
  (let [bid (str id)]
    (replace-branches
     (fn [existing]
       (conj existing
             {:id        bid
              :parent-id parent-id
              :prompt    (str prompt)
              :status    :active
              :result    nil
              :created   (or created (js/Date.now))})))))

(defn mark-branch-done
  "Set branch `id` to :done with `result` (a short human label). No-op when
  the branch is not found."
  [{:keys [id result]}]
  (replace-branches
   (fn [existing]
     (mapv (fn [b]
             (if (= (:id b) id)
               (assoc b :status :done :result (str result))
               b))
           existing))))

(defn mark-branch-error
  "Set branch `id` to :error with `result` (the error text). No-op when the
  branch is not found."
  [{:keys [id result]}]
  (replace-branches
   (fn [existing]
     (mapv (fn [b]
             (if (= (:id b) id)
               (assoc b :status :error :result (str result))
               b))
           existing))))

(defn discard-branch
  "Mark branch `id` (and all its descendants) as :discarded. They are kept
  in the vector for undo safety but hidden from the active tree."
  [{:keys [id]}]
  (replace-branches
   (fn [existing]
     (let [;; collect the id + all descendant ids
           by-parent (group-by :parent-id existing)
           descendants (fn descendants [pid]
                         (let [direct (map :id (get by-parent pid []))]
                           (reduce into direct (map descendants direct))))
           to-hide (into #{id} (descendants id))]
       (mapv (fn [b]
               (if (contains? to-hide (:id b))
                 (assoc b :status :discarded)
                 b))
             existing)))))

(defn clear-branches
  "Remove every branch (root + children). One undo transaction."
  []
  (replace-branches (fn [_] [])))