;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.on-page-edit
  "On-page editing of live/published content (ALL_APPS_PARITY P1.24) — data layer.

  Feasible half: an on-page edit mode against a LOCAL preview (the
  exported/published HTML rendered in a sandboxed iframe), with
  contenteditable on CMS-bound elements + sync edits back to the
  project's CMS string table (the page's plugin-data cms-data slot).

  The live-cloud-host round-trip (editing the actual published cloud
  site) is DEFERRED post-hosting — the local-preview edit IS the
  feasible on-page editing surface. When the overlay is closed there
  is no change to state or rendering (byte-identical-when-closed).

  CMS string table: the page's cms-data map (`:collections` +
  `:bindings`), persisted by `app.main.data.workspace.collections` as
  page plugin-data under the `:ovion`/`cms-data` slot. A binding maps
  a shape-id to `{collection-id field-id item-id?}`. The 'CMS string'
  a bound text shape renders is the bound item's field value. On-page
  edit updates that field value through the standard changes pipeline
  (one undo transaction).

  Reuses (no shared-file edits):
    * `app.main.data.exports.publish/build-bundle-from-page` to derive
      the exported/published HTML for the current page (the same bundle
      the Ovion Cloud publish path produces).
    * `app.util.code-gen.common/shape->selector` to match exported
      elements to CMS-bound shapes — the selector is a stable CSS class
      token derived from the shape id's last 12 hex chars, emitted in
      the element's `class` attribute by `markup-html`.
    * `app.main.data.workspace.collections/read-cms-data` +
      `cms-namespace`/`cms-key` for reading + committing the CMS slot
      (mirrors the private `commit-cms-data` in both `collections` and
      `cms-import` — the sanctioned per-feature duplicate)."
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.main.data.changes :as dch]
   [app.main.data.exports.publish :as publish]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.collections :as dwc]
   [app.main.data.workspace.undo :as dwu]
   [app.util.code-gen.common :as cgc]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [app.main.store :as st]
   [okulary.core :as l]
   [potok.v2.core :as ptk]))

;; ── App-level state slot ───────────────────────────────────────────────────
;;
;; `[:on-page-edit :active?]` — boolean. Only the preview overlay mount
;; in `ui.workspace` reads it (gated). False/nil = closed = no change.

(defn on-page-edit-active?
  "Read the on-page-edit overlay active flag from `state`. Returns a
  boolean (nil/false = closed). Pure read, no side-effects."
  [state]
  (true? (get-in state [:on-page-edit :active?])))

(def on-page-edit-active
  "Derived ref over the on-page-edit overlay active flag. nil/false = closed.
  Defined in-module (mirrors localization.cljs's active-locale-ref +
  team_sharing.cljs's comments-mode-ref) so refs.cljs stays untouched. The
  preview overlay mount in workspace.cljs derefs this; closed = byte-identical."
  (l/derived on-page-edit-active? st/state))

(defn toggle-on-page-edit
  "Event: toggle the on-page edit preview overlay. With no arg, flips
  the current state; with `open?`, forces it. Purely additive — the
  flag is only read by the preview overlay mount in workspace.cljs, so
  closed = byte-identical to the prior render."
  ([]
   (toggle-on-page-edit nil))
  ([open?]
   (ptk/reify ::toggle-on-page-edit
     ptk/UpdateEvent
     (update [_ state]
       (let [next? (if (some? open?)
                     (boolean open?)
                     (not (on-page-edit-active? state)))]
         (assoc-in state [:on-page-edit :active?] next?))))))

;; ── CMS-bound key collection ───────────────────────────────────────────────

(defn read-cms-bound-keys
  "Collect the CMS keys bound to text shapes in the current page.
  Returns a vector of maps:
    {:shape-id :collection-id :field-id :item-id :selector :cms-key}
  where:
    * `:selector` is the stable CSS class token the exporter emits for
      the shape (used to tag the element contenteditable in the preview).
    * `:cms-key` is a string encoding `collection-id|field-id|item-id`
      (item-id segment omitted when nil) carried on `data-cms-key`.

  `cms-data` is the page's cms-data map (from `dwc/read-cms-data`).
  `objects` is the page's objects map. Only bindings whose shape is a
  text shape are returned. `:item-id` may be nil (collection-list
  repeatable templates — present so the menu self-hide check sees any
  CMS binding, but not tagged contenteditable in the preview). Nil-safe
  (empty/nil cms-data or objects -> [])."
  [cms-data objects]
  (let [objects  (or objects {})
        bindings (:bindings cms-data [])]
    (->> bindings
         (keep (fn [b]
                 (let [shape-id      (:shape-id b)
                       item-id       (:item-id b)
                       shape         (get objects shape-id)]
                   (when (and (some? shape)
                              (cfh/text-shape? shape))
                     (let [sel     (cgc/shape->selector shape)
                           cms-key (if (some? item-id)
                                     (dm/str (:collection-id b) "|"
                                             (:field-id b) "|" item-id)
                                     (dm/str (:collection-id b) "|"
                                             (:field-id b)))]
                       {:shape-id      shape-id
                        :collection-id (:collection-id b)
                        :field-id      (:field-id b)
                        :item-id       item-id
                        :selector      sel
                        :cms-key       cms-key})))))
         vec)))

;; ── Preview HTML construction ──────────────────────────────────────────────

(defn- escape-regex
  "Escape regex special chars in a literal string for safe embedding in
  a `js/RegExp` pattern."
  [s]
  (str/replace s #"[.*+?^${}()|[\]\\]" "\\$&"))

(defn- tag-element-contenteditable
  "In `html` string, find the first opening tag whose `class` attribute
  contains the class token `selector` and inject `contenteditable=\"true\"`
  + `data-cms-key=\"<cms-key>\"` attributes (right before the closing
  `>` of that tag), if not already present. Returns the (possibly
  modified) html string. Nil-safe (nil/empty html or selector -> html).

  The exporter (`markup-html`) emits `class=\"shape <type> <selector>…\"`
  for every shape, so matching the token within any `class=\"…\"` reliably
  targets the bound shape's wrapper element. The token is unique per
  shape (derived from the last 12 hex chars of the shape id) so the
  first match is the bound element."
  [html selector cms-key]
  (if (or (str/empty? html) (str/empty? selector))
    html
    (let [pattern (js/RegExp.
                   (dm/str "(<[^>]*\\bclass=\"[^\"]*\\b"
                           (escape-regex selector)
                           "\\b[^\"]*\"[^>]*)(>)")
                   "")
          key-attr (str/replace (str cms-key) "\"" "&quot;")]
      (if (.test pattern html)
        (.replace html pattern
                  (dm/str "$1 contenteditable=\"true\" data-cms-key=\""
                          key-attr "\"$2"))
        html))))

(defn build-preview-html
  "Build the on-page-edit preview HTML for `page-id` in `file-data`.
  Reuses `publish/build-bundle-from-page` to derive the exported/published
  HTML for the page, then post-processes it to add `contenteditable=\"true\"`
  + `data-cms-key=\"<key>\"` to the elements matching CMS-bound text
  shapes (matched by the stable selector class token the exporter
  emits). Only bindings WITH an `item-id` are tagged contenteditable
  (a specific item's field is the editable string); bindings without
  an item-id are repeatable templates and left read-only in the preview.

  Returns the html string, or nil when the page is empty (no exported
  bundle). Nil-safe (no CMS keys -> plain preview html)."
  [file-data page-id]
  (let [page   (dsh/get-page file-data page-id)
        bundle (publish/build-bundle-from-page page)]
    (if (nil? bundle)
      nil
      (let [html     (:index_html bundle)
            cms-data (dwc/read-cms-data page)
            objects  (:objects page)
            keys     (read-cms-bound-keys cms-data objects)]
        (if (empty? keys)
          html
          (reduce (fn [h k]
                    (if (some? (:item-id k))
                      (tag-element-contenteditable h (:selector k) (:cms-key k))
                      h))
                  html
                  keys))))))

;; ── Sync edit back to the project (CMS string table) ──────────────────────

(defn- update-item-field
  "Pure helper: in `cms`, set the item `item-id` in collection
  `collection-id` to carry `field-id` -> `value` in its `:fields` map.
  Returns a new cms-data map. Nil-safe (missing collection/item ->
  cms unchanged)."
  [cms collection-id field-id item-id value]
  (update cms :collections
          (fn [cols]
            (mapv (fn [c]
                    (if (not= (:id c) collection-id)
                      c
                      (update c :items
                              (fn [items]
                                (mapv (fn [it]
                                        (if (not= (:id it) item-id)
                                          it
                                          (assoc-in it [:fields field-id] value)))
                                      items)))))
                  cols))))

(defn- commit-cms-data
  "Build + commit a changeset writing `new-cms` to the current page's
  plugin-data CMS slot (`:ovion`/`cms-data`) inside one undo transaction.
  Mirrors the private `commit-cms-data` in `data.workspace.collections`
  (and the duplicate in `cms-import`) exactly so the committed shape +
  undo behavior are identical. Returns an rx stream of potok events
  (or `rx/empty` when the page is nil)."
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
                                             dwc/cms-namespace
                                             dwc/cms-key
                                             (pr-str new-cms)))]
        (rx/of (dwu/start-undo-transaction undo-id)
               (dch/commit-changes changes)
               (dwu/commit-undo-transaction undo-id))))))

(defn sync-cms-edit
  "Event: sync an on-page contenteditable edit back to the project's
  CMS string table. `cms-key` is a map
  `{:collection-id :field-id :item-id}` (item-id REQUIRED — bindings
  without item-id are not tagged contenteditable and never reach here).
  `new-text` is the edited string. Updates the bound item's field value
  through the changes pipeline (one undo). No-op (rx/empty) when
  item-id is nil or the page can't be resolved."
  [{:keys [collection-id field-id item-id] :as cms-key} new-text]
  (ptk/reify ::sync-cms-edit
    ptk/WatchEvent
    (watch [it state _]
      (if (nil? item-id)
        (rx/empty)
        (let [page    (dsh/lookup-page state)
              cms     (dwc/read-cms-data page)
              new-cms (update-item-field cms collection-id field-id item-id new-text)]
          (commit-cms-data it state new-cms))))))