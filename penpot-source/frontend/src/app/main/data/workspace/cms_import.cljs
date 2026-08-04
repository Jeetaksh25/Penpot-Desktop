;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.cms-import
  "CMS platform importer (ALL_APPS_PARITY P2.05) — data layer.

  Invokes the Rust `import_cms_platform` Tauri command to fetch a remote
  CMS (WordPress / Webflow / Contentful) and normalize its content model
  into the Ovion CMS collection shape, then builds real Ovion collections
  on the current page from the normalized result and commits them through
  the same plugin-data slot `data.workspace.collections` uses.

  Why build + commit in one pass (instead of emitting the existing
  `create-collection` / `add-collection-field` / `add-collection-item`
  events one-per-step):
    * The existing events create EMPTY items (`ctcol/make-item` has no
      value slot) and are fire-and-forget potok events — there is no way
      to get the freshly-created collection's id back to then add fields
      to it, let alone set an item's field values, which the import needs.
    * The import's whole value is populating items with the remote content
      (post titles, category names, multi-reference term-id links), so it
      must build the full `cms-data` map in memory and commit it once.
    * This reuses the PUBLIC pure helpers in `app.common.types.collection`
      (`make-collection` / `make-field` / `make-item` / `add-collection` /
      `add-field` / `add-item`) and the SAME commit mechanism
      (`pcb/set-plugin-data` on the page `:ovion`/`cms-data` slot inside
      one undo transaction) that `collections.cljs` uses — the only thing
      duplicated is the ~10-line `commit-cms-data` wrapper, kept private
      here. The result is one undo step for the whole import (better UX
      than dozens of per-field/per-item events) and real populated items.

  The remote multi-reference values (WP term ids on posts) are resolved
  to Ovion item ids via the `wp_id` the Rust side carries on each
  Categories / Tags item; the `wp_id` is dropped before committing."

  (:require
   ["@tauri-apps/api/core" :refer [invoke]]
   [app.common.data :as d]
   [app.common.files.changes-builder :as pcb]
   [app.common.types.collection :as ctcol]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.collections :as dwc]
   [app.main.data.workspace.undo :as dwu]
   [app.main.store :as st]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]
   [promesa.core :as p]))

;; ── State events (progress + result) ──────────────────────────────────────

(defn cms-import-progress
  "Event carrying a human-readable progress message (shown in the UI)."
  [message]
  (ptk/reify ::cms-import-progress
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:cms-import :status] {:state :progress :message message}))))

(defn cms-import-succeeded
  "Event carrying the import result summary.
  `summary` = `{:collections N :items M :platform \"wordpress\"}`."
  [summary]
  (ptk/reify ::cms-import-succeeded
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:cms-import :status] {:state :succeeded :summary summary}))))

(defn cms-import-failed
  "Event carrying the import error string."
  [error]
  (ptk/reify ::cms-import-failed
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:cms-import :status] {:state :failed :error error}))))

(defn cms-import-reset
  "Clear the import status (e.g. when the user starts a new import)."
  []
  (ptk/reify ::cms-import-reset
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:cms-import :status] nil))))

;; ── Plugin-data commit (mirrors collections.cljs/commit-cms-data) ─────────

(defn- commit-cms-data
  "Build and commit a changeset writing `new-cms` to the current page's
  plugin-data CMS slot (`:ovion`/`cms-data`) inside one undo transaction.
  Mirrors the private `commit-cms-data` in `data.workspace.collections`
  exactly so the committed shape + undo behavior are identical."
  [it state new-cms]
  (let [page      (dsh/lookup-page state)
        file-id   (:current-file-id state)
        file-data (dsh/lookup-file-data state file-id)]
    (if (nil? page)
      (rx/empty)
      (let [undo-id (js/Symbol)
            changes (-> (pcb/empty-changes it)
                        (pcb/with-file-data file-data)
                        (pcb/with-page page)
                        (pcb/set-plugin-data :page
                                             (:id page)
                                             dwc/cms-namespace
                                             dwc/cms-key
                                             (pr-str new-cms)))]
        (rx/of (dwu/start-undo-transaction undo-id)
               (dch/commit-changes changes)
               (dwu/commit-undo-transaction undo-id))))))

;; ── Normalized → cms-data builder ─────────────────────────────────────────

(defn- field-type-keyword
  "Coerce a field type string from the Rust normalized JSON to the
  schema:field-type keyword. Defaults to `:text` for anything unknown
  so an unexpected remote field type never breaks the import."
  [s]
  (let [t (if (keyword? s) s (keyword s))]
    (if (contains? #{:text :image :number :date :color
                     :reference :multi-reference} t)
      t
      :text)))

(defn- build-cms-from-normalized
  "Build the full `cms-data` map (collections + populated items) from the
  Rust normalized import result, MERGED with the existing page cms-data
  (imported collections are appended after existing ones so re-import
  never destroys hand-authored collections). Returns the new cms-data
  map ready to commit.

  Collections are created in declaration order (Categories, Tags, Posts
  for WP) so referenced collections exist before referencing collections
  resolve their `reference-collection-id`. Multi-reference item values
  (vectors of remote term ids) are resolved to vectors of Ovion item
  ids via the `wp-id → item-id` map built for each referenced collection."
  [state normalized]
  (let [page (dsh/lookup-page state)
        existing (dwc/read-cms-data page)
        colls (:collections normalized)
        [new-colls _]
        (loop [remaining colls
               ref-name->coll-id {}
               wp-id-maps {}  ;; {coll-id {wp-id ovion-item-id}}
               acc []]
          (if (empty? remaining)
            [acc wp-id-maps]
            (let [ncoll (first remaining)
                  coll-id (random-uuid)
                  flds (:fields ncoll)
                  ovion-fields
                  (mapv (fn [fld]
                          (let [ftype (field-type-keyword (:type fld))
                                ref-name (:reference fld)]
                            (cond-> (ctcol/make-field (:name fld) ftype)
                              (and (#{:reference :multi-reference} ftype)
                                   (some? ref-name)
                                   (contains? ref-name->coll-id ref-name))
                              (assoc :reference-collection-id
                                     (get ref-name->coll-id ref-name)))))
                        flds)
                  field-name->id
                  (into {} (map (fn [f] [(:name f) (:id f)]) ovion-fields))
                  field-name->ref-id
                  (into {}
                        (for [f ovion-fields
                              :when (:reference-collection-id f)]
                          [(:name f) (:reference-collection-id f)]))
                  wp-id->item-id (volatile! {})
                  ovion-items
                  (mapv (fn [item]
                          (let [raw-fields (or (:fields item) {})
                                ovion-item (ctcol/make-item)
                                item-id (:id ovion-item)]
                            (when-let [wp-id (:wpId item)]
                              (vswap! wp-id->item-id assoc wp-id item-id))
                            (let [populated
                                  (reduce-kv
                                   (fn [acc fname value]
                                     (if-let [fid (get field-name->id (d/name fname))]
                                       (let [ref-coll-id (get field-name->ref-id (d/name fname))
                                             ref-map (when ref-coll-id
                                                       (get wp-id-maps ref-coll-id))
                                             resolved
                                             (if (and (vector? value) (seq value) ref-map)
                                               (mapv (fn [rid]
                                                       (get ref-map rid rid))
                                                     value)
                                               value)]
                                         (assoc acc fid resolved))
                                       acc))
                                   (:fields ovion-item)
                                   raw-fields)]
                              (assoc ovion-item :fields populated))))
                        (:items ncoll))
                  ovion-coll (assoc (ctcol/make-collection coll-id (:name ncoll))
                                    :fields ovion-fields
                                    :items ovion-items)]
              (recur (rest remaining)
                     (assoc ref-name->coll-id (:name ncoll) coll-id)
                     (if (seq @wp-id->item-id)
                       (assoc wp-id-maps coll-id @wp-id->item-id)
                       wp-id-maps)
                     (conj acc ovion-coll)))))]
    (-> existing
        (update :collections d/concat-vec new-colls))))

;; ── Public ptk event ───────────────────────────────────────────────────────

(defn run-cms-import
  "Import a CMS platform into Ovion CMS collections on the current page.
  `opts` = `{:platform :base-url :token}`, where `:platform` is one of
  `\"wordpress\"` / `\"webflow\"` / `\"contentful\"` (only WordPress is
  implemented). Invokes the Rust `import_cms_platform` command, builds
  real Ovion collections (with populated items) from the normalized
  result, and commits them via the page plugin-data slot. Emits
  `cms-import-progress` / `cms-import-succeeded` / `cms-import-failed`
  into the store so the UI can show progress + a result summary."
  [{:keys [platform base-url token]}]
  (ptk/reify ::run-cms-import
    ptk/WatchEvent
    (watch [it state _]
      (let [plat (or platform "wordpress")
            request (clj->js {:platform plat
                              :base_url (or base-url "")
                              :token    (or token nil)
                              :options  nil})
            on-ok
            (fn [result]
              (let [norm (js->clj result :keywordize-keys true)]
                (st/emit! (cms-import-progress
                           (str "Building " (count (:collections norm))
                                " collections…")))
                ;; Build + commit inside a store event so the changes
                ;; pipeline sees a FRESH state snapshot (the `state`
                ;; captured here is stale by the time the promise
                ;; resolves). The `cms-import-succeeded` event is
                ;; concatenated AFTER the commit changeset so the
                ;; summary reflects a committed import.
                (st/emit!
                 (ptk/reify ::commit-imported-collections
                   ptk/WatchEvent
                   (watch [it2 state2 _]
                     (let [new-cms (build-cms-from-normalized state2 norm)
                           colls (:collections norm)
                           n-colls (count colls)
                           n-items (reduce + 0 (map #(count (:items %)) colls))]
                       (rx/concat
                        (commit-cms-data it2 state2 new-cms)
                        (rx/of (cms-import-succeeded
                                {:platform plat
                                 :collections n-colls
                                 :items n-items})))))))))
            on-err
            (fn [err]
              (st/emit! (cms-import-failed (str err))))]
        (st/emit! (cms-import-progress
                   (str "Fetching " plat " content…")))
        ;; Detached promise — side-effects fire via st/emit! on resolve.
        ;; Mirrors `data.exports.publish/publish-current-site`.
        (-> (invoke "import_cms_platform" #js {:request request})
            (p/then on-ok)
            (p/catch on-err))
        (rx/empty)))))