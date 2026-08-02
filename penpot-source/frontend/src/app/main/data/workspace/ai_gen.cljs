;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.ai-gen
  "Feature 3 + 4 — data-layer wrappers around the closed AI backend
  (`src-tauri/src/llm.rs`). The UI bar (`ai_bar.cljs`) calls these helpers
  directly; they return `promesa` promises. Results are fed back into the
  potok store by the caller (e.g. `apply-design-spec` from `design_gen`).

  Keeps the AI pipeline stateless from potok's perspective: the bar holds
  UI state locally (busy, stage, prompt, attachments) and the only global
  mutation is the canvas commit (`apply-design-spec`), which is one undo
  transaction. Progress stage text arrives via the Tauri `ai-progress`
  event (`subscribe-progress`)."
  (:require
   ["@tauri-apps/api/core" :refer [invoke]]
   ["@tauri-apps/api/event" :as tevent]
   [cuerdas.core :as str]
   [app.common.data.macros :as dm]
   [app.main.data.workspace.design-gen :as dg]
   [app.main.store :as st]
   [app.util.i18n :as i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]
   [promesa.core :as p]))

;; ── Files → backend FileInput ────────────────────────────────────────────────

(defn file->input
  "Read a browser File/Blob into a backend FileInput map {:name :mime :base64}.
   Returns a promesa promise. The base64 string is raw (no data: prefix); the
   backend re-wraps it as a data URL for the vision provider."
  [file]
  (p/create
   (fn [resolve reject]
     (let [reader (js/FileReader.)]
       (set! (.. reader -onload)
             (fn [e]
               (let [result (.. e -target -result)
                     ;; result is a data URL: data:<mime>;base64,<b64>
                     b64 (if (string? result)
                           (let [idx (.indexOf result ",")]
                             (if (pos? idx) (.substring result (inc idx)) result))
                           "")]
                 (resolve {:name (or (.. file -name) "attachment")
                           :mime (or (.. file -type) "application/octet-stream")
                           :base64 b64}))))
       (set! (.. reader -onerror) (fn [err] (reject err)))
       (.readAsDataURL reader file)))))

(defn files->inputs
  "Read a seq of Files into FileInput maps (parallel). Returns a promesa
   promise of a vector."
  [files]
  (->> (p/all (mapv file->input files))))

;; ── Request building ─────────────────────────────────────────────────────────

(defn build-request
  "Assemble the JS object the Rust `llm_generate` command expects.

   Keys:
     :prompt      prompt string (URL references may be embedded; the backend
                  extracts them via `extract_urls`)
     :files       vector of FileInput maps ({:name :mime :base64} or {:path})
     :options     map with:
       :target        \"full\" | \"update-selection\" (default \"full\")
       :quality       \"max\" | \"auto\" (default: backend config)
       :frame-preset  \"mobile\" | \"web\" | \"auto\" …
       :frame-width   / :frame-height  (override preset)
       :file-id       for conversation memory
       :use-memory    boolean
       :selection     {:bounds {:x :y :width :height} :shapes <snippet>} for
                      region updates (Feature 4)"
  [{:keys [prompt files options]}]
  (let [opts (-> {:target       (:target options "full")
                  :quality      (:quality options)
                  :frame_preset  (:frame-preset options)
                  :frame_width   (:frame-width options)
                  :frame_height  (:frame-height options)
                  :file_id       (:file-id options)
                  :use_memory    (:use-memory options)}
                 (cond-> (:selection options)
                   (assoc :selection (clj->js (:selection options))))
        req #js {:prompt  (str prompt)
                 :files   (apply array (mapv clj->js files))
                 :options (clj->js opts)}]
    req))

;; ── Invoke wrappers ──────────────────────────────────────────────────────────

(defn invoke-generate
  "Invoke `llm_generate`. `request` is the GenerateRequest JS object
  ({:prompt :files :options}); Tauri expects it under the `request` param
  name (Tauri v2 camelCases top-level command params). Returns a promesa
  promise resolving to the DesignSpec as a JS object — keywordize with
  `js->clj` at the call site before `apply-design-spec`."
  [request]
  (invoke "llm_generate" #js {:request request}))

(defn invoke-cancel
  "Cancel any in-flight generation. Resolves when the abort flag is set."
  []
  (invoke "llm_cancel" #js {}))

(defn invoke-clear-memory
  "Clear the per-file AI conversation memory for `file-id`."
  [file-id]
  (invoke "llm_clear_memory" #js {:fileId file-id}))

(defn invoke-get-config
  "Read the AI config (provider, mode, model slugs, memory settings). Returns
   a promesa promise of a JS object."
  []
  (p/then (invoke "llm_get_config" #js {}) identity))

(defn invoke-set-config
  "Write the AI config. Pass a CLJS map; `clj->js` turns the snake_case
   keyword keys (:deepinfra_api_key …) into the snake_case string keys the
   backend `LlmConfig` struct expects (serde field names, no rename_all).
   Top-level command param `config` is camelCased by Tauri v2 automatically."
  [config]
  (invoke "llm_set_config" #js {:config (clj->js config)}))

;; ── Progress events ──────────────────────────────────────────────────────────

(defn subscribe-progress
  "Subscribe to backend `ai-progress` events. `handler` is called with the
   payload {:stage :detail} (as a JS object). Returns a promesa promise that
   resolves to the unlisten function — call it to stop listening. Intended
   for use in a component effect:

     (mf/use-effect
       (fn []
         (let [unp (ai/subscribe-progress #(handle (.-payload %)))]
           (fn [] (p/then unp (fn [u] (u)))))))"
  [handler]
  (tevent/listen "ai-progress" (fn [e] (handler (.-payload e)))))

;; ── Prompt helpers ───────────────────────────────────────────────────────────

(defn attach-url
  "Embed a reference URL into the prompt so the backend's `extract_urls`
   picks it up and fetches/scouts the site before generation. No-op when the
   URL is blank."
  [prompt url]
  (if (or (nil? url) (str/empty? url))
    prompt
    (dm/str "[Reference URL: " url "]\n\n" (or prompt ""))))

;; ── Potok state events ───────────────────────────────────────────────────────
;;
;; The AI bar reads :ai-busy / :ai-preview / :ai-error from :workspace-local
;; via refs defined in `app.main.refs`. These UpdateEvents mutate those slots.
;; Generation is fired as a WatchEvent (so it can read state for the selection
;; snippet + bounds) whose async result is fed back through st/emit!.

(defn set-ai-busy
  [busy]
  (ptk/reify ::set-ai-busy
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :ai-busy] busy))))

(defn set-ai-stage
  [stage]
  (ptk/reify ::set-ai-stage
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :ai-stage] stage))))

(defn set-ai-preview
  "Store the generated spec + target so the bar can show the preview modal.
   `preview` is {:spec <clj spec> :target \"full\"|\"update-selection\"} or nil."
  [preview]
  (ptk/reify ::set-ai-preview
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :ai-preview] preview))))

(defn clear-ai-preview
  []
  (ptk/reify ::clear-ai-preview
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :ai-preview] nil))))

(defn set-ai-error
  [message]
  (ptk/reify ::set-ai-error
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :ai-error] message))))

;; ── The generate event ───────────────────────────────────────────────────────

(defn- err->str
  [err]
  (cond
    (string? err)    err
    (some-> err .-message) (.-message err)
    :else (tr "workspace.ai.bar.error-generic")))

(defn generate-design
  "Fire an AI generation. Reads potok state to build the selection snippet +
   bounds for region updates, invokes `llm_generate`, and on success stores
   the spec as a preview (the bar's Apply button then commits it via
   `dg/apply-design-spec`). On error sets :ai-error.

   Keys:
     :prompt   prompt string (URL references already embedded by the caller)
     :files    vector of FileInput maps ({:name :mime :base64})
     :options  {:target \"full\"|\"update-selection\"
                :quality \"max\"|\"auto\"
                :frame-preset \"mobile\"|\"web\"|…
                :frame-width / :frame-height
                :use-memory boolean}"
  [{:keys [prompt files options]}]
  (ptk/reify ::generate-design
    ptk/WatchEvent
    (watch [_ state _]
      (let [target     (:target options "full")
            is-update  (= target "update-selection")
            bounds     (when is-update (dg/selection-bounds state))
            ;; Only attach a selection context when we actually have a non-empty
            ;; bounds rect — the backend's `bounds: Rectf` is non-Option, so a
            ;; JSON `null` would fail to deserialize.
            selection  (when (and is-update bounds)
                        {:bounds bounds
                         :shapes (or (dg/selection->snippet state) [])})
            file-id    (str (:current-file-id state))
            opts       (cond-> (assoc options :file-id file-id)
                        selection (assoc :selection selection))
            request    (build-request {:prompt prompt :files files :options opts})

            handle     (fn [result]
                         (let [spec (js->clj result :keywordize-keys true)]
                           (st/emit! (set-ai-busy false)
                                     (set-ai-error nil)
                                     (set-ai-preview {:spec spec :target target}))))
            handle-err (fn [err]
                         (st/emit! (set-ai-busy false)
                                   (set-ai-error (err->str err))))]
        ;; Detached promise: fires side-effects via st/emit! when it resolves.
        (-> (invoke-generate request)
            (p/then handle)
            (p/catch handle-err))
        ;; Mark busy immediately so the bar shows its spinner.
        (rx/of (set-ai-busy true))))))

(defn cancel-generation
  "Cancel any in-flight generation and clear the busy flag."
  []
  (ptk/reify ::cancel-generation
    ptk/WatchEvent
    (watch [_ _ _]
      ;; Detached promise: clears busy when the backend acknowledges. We also
      ;; clear it immediately below so the UI reacts without waiting on IPC.
      (-> (invoke-cancel)
          (p/then  (fn [_] (st/emit! (set-ai-busy false))))
          (p/catch (fn [_] (st/emit! (set-ai-busy false)))))
      (rx/of (set-ai-busy false)))))