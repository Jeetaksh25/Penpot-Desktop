;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.forms
  "Native forms builder (ALL_APPS_PARITY P1.23 — native-forms half) — data layer.

  Persists a frame's form configuration as plugin-data on the frame shape
  under namespace `:ovion` key `\"form-config\"` (a pr-str EDN map), through
  the standard changes pipeline so edits are undo/redo-safe and survive
  save/reload. Mirrors `data.workspace.collections` (which does the same
  for page-level CMS data).

  Form config schema:

    {:fields     [{:id <uuid> :name <str> :label <str>
                   :type <keyword text|email|number|tel|textarea|select|checkbox>
                   :required <bool>
                   :shape-id <uuid|nil>     ;; bound canvas input shape
                   :options [<str>...]      ;; for :select
                   :test-value <str>}]
     :action     {:type :ovion-cloud
                  :endpoint <str|nil>       ;; nil → Rust resolves from llm.json
                  :name <str>}              ;; form name posted to /forms/submit
     :success-message <str>
     :error-message   <str>}

  Two event families:

    * config mutators (`set-form-config`, `add-field`, `update-field`,
      `remove-field`, `set-action`, `set-messages`) — `ptk/WatchEvent`s
      that read the frame's current config, apply a pure transform, and
      commit it via `commit-form-config`.

    * `test-submit-form` — fires the Tauri `submit_form` command detached
      (mirrors `data.exports.publish/publish-current-site`) and routes the
      result back into the store via `form-submit-succeeded` /
      `form-submit-failed`. The payload is built from the bound field
      shapes' current text content (or the field's `:test-value` fallback)."
  (:require
   ["@tauri-apps/api/core" :refer [invoke]]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as pcb]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.undo :as dwu]
   [app.main.store :as st]
   [beicon.v2.core :as rx]
   [cljs.reader :as reader]
   [potok.v2.core :as ptk]
   [promesa.core :as p]))

;; --- Plugin-data slot constants --------------------------------------------

(def forms-namespace
  "Plugin-data namespace keyword under which form config is stored on the
  frame shape. Schema:plugin-data keys namespaces are keywords."
  :ovion)

(def forms-key
  "Plugin-data key (string) under `forms-namespace` for the form-config map."
  "form-config")

;; --- Field type metadata ---------------------------------------------------

(def field-types
  "Supported native-form field types (keywords). Mirrors the HTML input
  types a published `<form>` would render."
  [:text :email :number :tel :textarea :select :checkbox])

;; --- Read / write helpers ---------------------------------------------------

(defn empty-form-config
  "A fresh form config with no fields and the default Ovion Cloud action +
  success/error messages."
  []
  {:fields          []
   :action          {:type :ovion-cloud :endpoint nil :name "form"}
   :success-message "Thank you! Your submission was received."
   :error-message   "Sorry — something went wrong. Please try again."})

(defn read-form-config
  "Parse a frame's plugin-data form-config slot back into a config map.
  Accepts either a shape map (reads `:plugin-data`) or a raw stored string.
  Returns `empty-form-config` when the slot is absent or unparsable."
  ([]
   (empty-form-config))
  ([shape-or-str]
   (let [raw (if (map? shape-or-str)
               (dm/get-in shape-or-str [:plugin-data forms-namespace forms-key])
               shape-or-str)]
     (if (or (nil? raw) (empty? raw))
       (empty-form-config)
       (try
         (reader/read-string raw)
         (catch :default _
           (empty-form-config)))))))

(defn- current-form-config
  "Read `frame-shape`'s current form config, defaulting to empty when the
  shape is nil or has no slot."
  [frame-shape]
  (if (nil? frame-shape)
    (empty-form-config)
    (read-form-config frame-shape)))

(defn- write-form-config
  "Serialize a form-config map to the plugin-data slot string form."
  [config]
  (pr-str config))

(defn- commit-form-config
  "Build and commit a changeset that writes `new-config` to the frame
  shape's plugin-data form-config slot, inside one undo transaction.
  Returns an rx stream of potok events (or `rx/empty` when the frame shape
  cannot be resolved). Mirrors `collections/commit-cms-data` but targets a
  shape (not a page), so `set-plugin-data` needs the page-id arity."
  [it state frame-id new-config]
  (let [page-id   (:current-page-id state)
        file-id   (:current-file-id state)
        file-data (dsh/lookup-file-data state file-id)
        shape     (dsh/lookup-shape state page-id frame-id)]
    (if (nil? shape)
      (rx/empty)
      (let [undo-id (js/Symbol)
            changes (-> (pcb/empty-changes it page-id)
                        (pcb/with-file-data file-data)
                        (pcb/with-page-id page-id)
                        (pcb/set-plugin-data :shape
                                             frame-id
                                             page-id
                                             forms-namespace
                                             forms-key
                                             (write-form-config new-config)))]
        (rx/of (dwu/start-undo-transaction undo-id)
               (dch/commit-changes changes)
               (dwu/commit-undo-transaction undo-id))))))

;; --- Config mutators -------------------------------------------------------

(defn set-form-config
  "Replace the whole form config on `frame-id` with `config`. Used by the
  menu's high-level controls (e.g. the 'Enable form' toggle)."
  [{:keys [frame-id config]}]
  (ptk/reify ::set-form-config
    ptk/WatchEvent
    (watch [it state _]
      (let [shape (dsh/lookup-shape state (:current-page-id state) frame-id)
            cfg   (or config (empty-form-config))]
        (commit-form-config it state frame-id cfg)))))

(defn add-field
  "Add a new field to the form on `frame-id`. `field` may carry
  `:name :label :type :required :shape-id :options`; missing keys default."
  [{:keys [frame-id field]}]
  (ptk/reify ::add-field
    ptk/WatchEvent
    (watch [it state _]
      (let [shape  (dsh/lookup-shape state (:current-page-id state) frame-id)
            cfg    (current-form-config shape)
            fld    (merge
                    {:id        (uuid/next)
                     :name      "field"
                     :label     "Field"
                     :type      :text
                     :required  false
                     :shape-id  nil
                     :options   []
                     :test-value ""}
                    field)
            cfg'   (update cfg :fields conj fld)]
        (commit-form-config it state frame-id cfg')))))

(defn update-field
  "Apply `patch` (a map) to the field identified by `field-id` on the form
  for `frame-id`. No-op when the field is not found."
  [{:keys [frame-id field-id patch]}]
  (ptk/reify ::update-field
    ptk/WatchEvent
    (watch [it state _]
      (let [shape (dsh/lookup-shape state (:current-page-id state) frame-id)
            cfg   (current-form-config shape)
            fields (->> (:fields cfg)
                        (mapv (fn [f]
                                (if (= (:id f) field-id)
                                  (merge f patch)
                                  f))))
            cfg'  (assoc cfg :fields fields)]
        (commit-form-config it state frame-id cfg')))))

(defn remove-field
  "Remove the field identified by `field-id` from the form on `frame-id`."
  [{:keys [frame-id field-id]}]
  (ptk/reify ::remove-field
    ptk/WatchEvent
    (watch [it state _]
      (let [shape  (dsh/lookup-shape state (:current-page-id state) frame-id)
            cfg    (current-form-config shape)
            fields (vec (remove #(= (:id %) field-id) (:fields cfg)))
            cfg'   (assoc cfg :fields fields)]
        (commit-form-config it state frame-id cfg')))))

(defn set-action
  "Set the submission action for the form on `frame-id`. `action` is a map
  `{:type :ovion-cloud :endpoint <str|nil> :name <str>}`."
  [{:keys [frame-id action]}]
  (ptk/reify ::set-action
    ptk/WatchEvent
    (watch [it state _]
      (let [shape (dsh/lookup-shape state (:current-page-id state) frame-id)
            cfg   (current-form-config shape)
            cfg'  (assoc cfg :action (merge (:action cfg) action))]
        (commit-form-config it state frame-id cfg')))))

(defn set-messages
  "Set the success/error messages for the form on `frame-id`. Either key in
  `messages` may be nil to leave it unchanged."
  [{:keys [frame-id messages]}]
  (ptk/reify ::set-messages
    ptk/WatchEvent
    (watch [it state _]
      (let [shape (dsh/lookup-shape state (:current-page-id state) frame-id)
            cfg   (current-form-config shape)
            cfg'  (merge cfg (d/without-nils messages))]
        (commit-form-config it state frame-id cfg')))))

;; --- Test-submit (Tauri submit_form) ---------------------------------------

(defn- field-value
  "Derive a submission value for `field` from its bound shape's current text
  content, falling back to the field's `:test-value`, then \"\"."
  [state page-id field]
  (let [shape-id (:shape-id field)]
    (if (nil? shape-id)
      (or (:test-value field) "")
      (let [shape (dsh/lookup-shape state page-id shape-id)
            ;; Text shapes store their content as a string; groups/rects
            ;; have none — fall back to test-value / "".
            content (when (map? shape) (:content shape))
            text    (when (and (string? content) (not (empty? content))) content)]
        (or text (:test-value field) "")))))

(defn- build-payload
  "Build the JSON payload map from the form's bound field shapes. Each
  entry is `{field-name value}`."
  [state page-id config]
  (let [fields (:fields config)]
    (reduce (fn [acc fld]
              (assoc acc (:name fld) (field-value state page-id fld)))
            {}
            fields)))

(defn form-submit-succeeded
  "Event carrying the parsed response from `submit_form`. Emitted by
  `test-submit-form` on success. Stashes the result under
  `[:forms :last-submit-result]` for the menu to display."
  [result]
  (ptk/reify ::form-submit-succeeded
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:forms :last-submit-result]
                {:ok true :data result}))))

(defn form-submit-failed
  "Event carrying the submit error string. Emitted by `test-submit-form`
  on failure."
  [error]
  (ptk/reify ::form-submit-failed
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:forms :last-submit-result]
                {:ok false :error error}))))

(defn test-submit-form
  "Fire the Tauri `submit_form` command for the form configured on
  `frame-id`, using the configured endpoint/name and a payload built from
  the bound field shapes' current values. Detached promise — routes the
  result back into the store via `form-submit-succeeded` /
  `form-submit-failed` (mirrors `publish-current-site`).
  Returns `rx/empty` (the WatchEvent's stream is empty; side-effects fire
  on promise resolve)."
  [{:keys [frame-id]}]
  (ptk/reify ::test-submit-form
    ptk/WatchEvent
    (watch [_ state _]
      (let [page-id  (:current-page-id state)
            shape    (dsh/lookup-shape state page-id frame-id)
            cfg      (current-form-config shape)]
        (if (or (nil? shape) (empty? (:fields cfg)))
          (rx/empty)
          (let [action   (:action cfg)
                endpoint (:endpoint action)
                name     (:name action)
                payload  (build-payload state page-id cfg)
                request  (clj->js {:endpoint  (or endpoint nil)
                                   :form_name (or name "form")
                                   :payload   payload
                                   :token     nil})
                handle     (fn [result]
                             (let [res (js->clj result :keywordize-keys true)]
                               (st/emit! (form-submit-succeeded res))))
                handle-err (fn [err]
                             (st/emit! (form-submit-failed (str err))))]
            ;; Detached promise — side-effects fire via st/emit! on resolve.
            (-> (invoke "submit_form" #js {:request request})
                (p/then handle)
                (p/catch handle-err))
            (rx/empty)))))))