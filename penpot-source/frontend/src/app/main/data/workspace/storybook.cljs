;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.storybook
  "P0.19 — Sync external React component libraries from Storybook into the
  design library.

  A thin data layer that fetches a Storybook stories index via the Rust
  `storybook_fetch` Tauri command (see `src-tauri/src/storybook.rs`), parses
  it into a seq of component descriptors, and registers each story as a
  code-component entry through the P0.14 host
  (`app.main.data.workspace.code-components`).

  Storybook index JSON formats handled:

    • Storybook 7+ (`index.json`):
        { \"v\": 3,
          \"stories\": { \"<story-id>\": { :id :title :name :importPath
                                            :argTypes :args :parameters } } }

    • Storybook 6 / older (`stories.json`):
        { \"stories\": [ { :id :title :name :importPath :argTypes :args } … ] }

  For each story we derive:
    • `:id`          — the Storybook story id (e.g. \"components-button--primary\")
    • `:title`       — the component title (e.g. \"Components/Button\")
    • `:name`        — the story name (e.g. \"Primary\")
    • `:bundle-url`  — the Storybook iframe URL the P0.14 host loads in its
                       sandboxed iframe:
                       `<base>/iframe.html?id=<story-id>&viewMode=story`
    • `:props-schema`— derived from the story's `argTypes` (control type →
                       :string/:number/:boolean/:color; default from `args`;
                       label from the arg name).

  The sync is an opt-in user action (\"Sync Storybook\" in the assets panel).
  `byte-identical-when-inactive`: no sync = no fetch = no registry mutation.

  Storybook prop-passing limitation (honest): the P0.14 host passes props to
  the iframe via a `#ovion-props=<base64>` URL fragment. Storybook's
  `iframe.html` ignores that unknown fragment and renders the story with its
  default `args`. Edited props in the inspector therefore do NOT flow into a
  live Storybook render — the inspector still shows the derived props-schema
  for reference, and the component renders with Storybook's defaults. Passing
  edited props to Storybook would require the `&args=` query param, which the
  host's fragment scheme does not use. This is a known, documented
  limitation; the story registration + default-args render is the value."

  (:require
   ["@tauri-apps/api/core" :refer [invoke]]
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as pcb]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.notifications :as ntf]
   [app.main.data.workspace.code-components :as cc]
   [app.main.data.workspace.undo :as dwu]
   [app.main.store :as st]
   [app.util.i18n :as i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [clojure.string :as cstr]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]
   [promesa.core :as p]))

;; ── Last-used base URL (browser localStorage) ────────────────────────────────
;; Mirrors the Pexels key / Figma token pattern in stock_assets.cljs: the
;; base URL the user last synced from is remembered so a re-sync is one
;; click. Never sent anywhere except back to the Rust command on the next
;; sync.

(def ^:private url-storage-key "ovion.storybook-url")

(defn load-storybook-url
  "Read the last-used Storybook base URL from localStorage. Returns a string
  (empty when unset or unavailable). Nil-safe against browsers that throw on
  localStorage access (private mode)."
  []
  (try
    (or (.getItem js/localStorage url-storage-key) "")
    (catch :default _ "")))

(defn save-storybook-url
  "Persist `url` to localStorage and return it. Empty string clears it."
  [url]
  (try
    (if (str/blank? url)
      (.removeItem js/localStorage url-storage-key)
      (.setItem js/localStorage url-storage-key url))
    (catch :default _))
  url)

;; ── Invoke wrapper ───────────────────────────────────────────────────────────

(defn fetch-storybook
  "Fetch the Storybook stories index from `base-url`. Returns a promesa
  promise resolving to a JS object `{index, sourceUrl}` where `index` is the
  raw stories JSON. Rejects with the sentinel `\"storybook-url-missing\"`
  when `base-url` is blank, or `\"storybook-fetch-failed\"` on any
  network/HTTP/parse error — callers match these exactly."
  [base-url]
  (invoke "storybook_fetch" #js {:url base-url}))

;; ── Pure parser ──────────────────────────────────────────────────────────────
;;
;; Walks the Storybook index `v->stories` map (v3) OR the older `stories.json`
;; array, and returns a seq of `{:id :title :name :bundle-url :props-schema}`
;; maps. The `base-url` is needed to build the iframe.html `bundle-url`.
;; Nil-safe: stories missing `id`/`title`/`name` are skipped; argTypes that
;; map to unsupported control types are skipped.

(defn- control-type->prop-type
  "Map a Storybook argType control type keyword/string to the P0.14 host's
  prop schema type. Returns nil for unsupported (complex) control types
  (`object`, `array`, `file`, `date` — caller skips nils)."
  [control-type]
  ;; Storybook control types arrive as strings ("text", "boolean", …) or
  ;; sometimes as a map {:type "text"}. Normalize to a lowercase string.
  (let [t (cond
            (string? control-type)  (str/lower control-type)
            (map? control-type)     (some-> (get control-type :type) str/lower)
            (keyword? control-type) (name control-type)
            :else                    nil)]
    (case t
      "text"      :string
      "string"    :string
      "number"    :number
      "boolean"   :boolean
      "color"     :color
      "select"    :string
      "radio"     :string
      "inline-radio" :string
      "check"     :boolean
      "inline-check" :boolean
      "multi-select" :string
      nil)))

(defn- derive-props-schema
  "Build a P0.14 props-schema map from a story's `argTypes` + `args`.
  `{<prop-keyword> {:type :string|:number|:boolean| :default <val> :label <string>}}`.
  Args without a control type are skipped. `args` provides defaults; the
  label is the arg name (humanized)."
  [arg-types args]
  (reduce-kv
   (fn [schema arg-name arg-type]
     (let [control  (get arg-type :control)
           ptype    (control-type->prop-type control)]
       (if (nil? ptype)
         schema
         (let [prop-kw (keyword arg-name)
               default (get args arg-name)
               raw     (name arg-name)
               label   (if (str/blank? raw)
                         raw
                         (str (cstr/upper-case (subs raw 0 1)) (subs raw 1)))]
           (assoc schema prop-kw
                  (cond-> {:type ptype :label label}
                    (some? default) (assoc :default default)))))))
   {}
   (or arg-types {})))

(defn- story->descriptor
  "Build one `{:id :title :name :bundle-url :props-schema}` map from a parsed
  story object. Returns nil when the story lacks the minimum fields."
  [base-url story]
  (let [id    (:id story)
        title (:title story)
        name  (:name story)]
    (when (and (not (str/blank? id))
               (not (str/blank? title))
               (not (str/blank? name)))
      (let [trimmed-base (if (cstr/ends-with? base-url "/")
                           (subs base-url 0 (max 0 (dec (count base-url))))
                           base-url)
            bundle-url (str trimmed-base
                            "/iframe.html?id="
                            (js/encodeURIComponent id)
                            "&viewMode=story")
            props-schema (derive-props-schema (:argTypes story) (:args story))]
        {:id           id
         :title        title
         :name         name
         :bundle-url   bundle-url
         :props-schema props-schema}))))

(defn parse-stories
  "Pure fn. Walks a Storybook stories index (already `js->clj :keywordize-keys
  true`) and returns a seq of component descriptors for the P0.14 host.

  `index-json` is the raw stories payload (the `:index` key from the Rust
  response). `base-url` is the user-supplied base URL, used to build each
  story's `iframe.html` bundle-url.

  Handles both the v3 map (`{v: 3, stories: {<id>: …}}`) and the older
  array format (`{stories: […]}`). Nil-safe."
  [index-json base-url]
  (let [stories (or (:stories index-json) {})]
    (cond
      ;; v3 — map of story-id -> story.
      (map? stories)
      (keep #(story->descriptor base-url (val %)) stories)

      ;; older — array of stories.
      (sequential? stories)
      (keep #(story->descriptor base-url %) stories)

      :else '())))

;; ── Sync event ───────────────────────────────────────────────────────────────
;;
;; `sync-storybook` fetches the index, parses it, and registers every story
;; as a code-component via the P0.14 host's PURE `cc/register-component`
;; changes-fn, all inside ONE `dwu` undo transaction. We use the pure fn
;; (not `cc/register-component-event`) because the event opens its own dwu;
;; calling the pure fn inside our dwu gives a single changes-builder, a
;; single file-data read, and a single undo entry for the whole sync —
;; mirroring how `material-kit.cljs` batches multiple `pcb/add-color` +
;; `design-gen/apply-design-spec` inside one outer dwu.

(defn- commit-storybook-sync
  "ptk event: register every parsed story descriptor in ONE undo transaction
  using the P0.14 host's pure `cc/register-component` changes-fn. Builds a
  single changes value that accumulates all registry entries, then commits
  it between `dwu/start-undo-transaction` / `dwu/commit-undo-transaction`."
  [stories]
  (ptk/reify ::commit-storybook-sync
    ptk/WatchEvent
    (watch [it state _]
      (let [file-data (dsh/lookup-file-data state)
            undo-id   (uuid/next)
            changes   (-> (pcb/empty-changes it)
                          (pcb/with-file-data file-data)
                          (as-> c
                              (reduce (fn [acc s]
                                        (cc/register-component
                                         acc
                                         (keyword (:id s))
                                         (str (:title s) "/" (:name s))
                                         (:bundle-url s)
                                         (:props-schema s)))
                                      c
                                      stories)))]
        (rx/of (dwu/start-undo-transaction undo-id)
               (dch/commit-changes changes)
               (dwu/commit-undo-transaction undo-id))))))

(defn sync-storybook
  "Fetch + parse + register every Storybook story as a code-component entry
  in ONE undo transaction. Detached-promise side-effects fire via
  `st/emit!` on resolve (mirrors `cms-import/run-cms-import`). Emits an info
  toast on success / error. Nil-safe against an empty index."
  [base-url]
  (ptk/reify ::sync-storybook
    ptk/WatchEvent
    (watch [_it _state _]
      (let [base (str/trim (or base-url ""))
            on-ok
            (fn [js-obj]
              (let [payload  (js->clj js-obj :keywordize-keys true)
                    index    (:index payload)
                    stories  (parse-stories index base)]
                (if (seq stories)
                  (do
                    (st/emit! (commit-storybook-sync stories))
                    (st/emit! (ntf/info
                               (tr "workspace.assets.storybook-synced"
                                   (count stories)))))
                  (st/emit! (ntf/info (tr "workspace.assets.storybook-empty"))))))
            on-err
            (fn [err]
              (let [msg (str err)]
                (condp = msg
                  "storybook-url-missing"
                  (st/emit! (ntf/error (tr "workspace.assets.storybook-url-missing")))
                  "storybook-fetch-failed"
                  (st/emit! (ntf/error (tr "workspace.assets.storybook-fetch-failed")))
                  ;; Unknown error — surface the generic fetch-failed state.
                  (st/emit! (ntf/error (tr "workspace.assets.storybook-fetch-failed"))))))]
        (if (str/blank? base)
          (st/emit! (ntf/error (tr "workspace.assets.storybook-url-missing")))
          (do
            (save-storybook-url base)
            (-> (fetch-storybook base)
                (p/then on-ok)
                (p/catch on-err))))
        (rx/empty)))))