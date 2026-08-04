;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.localization.events
  "P2.25 — Localization EVENT layer (ALL_APPS_PARITY).

  The mutation/commit half of the localization feature: the ptk events that
  build + commit plugin-data changes (locale list, per-shape locale-strings)
  in one undo transaction, and the app-level `set-active-locale` UpdateEvent.

  This lives in its own namespace — SEPARATE from the read/render namespace
  `app.main.data.workspace.localization` — so the render path
  (`app.main.ui.shapes.text`, required transitively by `app.main.data.changes`
  via features → render-wasm → render-wasm.api) never pulls
  `app.main.data.changes` back through localization (which would close a
  compile-time circular dependency). Only the workspace UI menu requires this
  events namespace; the renderer requires the read namespace alone."

  (:require
   [app.common.files.changes-builder :as pcb]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.localization :as loc]
   [app.main.data.workspace.undo :as dwu]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

;; --- Pure changes helpers ---------------------------------------------------

(defn- set-locales
  "Pure changes fn: write the file-level enabled-locales vector. `locales`
  is a vector of keywords. `changes` must carry file-data (via
  pcb/with-file-data)."
  [changes locales]
  (pcb/set-plugin-data changes loc/ovion-namespace loc/locales-key (pr-str locales)))

(defn- set-shape-locale-strings
  "Pure changes fn: write the shape-level locale-strings slot on
  `shape-id` (page `page-id`). `strings` is a map {kw str} or nil to clear.
  `changes` must carry file-data + page context."
  [changes shape-id page-id strings]
  (let [value (if (nil? strings) nil (pr-str strings))]
    (pcb/set-plugin-data changes :shape shape-id page-id
                         loc/ovion-namespace loc/slot-key value)))

;; --- Event commit helpers (one undo transaction) ----------------------------

(defn- commit-file-locales
  "Build + commit a file-level locales plugin-data change in one undo
  transaction. `f` is applied to the current locales vector and must return
  the new vector."
  [it state f]
  (let [file-id   (:current-file-id state)
        file-data (dsh/lookup-file-data state file-id)]
    (if (nil? file-data)
      (rx/empty)
      (let [undo-id  (js/Symbol)
            current  (loc/read-locales file-data)
            new-vec  (f current)]
        (rx/of (dwu/start-undo-transaction undo-id)
               (dch/commit-changes
                (-> (pcb/empty-changes it)
                    (pcb/with-file-data file-data)
                    (set-locales new-vec)))
               (dwu/commit-undo-transaction undo-id))))))

(defn- commit-shape-locale-strings
  "Build + commit a shape-level locale-strings plugin-data change in one
  undo transaction. `update-fn` is applied to the existing strings map (or
  {} when absent) and must return the new map (or nil to clear the slot)."
  [it state shape-id update-fn]
  (let [page-id   (:current-page-id state)
        page      (dsh/lookup-page state)
        file-id   (:current-file-id state)
        file-data (dsh/lookup-file-data state file-id)]
    (if (or (nil? page) (nil? shape-id))
      (rx/empty)
      (let [undo-id  (js/Symbol)
            shape    (get-in page [:objects shape-id])
            existing (or (loc/read-locale-strings shape) {})
            new-str  (update-fn existing)
            value    (if (nil? new-str) nil (pr-str new-str))]
        (rx/of (dwu/start-undo-transaction undo-id)
               (dch/commit-changes
                (-> (pcb/empty-changes it)
                    (pcb/with-file-data file-data)
                    (pcb/with-page page)
                    (pcb/set-plugin-data :shape shape-id page-id
                                         loc/ovion-namespace loc/slot-key value)))
               (dwu/commit-undo-transaction undo-id))))))

;; --- Events -----------------------------------------------------------------

(defn add-locale-event
  "ptk event: add `locale` (a keyword or string) to the file's enabled
  locales vector in one undo transaction. Idempotent — no-op if already
  present. Never removes the default `:en`."
  [locale]
  (ptk/reify ::add-locale
    ptk/WatchEvent
    (watch [it state _]
      (commit-file-locales
       it state
       (fn [current]
         (let [loc (keyword locale)]
           (vec (distinct (conj current loc)))))))))

(defn remove-locale-event
  "ptk event: remove `locale` (a keyword or string) from the file's enabled
  locales vector in one undo transaction. Never removes `:en` (the default
  is always retained). No-op if absent."
  [locale]
  (ptk/reify ::remove-locale
    ptk/WatchEvent
    (watch [it state _]
      (commit-file-locales
       it state
       (fn [current]
         (let [loc    (keyword locale)
               next   (vec (remove #(= loc %) current))]
           (if (empty? next) [:en] next)))))))

(defn set-shape-locale-string-event
  "ptk event: set the `locale` string on shape `shape-id`'s locale-strings
  slot to `text` in one undo transaction. When `text` is empty, the locale
  entry is removed from the map (falling back to default/own-content for
  that locale). Initializes the slot if absent."
  [shape-id locale text]
  (ptk/reify ::set-shape-locale-string
    ptk/WatchEvent
    (watch [it state _]
      (let [loc (keyword locale)]
        (commit-shape-locale-strings
         it state shape-id
         (fn [existing]
           (let [txt (str text)]
             (if (empty? txt)
               (let [next (dissoc existing loc)]
                 (if (empty? next) nil next))
               (assoc existing loc txt)))))))))

(defn enable-locale-strings-on-shape-event
  "ptk event: initialize an empty locale-strings slot on shape `shape-id`,
  copying the shape's current text into the default locale (the first
  enabled locale, or `:en`). No-op if the shape already carries a slot.
  One undo transaction. After this, the shape is locale-managed and the
  per-locale editors become available."
  [shape-id]
  (ptk/reify ::enable-locale-strings
    ptk/WatchEvent
    (watch [it state _]
      (let [page      (dsh/lookup-page state)
            file-id   (:current-file-id state)
            file-data (dsh/lookup-file-data state file-id)]
        (if (nil? page)
          (rx/empty)
          (let [shape    (get-in page [:objects shape-id])
                existing (loc/read-locale-strings shape)]
            (if (some? existing)
              (rx/empty)
              (let [dloc    (first (loc/read-locales file-data))
                    dloc    (if (nil? dloc) loc/default-locale dloc)
                    init    {dloc (loc/shape-own-text shape)}]
                (commit-shape-locale-strings
                 it state shape-id
                 (fn [_] init))))))))))

(defn clear-locale-strings-on-shape-event
  "ptk event: remove the locale-strings slot from shape `shape-id`,
  returning it to normal (non-locale-managed) text rendering. One undo
  transaction. The shape's own content is unchanged."
  [shape-id]
  (ptk/reify ::clear-locale-strings
    ptk/WatchEvent
    (watch [it state _]
      (commit-shape-locale-strings
       it state shape-id
       (fn [_] nil)))))

(defn set-active-locale-event
  "ptk event: set the canvas-wide active locale to `locale` (a keyword or
  string). This is an UpdateEvent — it only mutates app-level state, no file
  change / no undo. Text shapes subscribed via `active-locale-ref` re-render
  and substitute the new locale's string."
  [locale]
  (ptk/reify ::set-active-locale
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :active-locale (keyword locale)))))