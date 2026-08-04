;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.devlink
  "P2.16 — DevLink-style two-way component sync (ALL_APPS_PARITY).

  Wires the two-way sync between Ovion's code-component registry (P0.14
  host) and an external React/TypeScript codebase. The host interface
  lives in `app.main.data.workspace.code-components` (read-registry,
  read-slot, register-component-event, apply-to-shape-event,
  bundle-url-for); this module consumes it — it never touches
  plugin-data directly.

  Three pieces:

  1. FORWARD SYNC (Ovion -> code): `build-manifest` is a PURE fn that
     walks the file registry + page objects and emits a DevLinkProvider
     contract manifest:

        {:version 1
         :components [{:id :name :bundleUrl :propsSchema} ...]
         :instances  [{:shapeId :componentId :props} ...]}

     `export-manifest` is the ptk event that builds it, copies it to the
     clipboard, and offers a `.json` download (reusing the
     `dom/trigger-download` blob path the code-export module uses).

  2. REVERSE SYNC (code -> Ovion): `re-sync-components` walks the
     registry. For entries whose `bundle-url` matches the Storybook
     iframe pattern (`<base>/iframe.html?id=<story-id>&viewMode=story`),
     it re-pulls the Storybook index via
     `app.main.data.workspace.storybook/sync-storybook` (which
     re-registers every story idempotently on its keyword id — so a
     changed bundle-url or props-schema is reconciled back into Ovion in
     one undo transaction). AI-blob / session-scoped / custom-URL
     components are a no-op (the user must paste an updated URL and
     re-register manually) — surfaced with an info toast.

  3. RUNTIME STUB: `runtime-provider` is a small map of fns mirroring a
     DevLinkProvider (`get-component` / `set-props`) so a future external
     Node runtime can plug in. `set-props` emits the host's
     `apply-to-shape-event`. The full external Node DevLinkProvider
     bridge is the multi-quarter tail; this is the in-app stub that
     documents the contract.

  `byte-identical-when-inactive`: DevLink sync is purely opt-in (the
  DevLink menu actions). No action = no manifest export, no re-sync
  fetch, no registry mutation. The module writes nothing to plugin-data
  itself — all registry writes go through the P0.14 host's events."

  (:require
   [app.common.data.macros :as dm]
   [app.main.data.helpers :as dsh]
   [app.main.data.notifications :as ntf]
   [app.main.data.workspace.code-components :as cc]
   [app.main.data.workspace.storybook :as storybook]
   [app.main.store :as st]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [clojure.string :as cstr]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]
   [promesa.core :as p]))

;; --- Storybook iframe-pattern detection -------------------------------------

(def ^:private storybook-iframe-marker "/iframe.html?id=")

(defn- storybook-base-url
  "If `bundle-url` is a Storybook iframe URL (`<base>/iframe.html?id=…`),
  return the `<base>` (everything before `/iframe.html`); else nil."
  [bundle-url]
  (let [s (str bundle-url)]
    (if (cstr/includes? s storybook-iframe-marker)
      (let [idx (cstr/index-of s storybook-iframe-marker)]
        (subs s 0 idx))
      nil)))

;; --- Manifest builder (PURE) ------------------------------------------------

(defn build-manifest
  "PURE fn. Build a DevLink manifest map from `file-data` (carrying the
  file-level registry) and `objects` (a page objects map). Returns:

    {:version 1
     :components [{:id <kw> :name <str> :bundleUrl <str> :propsSchema <map>} ...]
     :instances  [{:shapeId <uuid> :componentId <kw> :props <map>} ...]}

  Components come from the registry (`cc/read-registry`). Instances come
  from every shape in `objects` carrying the `:ovion \"code-component\"`
  slot (`cc/read-slot`). Nil-safe: an absent registry yields an empty
  component list; an empty `objects` yields an empty instance list."
  [file-data objects]
  (let [registry  (or (cc/read-registry file-data) {})
        components
        (for [[id-kw entry] registry
              :when (map? entry)]
          {:id           id-kw
           :name         (or (:name entry) (name id-kw))
           :bundleUrl    (or (:bundle-url entry) "")
           :propsSchema  (or (:props-schema entry) {})})
        instances
        (for [[shape-id shape] (seq objects)
              :let [slot (cc/read-slot shape)]
              :when (and (map? slot) (some? (:id slot)))]
          {:shapeId      shape-id
           :componentId  (:id slot)
           :props        (or (:props slot) {})})]
    {:version    1
     :components (vec components)
     :instances  (vec instances)}))

;; --- Manifest -> JSON string ------------------------------------------------

(defn manifest->json
  "Serialize a manifest map to a pretty-printed JSON string. Keywords
  become string keys; keyword component ids become their name. Nil-safe
  (nil -> \"{}\")."
  [manifest]
  (if (nil? manifest)
    "{}"
    (js/JSON.stringify (clj->js manifest) nil 2)))

;; --- Forward sync: export manifest ------------------------------------------

(defn- manifest-blob
  "Build a `js/Blob` for the manifest JSON (application/json)."
  [json-str]
  (js/Blob. #js [(str json-str)] #js {:type "application/json"}))

(defn- clipboard-write!
  "Copy `text` to the system clipboard. Nil-safe; swallows clipboard
  errors (the download path is the primary delivery)."
  [text]
  (try
    (let [clip (some-> js/navigator .-clipboard)]
      (when (and (some? clip) (fn? (.-writeText clip)))
        (-> (.writeText clip (str text))
            (p/catch (fn [err]
                       (js/console.warn "[devlink] clipboard write failed" err))))))
    (catch :default err
      (js/console.warn "[devlink] clipboard unavailable" err))))

(defn export-manifest
  "ptk event: build the DevLink manifest for the current file + page,
  copy it to the clipboard, trigger a `.json` download, and emit an info
  toast. Purely additive — no registry mutation. Nil-safe when the file
  or page is unavailable (emits an error toast and returns rx/empty)."
  []
  (ptk/reify ::export-devlink-manifest
    ptk/WatchEvent
    (watch [_it state _]
      (let [file-id   (:current-file-id state)
            file-data (dsh/lookup-file-data state file-id)
            page      (dsh/lookup-page state)
            objects   (or (:objects page) {})]
        (if (nil? file-data)
          (do
            (st/emit! (ntf/error (tr "workspace.devlink.export-no-file")))
            (rx/empty))
          (let [manifest (build-manifest file-data objects)
                json     (manifest->json manifest)
                blob     (manifest-blob json)
                n-comp   (count (:components manifest))
                n-inst   (count (:instances manifest))]
            (clipboard-write! json)
            (dom/trigger-download "devlink-manifest.json" blob)
            (st/emit! (ntf/info
                       (tr "workspace.devlink.exported" n-comp n-inst)))
            (rx/empty)))))))

;; --- Reverse sync: re-sync from source --------------------------------------

(defn- collect-storybook-bases
  "Walk `registry` and return the distinct set of Storybook base URLs
  implied by its entries' bundle-urls. Non-Storybook bundle-urls are
  skipped (collected separately via the second return value). Returns
  `[storybook-bases non-storybook-count]`."
  [registry]
  (let [acc (reduce
             (fn [acc entry]
               (let [bundle-url (:bundle-url entry)
                     base (storybook-base-url bundle-url)]
                 (if (some? base)
                   (-> acc
                       (update :bases (fnil conj #{}) base)
                       (update :storybook-count (fnil inc 0)))
                   (update acc :other-count (fnil inc 0)))))
             {:bases #{} :storybook-count 0 :other-count 0}
             (vals registry))]
    [(:bases acc) (:other-count acc) (:storybook-count acc)]))

(defn re-sync-components
  "ptk event: reconcile code-side component changes back into Ovion.

  For each Storybook-pattern bundle-url in the registry, re-pull the
  Storybook index via `storybook/sync-storybook` (which re-registers
  every story idempotently on its keyword id — updated bundle-urls /
  props-schemas overwrite the entry). One `sync-storybook` call per
  distinct Storybook base URL.

  Non-Storybook entries (AI-blob / session-scoped / custom URL) are a
  no-op: the user must paste an updated URL and re-register manually.
  Surfaced with an info toast noting how many were skipped.

  Nil-safe: an empty/absacent registry emits an info toast and returns
  rx/empty. Purely opt-in — no action = no fetch = no registry change."
  []
  (ptk/reify ::devlink-re-sync
    ptk/WatchEvent
    (watch [_it state _]
      (let [file-id   (:current-file-id state)
            file-data (dsh/lookup-file-data state file-id)
            registry  (or (cc/read-registry file-data) {})
            [bases other-count sb-count] (collect-storybook-bases registry)]
        (cond
          ;; Empty/absent registry — nothing to re-sync.
          (empty? registry)
          (st/emit! (ntf/info (tr "workspace.devlink.resync-empty")))

          ;; No Storybook sources — nothing to re-pull.
          (empty? bases)
          (st/emit!
           (ntf/info
            (tr "workspace.devlink.resync-no-storybook"
                (count registry) other-count)))

          ;; Storybook sources present — re-pull each distinct base.
          :else
          (do
            (doseq [base bases]
              (st/emit! (storybook/sync-storybook base)))
            (st/emit!
             (ntf/info
              (tr "workspace.devlink.resync-done"
                  sb-count (count bases) other-count)))))
        (rx/empty)))))

;; --- Runtime provider stub --------------------------------------------------
;;
;; A small map of fns mirroring a DevLinkProvider, so a future external
;; Node runtime can plug in. `get-component` is a pure lookup over a
;; manifest; `set-props` is a side-effecting fn that emits the host's
;; `apply-to-shape-event` (one undo transaction per prop change). This
;; is the in-app stub; the full external Node DevLinkProvider bridge is
;; the multi-quarter tail (noted honestly — the wire contract is here,
;; the remote process is not).

(defn runtime-provider
  "Return a DevLinkProvider-style map of fns:

    :get-component  (fn [manifest id]) -> component map | nil
    :set-props      (fn [shape-id component-id props]) -> emits
                     cc/apply-to-shape-event (one undo); nil-safe.
    :list-components (fn [manifest]) -> seq of component maps

  This is the in-app runtime stub. A future external Node DevLinkProvider
  would consume `export-manifest`'s JSON and call back through
  `set-props`; the remote bridge is the multi-quarter tail."
  []
  {:get-component
   (fn get-component
     ([manifest id]
      (let [id-kw (if (keyword? id) id (keyword (str id)))]
        (some #(when (= (:id %) id-kw) %)
              (:components manifest)))))
   :list-components
   (fn list-components [manifest]
     (seq (:components manifest)))
   :set-props
   (fn set-props [shape-id component-id props]
     (when (some? shape-id)
       (st/emit!
        (cc/apply-to-shape-event
         shape-id
         (if (keyword? component-id) component-id (keyword (str component-id)))
         (or props {})))))})