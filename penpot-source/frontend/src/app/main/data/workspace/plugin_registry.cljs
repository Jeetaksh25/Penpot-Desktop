;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.plugin-registry
  "ALL_APPS_PARITY P1.28 — Plugin Center data layer.

  Fetches the Ovion-hosted plugin registry JSON via the Rust
  `fetch_plugin_registry` Tauri command and routes the result back into the
  potok store. The UI panel (`ui.workspace.sidebar.options.menus.plugin-center`)
  consumes the resulting `[:plugin-center :registry]` slot.

  Install / enable / disable / uninstall are ptk events:
    * `install-plugin`  — adds the plugin to the profile registry via the
                          existing `app.plugins.register/install-plugin!`
                          (READ-only reference — no edit to register.cljs) AND
                          marks it enabled in the current file's plugin-data.
    * `enable-plugin`   — flips the per-file enabled flag on.
    * `disable-plugin`  — flips it off.
    * `uninstall-plugin` — calls `app.plugins.register/remove-plugin!` and
                          drops the per-file enabled flag.

  Per-file persistence: the enabled map is stored on the page's
  `:plugin-data :ovion \"plugin-center-enabled\"` slot as an EDN string
  (mirrors `data.workspace.collections`'s CMS-data slot — the changes
  pipeline's generic per-page extension point, undo/redo-safe, no shared-file
  edits). Profile-level install state stays in the existing registry atom.

  Tauri invoke mirrors `data.exports.publish/publish-current-site`: the
  `invoke \"fetch_plugin_registry\"` promise is fired detached and resolves
  via `p/then`/`p/catch` into `registry-loaded` / `registry-failed`."
  (:require
   ["@tauri-apps/api/core" :refer [invoke]]
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.undo :as dwu]
   [app.plugins.register :as reg]
   [app.main.store :as st]
   [beicon.v2.core :as rx]
   [cljs.reader :as reader]
   [potok.v2.core :as ptk]
   [promesa.core :as p]))

;; ── Plugin-data slot constants (per-file enabled map) ──────────────────────

(def pc-namespace
  "Plugin-data namespace keyword under which the per-file Plugin Center
  enabled map is stored on the page. Schema:plugin-data key namespaces are
  keywords."
  :ovion)

(def pc-key
  "Plugin-data key (string) under `pc-namespace` for the enabled map."
  "plugin-center-enabled")

;; ── Read / write helpers (mirror collections.cljs) ──────────────────────────

(defn read-enabled-map
  "Parse the page's plugin-data Plugin Center slot back into an enabled map.
  Accepts either a page map (reads `:plugin-data`) or a raw stored string.
  Returns `{}` when the slot is absent or unparsable."
  ([]
   {})
  ([page-or-str]
   (let [raw (if (map? page-or-str)
               (dm/get-in page-or-str [:plugin-data pc-namespace pc-key])
               page-or-str)]
     (if (or (nil? raw) (empty? raw))
       {}
       (try
         (reader/read-string raw)
         (catch :default _
           {}))))))

(defn- write-enabled-map
  "Serialize an enabled map to the plugin-data slot string form."
  [m]
  (pr-str m))

(defn- current-enabled-map
  "Read the current page's Plugin Center enabled map, defaulting to `{}`."
  [page]
  (if (nil? page)
    {}
    (read-enabled-map page)))

(defn- commit-enabled-map
  "Build and commit a changeset that writes `new-map` to the current page's
  plugin-data Plugin Center slot, inside one undo transaction. Returns an rx
  stream of potok events (or `rx/empty` when the page is nil)."
  [it state new-map]
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
                                              pc-namespace
                                              pc-key
                                              (write-enabled-map new-map)))]
        (rx/of (dwu/start-undo-transaction undo-id)
               (dch/commit-changes changes)
               (dwu/commit-undo-transaction undo-id))))))

;; ── Registry fetch events ──────────────────────────────────────────────────

(defn registry-loaded
  "Event carrying the registry envelope `{plugins fallback endpoint}` returned
  by `fetch_plugin_registry`. Stored under `[:plugin-center :registry]`;
  `:fallback` / `:endpoint` are surfaced so the panel can show an offline chip."
  [envelope]
  (ptk/reify ::registry-loaded
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc-in [:plugin-center :registry] envelope)
          (assoc-in [:plugin-center :status] :loaded)))))

(defn registry-failed
  "Event carrying the fetch error string. Sets `:status :failed` and stores the
  error under `[:plugin-center :error]`."
  [error]
  (ptk/reify ::registry-failed
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc-in [:plugin-center :status] :failed)
          (assoc-in [:plugin-center :error] (str error))))))

(defn fetch-registry
  "Fetch the plugin registry. `endpoint` is an optional override (defaults to
  the Rust-side `https://api.ovion.app/v1/plugins/registry`). Mirrors
  `publish-current-site`: the `invoke` promise is fired detached and resolves
  via `p/then`/`p/catch` into `registry-loaded` / `registry-failed`. Emits a
  `:loading` status first so the panel can render a spinner."
  [{:keys [endpoint]}]
  (ptk/reify ::fetch-registry
    ptk/WatchEvent
    (watch [_ state _]
      (let [handle     (fn [result]
                         (let [env (js->clj result :keywordize-keys true)]
                           (st/emit! (registry-loaded env))))
            handle-err (fn [err]
                         (st/emit! (registry-failed (str err))))]
        ;; Detached promise — side-effects fire via st/emit! on resolve.
        ;; `endpoint` is an optional override; pass nil to use the Rust default.
        (-> (invoke "fetch_plugin_registry" #js {:endpoint (or endpoint nil)})
            (p/then handle)
            (p/catch handle-err)))
      (rx/of (ptk/reify ::registry-loading
               ptk/UpdateEvent
               (update [_ state]
                 (assoc-in state [:plugin-center :status] :loading)))))))

;; ── Install / enable / disable / uninstall events ─────────────────────────

(defn install-plugin
  "Install `plugin` (a registry entry map `{id name description icon ...}`)
  into the profile registry via the existing `app.plugins.register/install-plugin!`
  (read-only reference — no edit to register.cljs) AND mark it enabled in the
  current file's plugin-data slot. A plugin is only 'installed' once across
  the profile, but enabled per file — mirroring Figma's model.

  The registry entry uses `:id`; the Penpot register expects `:plugin-id`, so
  the entry is re-keyed (`:plugin-id` ← `:id`) before `install-plugin!`."
  [{:keys [plugin]}]
  (ptk/reify ::install-plugin
    ptk/WatchEvent
    (watch [it state _]
      (let [page    (dsh/lookup-page state)
            enabled (current-enabled-map page)
            pid     (:id plugin)
            entry   (assoc plugin :plugin-id pid)]
        ;; Profile-level install (no-op if already installed; register dedups
        ;; by plugin-id). `install-plugin!` takes a pre-built entry — hand it
        ;; the registry entry re-keyed to `:plugin-id`.
        (try
          (reg/install-plugin! entry)
          (catch :default _ nil))
        (commit-enabled-map it state (assoc enabled pid true))))))

(defn uninstall-plugin
  "Uninstall `plugin` from the profile registry via `app.plugins.register/remove-plugin!`
  AND drop its per-file enabled flag. The plugin is no longer loadable on any
  file until reinstalled."
  [{:keys [plugin]}]
  (ptk/reify ::uninstall-plugin
    ptk/WatchEvent
    (watch [it state _]
      (let [page    (dsh/lookup-page state)
            enabled (current-enabled-map page)
            pid     (:id plugin)]
        (try
          (reg/remove-plugin! {:plugin-id pid})
          (catch :default _ nil))
        (commit-enabled-map it state (dissoc enabled pid))))))

(defn enable-plugin
  "Flip the per-file enabled flag for `plugin` ON. Does not touch the profile
  registry (a plugin must already be installed to enable it)."
  [{:keys [plugin]}]
  (ptk/reify ::enable-plugin
    ptk/WatchEvent
    (watch [it state _]
      (let [page    (dsh/lookup-page state)
            enabled (current-enabled-map page)
            pid     (:id plugin)]
        (commit-enabled-map it state (assoc enabled pid true))))))

(defn disable-plugin
  "Flip the per-file enabled flag for `plugin` OFF. The plugin remains
  installed in the profile registry."
  [{:keys [plugin]}]
  (ptk/reify ::disable-plugin
    ptk/WatchEvent
    (watch [it state _]
      (let [page    (dsh/lookup-page state)
            enabled (current-enabled-map page)
            pid     (:id plugin)]
        (commit-enabled-map it state (assoc enabled pid false))))))