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
   [app.main.data.workspace.ai-agent :as aia]
   [app.main.data.workspace.ai-tools :as ait]
   [app.main.data.workspace.design-gen :as dg]
   [app.main.store :as st]
   [app.util.i18n :as i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]
   [promesa.core :as p]))

;; ── Files → backend FileInput ────────────────────────────────────────────────

;; Monotonic generation id used to drop stale results. The backend HTTP
;; request cannot be interrupted mid-flight (reqwest has no cancel token here),
;; so a cancelled generation still resolves eventually. Each generate-design
;; call captures a fresh id; cancel-generation (and any new generation) bumps
;; the atom, so a late-arriving result whose captured id no longer matches
;; `@gen-id` is dropped instead of clobbering the preview / busy / error state.
(def ^:private gen-id (atom 0))

(defn bump-gen-id
  "Bump the stale-result guard and return the new id. The agent loop captures
  this at start and checks it before applying a final spec, so a cancel (which
  also bumps) invalidates a late-arriving spec."
  []
  (swap! gen-id inc))

(defn gen-id-current []
  @gen-id)

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
       :target        \"new-board\" | \"update-selection\" (default \"new-board\")
       :quality       \"max\" | \"auto\" (default: backend config)
       :frame-preset  \"mobile\" | \"web\" | \"auto\" …
       :frame-width   / :frame-height  (override preset)
       :file-id       for conversation memory
       :use-memory    boolean
       :selection     {:bounds {:x :y :width :height} :shapes <snippet>} for
                      region updates (Feature 4)"
  [{:keys [prompt files options]}]
  (let [opts (-> {:target       (:target options "new-board")
                  :quality      (:quality options)
                  :frame_preset  (:frame-preset options)
                  :frame_width   (:frame-width options)
                  :frame_height  (:frame-height options)
                  :file_id       (:file-id options)
                  :use_memory    (:use-memory options)
                  :variants      (:variants options)}
                 (cond-> (:selection options)
                   (assoc :selection (clj->js (:selection options)))))
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

(defn ai-usable?
  "Given a keywordized `llm_get_config` view (with `*_set` presence flags + the
   active `:provider`), return true if the AI can actually run on the current
   provider. DeepInfra / Ovion Cloud need a configured key/token; local Ollama
   does not (it is keyless, so it is always considered usable — a missing/offline
   Ollama server surfaces as a normal runtime error, not a 'no key' guard). Used
   by the AI bar so a user with no key is sent to Settings instead of firing a
   request that will simply 401."
  [cfg]
  (case (:provider cfg)
    "ollama"      true
    "ovion-cloud" (boolean (:ovion_cloud_token_set cfg))
    "deepinfra"   (boolean (:deepinfra_api_key_set cfg))
    ;; Unknown provider defaults to the DeepInfra key requirement.
    (boolean (:deepinfra_api_key_set cfg))))

;; ── Phase 2 IPC wrappers (design system / review / spec-doc / image / MCP) ────
;;
;; Mirror the existing `invoke-*` style: a thin promesa wrapper around the
;; Tauri `invoke` for the Phase 2 `llm_*` commands registered in lib.rs. Each
;; returns a promesa promise of the JS object the backend serializes; the
;; caller keywordizes with `js->clj` at the call site.

(defn invoke-generate-design-system
  "Generate a design-system (tokens) payload from a prompt and optional
   reference image/URL. Returns a JS object
   {:colors [{:name :value}] :typography {:families [...] :scale [...]}
    :spacing [i32] :radii [i32]}."
  [{:keys [prompt source-image source-url]}]
  (invoke "llm_generate_design_system"
          #js {:request (clj->js {:prompt       (str prompt)
                                  :source_image (clj->js source-image)
                                  :source_url   source-url})}))

(defn invoke-review-design
  "Run a NON-mutating UX review. `screenshot` is a FileInput map
   {:name :mime :base64}; `selection-meta` is a free CLJS map (bounds + ids).
   Returns a JS object {:score :summary :strengths :issues :recommendations}."
  [screenshot selection-meta]
  (invoke "llm_review_design"
          #js {:request (clj->js {:screenshot      (clj->js screenshot)
                                  :selection_meta (clj->js selection-meta)})}))

(defn invoke-generate-spec-doc
  "Generate a spec document (markdown + html) for the given scope. `scope` is
   \"page\" | \"selection\"; `scene` is the serialized scene value (the output
   of `dg/serialize-scene`). Returns a JS object {:markdown :html}."
  [scope scene]
  (invoke "llm_generate_spec_doc"
          #js {:request (clj->js {:scope scope :scene (clj->js scene)})}))

(defn invoke-generate-image
  "Generate a raster image from `prompt`. `size` is \"1024x1024\" |
   \"1024x1792\" | \"1792x1024\". Returns a JS object {:image_base64 :mime}."
  [prompt size]
  (invoke "llm_generate_image"
          #js {:request (clj->js {:prompt (str prompt) :size (str size)})}))

(defn invoke-remove-background
  "Remove the background from `image-input` (a FileInput map). Returns a JS
   object {:image_base64 :mime}."
  [image-input]
  (invoke "llm_remove_background"
          #js {:request (clj->js {:image (clj->js image-input)})}))

(defn invoke-upscale-image
  "Upscale `image-input` (a FileInput map) by `scale` (2 | 4). Returns a JS
   object {:image_base64 :mime}."
  [image-input scale]
  (invoke "llm_upscale_image"
          #js {:request (clj->js {:image (clj->js image-input) :scale scale})}))

(defn invoke-mcp-start
  "Start the MCP server on `port` (0 = auto-pick). Resolves to nil."
  [port]
  (p/then (invoke "llm_mcp_start" #js {:port port}) identity))

(defn invoke-mcp-stop
  "Stop the MCP server. Resolves to nil."
  []
  (p/then (invoke "llm_mcp_stop" #js {}) identity))

(defn invoke-mcp-status
  "Read the MCP server status. Returns a JS object {:running :port :tools}."
  []
  (p/then (invoke "llm_mcp_status" #js {}) identity))

(defn invoke-mcp-tool-result
  "Ship a tool result back to the Rust MCP server so it can resolve the
  oneshot waiting on the tools/call round-trip. `id` is the u64 call id from
  the mcp-tool-call event payload; `result` is a CLJS map — clj->js turns it
  into the JS object Rust deserializes into serde_json::Value. Fire-and-forget."
  [id result]
  (invoke "llm_mcp_tool_result" #js {:requestId id :result (clj->js result)}))

;; ── Agent loop IPC wrappers ───────────────────────────────────────────────────
;;
;; The agent loop is CLJS-driven; Rust (`llm.rs`) is a stateless one-step model
;; service. Each step is one `llm_agent_step` call returning an
;; AgentStepResponse (kind "spec" | "tool_calls" | "text" | "error"). Loop
;; state (messages, step count, cancel) lives in CLJS.

(defn invoke-agent-step
  "One model step. `request` is the AgentStepRequest JS object
  ({:messages :tools :options :files :model}); Tauri camelCases the top-level
  param name. Returns a promesa promise resolving to the AgentStepResponse JS
  object — keywordize with `js->clj` at the call site."
  [request]
  (invoke "llm_agent_step" #js {:request request}))

(defn invoke-agent-reset
  "Reset the backend ABORT flag so a fresh agent run is not immediately
  cancelled by a prior cancel. Resolves to nil."
  []
  (p/then (invoke "llm_agent_reset" #js {}) identity))

(defn invoke-agent-progress
  "Emit an `ai-progress` event from CLJS (loop-level stage text). Used so the
  bar's existing `subscribe-progress` cond surfaces agent stage strings without
  any new UI. `stage` is the stage key (e.g. \"tool-thinking\",
  \"executing-tool\"); `detail` is a free string (e.g. the tool name)."
  [stage detail]
  (invoke "llm_agent_progress" #js {:stage stage :detail (str detail)}))

(defn build-agent-step-request
  "Assemble the AgentStepRequest JS object. `messages` is a vector of CLJS
  message maps; `tools` is the CLJS tools-list from `ait/tools-list`; `files`
  is a vector of FileInput maps (vision attachments; pass [] on non-first
  steps); `model` is an optional model slug (nil = backend default GLM)."
  [{:keys [messages tools files options model]}]
  (let [opts (-> {:target       (:target options "new-board")
                  :quality      (:quality options)
                  :frame_preset  (:frame-preset options)
                  :frame_width   (:frame-width options)
                  :frame_height   (:frame-height options)
                  :file_id       (:file-id options)
                  :use_memory    (:use-memory options)}
                 (cond-> (:selection options)
                   (assoc :selection (clj->js (:selection options)))))]
    #js {:messages (clj->js messages)
         :tools   (if (seq tools) (clj->js tools) nil)
         :options (clj->js opts)
         :files   (apply array (mapv clj->js files))
         :model   model}))

;; ── Viewport capture (visual channel) ─────────────────────────────────────────
;;
;; Capture the workspace design canvas (`#render` — an <svg> for the classic
;; renderer or a <canvas> for the WASM renderer) as a PNG FileInput for the
;; vision model. Pure browser canvas API; NO Tauri command, NO Rust recompile
;; (lowest static-verify risk under no-build). Fonts may render as fallback in
;; the SVG path — the scene-text channel (`dg/serialize-scene`) carries the
;; exact typography, so the image is a supplementary visual hint, not the sole
;; context. Returns a promesa promise of a FileInput map or nil on any failure
;; (tainted canvas, missing node). The loop treats nil as 'no visual'.

(defn- attr-num
  [el k fallback]
  (let [v (.getAttribute el k)]
    (if (seq v)
      (let [n (js/parseFloat v)]
        (if (js/isFinite n) n fallback))
      fallback)))

(defn- data-url->file-input
  [data-url]
  (let [idx (.indexOf data-url ",")]
    (when (pos? idx)
      {:name "viewport.png" :mime "image/png"
       :base64 (.substring data-url (inc idx))})))

(defn- canvas->file-input
  "Sync rasterization of a <canvas> via an off-DOM scratch canvas (so the
  source is not mutated). Returns a FileInput or nil."
  [src]
  (try
    (let [w (.-width src) h (.-height src)
          scratch (js/document.createElement "canvas")
          _ (set! (.-width scratch) w)
          _ (set! (.-height scratch) h)
          ctx (.getContext scratch "2d")]
      (.drawImage ctx src 0 0 w h)
      (data-url->file-input (.toDataURL scratch "image/png")))
    (catch :default _ nil)))

(defn- svg->file-input
  "Async rasterization of an <svg> via Blob URL → Image → canvas. Capped to
  1600px on the long edge. Returns a promesa promise of a FileInput or nil."
  [node]
  (p/create
   (fn [resolve]
     (try
       (let [w (attr-num node "width" 1440)
             h (attr-num node "height" 900)
             cap 1600
             scale (if (> (max w h) cap) (/ cap (max w h)) 1)
             cw (* w scale) ch (* h scale)
             clone (.cloneNode node true)
             _ (when-not (seq (.getAttribute node "viewBox"))
                 (.setAttribute clone "viewBox" (str "0 0 " w " " h)))
             _ (.setAttribute clone "width" cw)
             _ (.setAttribute clone "height" ch)
             _ (.setAttribute clone "xmlns" "http://www.w3.org/2000/svg")
             xml (.serializeToString (js/XMLSerializer.) clone)
             blob (js/Blob. #js [xml] #js {:type "image/svg+xml;charset=utf-8"})
             url (js/URL.createObjectURL blob)
             img (js/Image.)]
         (set! (.. img -onload)
               (fn []
                 (try
                   (let [canvas (js/document.createElement "canvas")
                         ctx (.getContext canvas "2d")]
                     (set! (.-width canvas) cw)
                     (set! (.-height canvas) ch)
                     (.drawImage ctx img 0 0 cw ch)
                     (js/URL.revokeObjectURL url)
                     (resolve (data-url->file-input (.toDataURL canvas "image/png"))))
                   (catch :default _
                     (js/URL.revokeObjectURL url) (resolve nil)))))
         (set! (.. img -onerror) (fn [] (js/URL.revokeObjectURL url) (resolve nil)))
         (set! (.. img -src) url))
       (catch :default _ (resolve nil))))))

(defn capture-viewport-png
  "Capture the workspace `#render` canvas as a PNG FileInput for the vision
  model, or nil. Returns a promesa promise. See section header for the
  trade-offs (font fallback, tainted-canvas guard)."
  []
  (try
    (let [node (js/document.getElementById "render")]
      (cond
        (nil? node)                     (p/resolved nil)
        (instance? js/HTMLCanvasElement node) (p/resolved (canvas->file-input node))
        (instance? js/SVGSVGElement node)     (svg->file-input node)
        :else                            (p/resolved nil)))
    (catch :default _ (p/resolved nil))))

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

;; ── AI tool helpers (Figma #71) ─────────────────────────────────────────────
;;
;; "Rename with AI" + "AI text generation" reuse the existing llm_generate
;; plumbing (no Rust changes). A crafted prompt is sent through the same
;; `invoke-generate` path used for design generation; the result is a
;; DesignSpec JS object, so the tool helpers do a best-effort extraction of
;; a short plain-text answer (a layer name / placeholder copy) from it.
;; Faithful rename/text-gen needs a plain-text response mode in the backend
;; (an `llm_text` command returning a string, not a DesignSpec); that Rust
;; addition is DEFERRED under the no-build constraint. The UI + prompt
;; crafting + invoke + best-effort apply are wired so the feature is usable
;; today and complete the moment the backend gains a text response mode.

(defn rename-prompt
  "Craft the prompt for suggesting a short, descriptive name for `shape`."
  [shape]
  (let [type (name (or (:type shape) :shape))
        name (or (:name shape) "(unnamed)")]
    (dm/str
     "Suggest a single short, descriptive layer name (max 4 words, no quotes, "
     "no punctuation) for a design layer of type \"" type "\" currently named \""
     name "\". Reply with only the name.")))

(defn text-gen-prompt
  "Craft the prompt for generating placeholder copy for a text layer whose
   current content/label is `label`."
  [label]
  (dm/str
   "Generate a single short line of realistic placeholder copy for a UI text "
   "element labeled \"" (or label "(empty)") "\". Reply with only the copy, "
   "no quotes, no explanation."))

(defn build-tool-request
  "Assemble a minimal llm_generate request for a plain-prompt tool call
   (no files, new-board target). Reuses `build-request` so the wire format
   matches the design-generation path exactly."
  [prompt]
  (build-request {:prompt  prompt
                  :files  []
                  :options {:target     "new-board"
                            :quality    "auto"
                            :use-memory false}}))

(defn- first-text-in
  "Depth-first search for the first non-empty string under :text/:content
   or a node :name in a keywordized spec map. Returns nil when none found."
  [node]
  (when (map? node)
    (or (when (string? (:text node)) (str/trim (:text node)))
        (when (string? (:content node)) (str/trim (:content node)))
        (some first-text-in (seq (:children node)))
        (some first-text-in (seq (:shapes node)))
        (some first-text-in (seq (:frames node)))
        (some first-text-in (seq (:nodes node))))))

(defn extract-text-from-spec
  "Best-effort extraction of a short plain-text answer from a DesignSpec
   result (the JS object returned by `llm_generate`). Returns a trimmed
   string or nil. Tries a top-level :text/:name, then descends into the
   spec's node tree. The result is collapsed to a single line so it can be
   used as a layer name or inserted as a one-line text content."
  [result-js]
  (let [spec (js->clj result-js :keywordize-keys true)
        raw  (or (when (string? (:text spec)) (str/trim (:text spec)))
                 (when (string? (:name spec)) (str/trim (:name spec)))
                 (first-text-in spec))]
    (some-> raw
            (str/replace #"\s+" " ")
            str/trim
            not-empty)))

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
   `preview` is {:spec <clj spec> :target \"new-board\"|\"update-selection\"} or nil."
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

(defn set-ai-update-sel
  "Persist the 'Update only the selection' generation preference (shared
   between the AI bar and the AI Settings modal via refs/ai-update-sel).
   `value` is boolean; nil leaves the default (true) in place."
  [value]
  (ptk/reify ::set-ai-update-sel
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :ai-update-sel] value))))

(defn set-ai-design-system
  "Store the generated design-system (tokens) payload so the bar / settings can
   preview it. `payload` is the keywordized
   {:colors [{:name :value}] :typography {:families :scale} :spacing :radii}
   map, or nil."
  [payload]
  (ptk/reify ::set-ai-design-system
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :ai-design-system] payload))))

(defn set-ai-review
  "Store the UX review result. `payload` is the keywordized
   {:score :summary :strengths :issues :recommendations} map, or nil.
   NON-mutating — review never touches the canvas."
  [payload]
  (ptk/reify ::set-ai-review
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :ai-review] payload))))

(defn set-ai-spec-doc
  "Store the generated spec document. `payload` is the keywordized
   {:markdown :html} map, or nil."
  [payload]
  (ptk/reify ::set-ai-spec-doc
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :ai-spec-doc] payload))))

(defn set-ai-image
  "Store a generated/edited image. `payload` is {:base64 :mime} or nil. Shared
   by generate-image, remove-background, and upscale-image."
  [payload]
  (ptk/reify ::set-ai-image
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :ai-image] payload))))

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
     :options  {:target \"new-board\"|\"update-selection\"
                :quality \"max\"|\"auto\"
                :frame-preset \"mobile\"|\"web\"|…
                :frame-width / :frame-height
                :use-memory boolean}"
  [{:keys [prompt files options]}]
  (ptk/reify ::generate-design
    ptk/WatchEvent
    (watch [_ state _]
      (let [my-id      (swap! gen-id inc)
            target     (:target options "new-board")
            variants   (or (:variants options) 1)
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

            ;; gen-id guard: drop the result if this generation was cancelled
            ;; or superseded while the (uninterruptible) HTTP request was in
            ;; flight — otherwise a cancelled run would still pop its preview
            ;; and a fast cancel+regenerate would race the wrong spec in.
            ;;
            ;; Variants: the backend may fan out (`:variants` > 1 threaded into
            ;; `build-request` options) and return either a bare DesignSpec or
            ;; `{:specs [spec ...]}`. variants=1 is byte-identical-when-inactive:
            ;; the preview carries `:spec` only. variants>1 carries `:specs` so
            ;; the UI carousel can render them; `:spec` is omitted on that path.
            handle     (fn [result]
                         (when (= my-id @gen-id)
                           (let [res   (js->clj result :keywordize-keys true)
                                 specs (if (contains? res :specs)
                                         (vec (:specs res))
                                         [res])
                                 preview (if (> variants 1)
                                           {:specs specs :target target}
                                           {:spec (first specs) :target target})]
                             (st/emit! (set-ai-busy false)
                                       (set-ai-error nil)
                                       (set-ai-preview preview)))))
            handle-err (fn [err]
                         (when (= my-id @gen-id)
                           (st/emit! (set-ai-busy false)
                                     (set-ai-error (err->str err)))))]
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
      ;; Invalidate any in-flight generation so its late-arriving result is
      ;; dropped by the gen-id guard in generate-design. The backend HTTP
      ;; request itself can't be interrupted (no cancel token), but this
      ;; guarantees a cancelled run never clobbers the preview/busy/error state.
      (swap! gen-id inc)
      ;; Detached promise: clears busy when the backend acknowledges. We also
      ;; clear it immediately below so the UI reacts without waiting on IPC.
      (-> (invoke-cancel)
          (p/then  (fn [_] (st/emit! (set-ai-busy false))))
          (p/catch (fn [_] (st/emit! (set-ai-busy false)))))
      (rx/of (set-ai-busy false)))))

;; ── Phase 2 WatchEvents (design system / review / spec-doc / image) ──────────
;;
;; Each mirrors `generate-design`'s shape: a `my-id` from the gen-id guard, a
;; detached promise that feeds the result back through st/emit!, into a Phase 2
;; state slot, and an immediate `set-ai-busy true` so the bar shows its spinner.
;; A late-arriving result whose `my-id` no longer matches `@gen-id` is dropped
;; (cancel-generation / a newer generation bumps the atom). Errors land in
;; `:ai-error` via `err->str`. All are byte-identical-when-inactive: when the
;; caller never emits them, no state slot is touched.

(defn generate-design-system
  "Generate a design-system (tokens) payload. Keys:
     :prompt        prompt string
     :source-image  optional FileInput map ({:name :mime :base64})
     :source-url    optional reference URL string
   On success stores the payload via `set-ai-design-system`."
  [{:keys [prompt source-image source-url]}]
  (ptk/reify ::generate-design-system
    ptk/WatchEvent
    (watch [_ _ _]
      (let [my-id (swap! gen-id inc)
            handle
            (fn [result]
              (when (= my-id @gen-id)
                (let [payload (js->clj result :keywordize-keys true)]
                  (st/emit! (set-ai-busy false)
                            (set-ai-error nil)
                            (set-ai-design-system payload)))))
            handle-err
            (fn [err]
              (when (= my-id @gen-id)
                (st/emit! (set-ai-busy false)
                          (set-ai-error (err->str err)))))]
        (-> (invoke-generate-design-system
             {:prompt prompt :source-image source-image :source-url source-url})
            (p/then handle)
            (p/catch handle-err))
        (rx/of (set-ai-busy true))))))

(defn review-design
  "Run a NON-mutating UX review of the current canvas. Captures the viewport
   PNG + selection metadata, invokes `llm_review_design`, and stores the result
   via `set-ai-review`. Never touches the canvas."
  [{:keys [selection-meta]}]
  (ptk/reify ::review-design
    ptk/WatchEvent
    (watch [_ state _]
      (let [my-id (swap! gen-id inc)
            file-id (str (:current-file-id state))
            meta (or selection-meta
                     (let [bounds (dg/selection-bounds state)]
                       (when bounds
                         {:bounds bounds
                          :selection (or (dg/selection->snippet state) [])})))
            handle
            (fn [screenshot]
              (if (nil? screenshot)
                (when (= my-id @gen-id)
                  (st/emit! (set-ai-busy false)
                            (set-ai-error (tr "workspace.ai.bar.error-generic"))))
                (-> (invoke-review-design screenshot meta)
                    (p/then
                     (fn [result]
                       (when (= my-id @gen-id)
                         (let [payload (js->clj result :keywordize-keys true)]
                           (st/emit! (set-ai-busy false)
                                     (set-ai-error nil)
                                     (set-ai-review payload))))))
                    (p/catch
                     (fn [err]
                       (when (= my-id @gen-id)
                         (st/emit! (set-ai-busy false)
                                   (set-ai-error (err->str err)))))))))
            handle-err
            (fn [err]
              (when (= my-id @gen-id)
                (st/emit! (set-ai-busy false)
                          (set-ai-error (err->str err)))))]
        (-> (capture-viewport-png)
            (p/then handle)
            (p/catch handle-err))
        (rx/of (set-ai-busy true))))))

(defn generate-spec-doc
  "Generate a spec document for `scope` (\"page\" | \"selection\"). Serializes
   the current scene (page or selection snippet), invokes
   `llm_generate_spec_doc`, and stores the result via `set-ai-spec-doc`."
  [{:keys [scope]}]
  (ptk/reify ::generate-spec-doc
    ptk/WatchEvent
    (watch [_ state _]
      (let [my-id (swap! gen-id inc)
            sc    (or scope "page")
            scene (if (= sc "selection")
                    (or (dg/selection->snippet state) [])
                    (or (dg/serialize-scene state) ""))
            handle
            (fn [result]
              (when (= my-id @gen-id)
                (let [payload (js->clj result :keywordize-keys true)]
                  (st/emit! (set-ai-busy false)
                            (set-ai-error nil)
                            (set-ai-spec-doc payload)))))
            handle-err
            (fn [err]
              (when (= my-id @gen-id)
                (st/emit! (set-ai-busy false)
                          (set-ai-error (err->str err)))))]
        (-> (invoke-generate-spec-doc sc scene)
            (p/then handle)
            (p/catch handle-err))
        (rx/of (set-ai-busy true))))))

(defn generate-image
  "Generate a raster image from `prompt`. `size` is \"1024x1024\" |
   \"1024x1792\" | \"1792x1024\". Stores {:base64 :mime} via `set-ai-image`."
  [{:keys [prompt size]}]
  (ptk/reify ::generate-image
    ptk/WatchEvent
    (watch [_ _ _]
      (let [my-id (swap! gen-id inc)
            handle
            (fn [result]
              (when (= my-id @gen-id)
                (let [res (js->clj result :keywordize-keys true)
                      payload {:base64 (:image_base64 res)
                               :mime   (:mime res "image/png")}]
                  (st/emit! (set-ai-busy false)
                            (set-ai-error nil)
                            (set-ai-image payload)))))
            handle-err
            (fn [err]
              (when (= my-id @gen-id)
                (st/emit! (set-ai-busy false)
                          (set-ai-error (err->str err)))))]
        (-> (invoke-generate-image prompt (or size "1024x1024"))
            (p/then handle)
            (p/catch handle-err))
        (rx/of (set-ai-busy true))))))

(defn remove-background
  "Remove the background from `image-input` (a FileInput map). Stores the
   result {:base64 :mime} via `set-ai-image`."
  [{:keys [image-input]}]
  (ptk/reify ::remove-background
    ptk/WatchEvent
    (watch [_ _ _]
      (let [my-id (swap! gen-id inc)
            handle
            (fn [result]
              (when (= my-id @gen-id)
                (let [res (js->clj result :keywordize-keys true)
                      payload {:base64 (:image_base64 res)
                               :mime   (:mime res "image/png")}]
                  (st/emit! (set-ai-busy false)
                            (set-ai-error nil)
                            (set-ai-image payload)))))
            handle-err
            (fn [err]
              (when (= my-id @gen-id)
                (st/emit! (set-ai-busy false)
                          (set-ai-error (err->str err)))))]
        (-> (invoke-remove-background image-input)
            (p/then handle)
            (p/catch handle-err))
        (rx/of (set-ai-busy true))))))

(defn upscale-image
  "Upscale `image-input` (a FileInput map) by `scale` (2 | 4). Stores the
   result {:base64 :mime} via `set-ai-image`."
  [{:keys [image-input scale]}]
  (ptk/reify ::upscale-image
    ptk/WatchEvent
    (watch [_ _ _]
      (let [my-id (swap! gen-id inc)
            handle
            (fn [result]
              (when (= my-id @gen-id)
                (let [res (js->clj result :keywordize-keys true)
                      payload {:base64 (:image_base64 res)
                               :mime   (:mime res "image/png")}]
                  (st/emit! (set-ai-busy false)
                            (set-ai-error nil)
                            (set-ai-image payload)))))
            handle-err
            (fn [err]
              (when (= my-id @gen-id)
                (st/emit! (set-ai-busy false)
                          (set-ai-error (err->str err)))))]
        (-> (invoke-upscale-image image-input (or scale 2))
            (p/then handle)
            (p/catch handle-err))
        (rx/of (set-ai-busy true))))))

;; ── The agent loop ─────────────────────────────────────────────────────────────
;;
;; `run-agent-design` is the powerful, native path (the AI bar routes "max"
;; quality to it; "auto" stays on the single-shot `generate-design` so the
;; existing path — and the bar's visuals — are byte-identical-when-inactive).
;;
;; Pipeline (all detached promises; the WatchEvent returns immediately with a
;; busy flag, exactly like `generate-design`):
;;   0. bump gen-id guard + reset backend ABORT + mark busy/clear error
;;   1. capture viewport PNG (visual channel; nil = no visual)
;;   2. OPTIONAL scout step: Kimi (vision) sees the PNG + scene text → a short
;;      visual brief. Skipped when there is no viewport. Failure is non-fatal.
;;   3. tool-calling loop (GLM, up to `aia/max-steps`): each step is one
;;      `llm_agent_step`. On tool_calls → execute via `ait/execute-tool`,
;;      append assistant+tool-result messages, loop. On spec → preview (same
;;      `set-ai-preview` the bar already renders, so Apply/Cancel is identical).
;;      On text → done. On error / cancel / max-iter → stop.
;;
;; The loop reuses the bar's existing `subscribe-progress` cond (no new UI):
;; `invoke-agent-progress` emits `ai-progress` events with stage keys the bar
;; already maps (tool-thinking / executing-tool / agent-done / …).

(defn- kimi-slug
  "Pick the configured vision-model slug from the JS config object returned by
  `llm_get_config` (already keywordized)."
  [config]
  (if (= (:provider config) "ollama")
    (:ollama_kimi_model config)
    (:deepinfra_kimi_model config)))

(defn- parse-tool-args
  "Coerce a model-supplied `arguments` value (a JSON string, a JS object, or
  nil) into a keywordized CLJS map for `ait/execute-tool`."
  [args]
  (cond
    (nil? args)              {}
    (string? args)           (try (js->clj (js/JSON.parse args) :keywordize-keys true)
                                 (catch :default _ {}))
    (object? args)           (js->clj args :keywordize-keys true)
    (map? args)              args
    :else                    {}))

(defn- emit-agent-stage
  "Fire-and-forget an `ai-progress` event so the bar's existing stage line shows
  agent progress. Never throws."
  [stage detail]
  (-> (invoke-agent-progress stage detail) (p/catch (fn [_] nil))))

(defn- agent-finish-busy
  [my-id & events]
  (when (= my-id (gen-id-current))
    (apply st/emit! events)))

(defn- run-agent-loop
  "The recursive tool-calling loop. `state` is captured from the WatchEvent so
  read-tools (get_scene / get_selection) operate on the LIVE store (each step
  sees the latest page objects, including shapes created by prior tool calls)."
  [my-id state target options tools messages step]
  (letfn [(stop-max []
            (emit-agent-stage "agent-max-iterations" (str (aia/max-steps)))
            (agent-finish-busy my-id (set-ai-busy false) (set-ai-error nil)))
          (stop-error [err]
            (agent-finish-busy my-id (set-ai-busy false) (set-ai-error (err->str err))))
          (stop-done []
            (emit-agent-stage "agent-done" "")
            (agent-finish-busy my-id (set-ai-busy false) (set-ai-error nil)))
          (apply-spec [spec-js]
            (when (= my-id (gen-id-current))
              (let [spec (js->clj spec-js :keywordize-keys true)]
                (st/emit! (set-ai-busy false)
                          (set-ai-error nil)
                          (set-ai-preview {:spec spec :target target})))))
          (run-step [messages step]
            (if (>= step (aia/max-steps))
              (stop-max)
              (let [req (build-agent-step-request
                         {:messages messages :tools tools :files []
                          :options options :model nil})]
                (emit-agent-stage "tool-thinking" "")
                (-> (invoke-agent-step req)
                    (p/then
                     (fn [resp-js]
                       (let [resp (js->clj resp-js :keywordize-keys true)
                             kind (keyword (:kind resp))]
                         (cond
                           ;; Backend cancelled (ABORT) or model error.
                           (= kind :error) (stop-error (:error resp))
                           ;; A DesignSpec → preview (the bar's Apply button
                           ;; commits it; identical to the non-agent path).
                           (= kind :spec)  (apply-spec (:spec resp))
                           ;; Tool calls → execute, append results, loop.
                           (= kind :tool_calls)
                           (let [calls (vec (:tool_calls resp))]
                             (when (seq calls)
                               (emit-agent-stage
                                "executing-tool"
                                (str/join ", " (map #(get-in % [:function :name]) calls))))
                             (let [results (mapv
                                            (fn [c]
                                              (let [name (get-in c [:function :name])
                                                    args (parse-tool-args
                                                           (get-in c [:function :arguments]))]
                                                {:id (:id c)
                                                 :result (ait/execute-tool name args state)}))
                                            calls)
                                   new-messages (aia/append-tool-results messages resp results)]
                               (run-step new-messages (inc step))))
                           ;; Plain text → done.
                           (= kind :text)  (stop-done)
                           ;; Unknown kind → stop gracefully.
                           :else          (stop-done)))))
                    (p/catch (fn [err] (stop-error err)))))))]
    (run-step messages step)))

(defn run-agent-design
  "Fire an agent-driven generation. Reuses the bar's busy/stage/error/preview
  state atoms (same as `generate-design`) so the UI is byte-identical-when-
  inactive and the preview modal is the same. Routes the bar's 'max' quality.

  Keys (same shape as `generate-design`):
    :prompt   prompt string
    :files    FileInput maps (user attachments; passed to the scout so the
              vision model also sees user reference images, when present)
    :options  {:target :quality :frame-preset :frame-width :frame-height
               :use-memory}"
  [{:keys [prompt files options]}]
  (ptk/reify ::run-agent-design
    ptk/WatchEvent
    (watch [_ state _]
      (let [my-id     (bump-gen-id)
            target    (:target options "new-board")
            is-update (= target "update-selection")
            file-id   (str (:current-file-id state))
            opts      (assoc options :file-id file-id)
            scene     (dg/serialize-scene state)
            sel       (when is-update (or (dg/selection->snippet state) []))
            tools     (ait/tools-list)

            user-text (str (or prompt "")
                           (when (seq scene)
                             (dm/str
                              "\n\n--- LIVE SCENE (structured; use these ids verbatim when calling tools) ---\n"
                              scene))
                           (when (seq sel)
                             (dm/str
                              "\n\n--- CURRENT SELECTION (the region the user wants updated) ---\n"
                              (js/JSON.stringify (clj->js sel)))))

            ;; Non-fatal scout: only when we can capture a viewport; runs on the
            ;; vision (Kimi) model with the PNG + user attachments. Its brief is
            ;; folded into the tool-loop's user message.
            run-scout
            (fn [viewport]
              (let [scout-files (into (vec (or files []))
                                      (when viewport [viewport]))]
                (if (seq scout-files)
                  (let [scout-req (build-agent-step-request
                                   {:messages [(aia/system-message (aia/scout-system-prompt))
                                               (aia/user-message
                                                (dm/str (or prompt "")
                                                         (when (seq scene)
                                                           (dm/str "\n\nSCENE:\n" scene))))]
                                    :tools nil :files scout-files :options opts
                                    :model nil})]
                    ;; invoke-get-config is a promise; resolve it first
                    (-> (invoke-get-config)
                        (p/then
                         (fn [cfg-js]
                           (let [cfg (js->clj cfg-js :keywordize-keys true)
                                 req (build-agent-step-request
                                       {:messages [(aia/system-message (aia/scout-system-prompt))
                                                   (aia/user-message
                                                    (dm/str (or prompt "")
                                                             (when (seq scene)
                                                               (dm/str "\n\nSCENE:\n" scene))))]
                                        :tools nil :files scout-files :options opts
                                        :model (kimi-slug cfg)})]
                             (invoke-agent-step req))))
                        (p/then
                         (fn [resp-js]
                           (let [resp (js->clj resp-js :keywordize-keys true)]
                             (or (:text resp) ""))))
                        (p/catch (fn [_] ""))))
                  (p/resolved ""))))

            begin-loop
            (fn [brief]
              (let [final-user (if (and (seq brief) (not= brief ""))
                                 (dm/str user-text
                                         "\n\n--- VISUAL BRIEF (from the screenshot) ---\n"
                                         brief)
                                 user-text)
                    messages [(aia/system-message (aia/design-system-prompt))
                              (aia/user-message final-user)]]
                (run-agent-loop my-id state target opts tools messages 0)))]

        ;; Mark busy + reset ABORT (detached, non-blocking).
        (-> (invoke-agent-reset) (p/catch (fn [_] nil)))
        ;; Detached pipeline: capture viewport → optional scout → loop. All
        ;; side-effects feed back via st/emit!; the WatchEvent itself just
        ;; returns the busy flag, exactly like `generate-design`.
        (-> (capture-viewport-png)
            (p/then (fn [viewport] (-> (run-scout viewport) (p/then begin-loop))))
            (p/catch (fn [_] (begin-loop ""))))
        (rx/of (set-ai-busy true))))))

;; ── P1.13 — Screenshot / Sketch to UI mode prompts ──────────────────────────────
;;
;; The AI bar exposes an image-mode segmented control (none / screenshot /
;; sketch) near the paperclip. When screenshot or sketch is active and the
;; user generates with an image attached, the bar prepends the matching
;; mode instruction below to the user prompt BEFORE calling
;; `generate-design` / `run-agent-design`. The backend already routes image
;; attachments to the Kimi vision model, so this is purely a prompt +
;; UI entry-point change — no backend edits.

(defn screenshot-mode-prompt
  "Return the system instruction prepended to the user prompt when the AI bar
  is in 'Screenshot to UI' mode. Asks the vision model to treat the attached
  image as a screenshot of an existing UI and reproduce it as a DesignSpec."
  []
  (dm/str
   "You are converting a SCREENSHOT of an existing user interface into a "
   "design spec. The attached image is a screenshot of a real UI screen. "
   "Analyze its layout, spacing, typography hierarchy, colors and components, "
   "and emit a DesignSpec that reproduces it as faithfully as possible — "
   "same structure, same visual style, same content text. Preserve the "
   "relative proportions and the visual hierarchy. Treat the screenshot as "
   "the source of truth.\n\n"))

(defn sketch-mode-prompt
  "Return the system instruction prepended to the user prompt when the AI bar
  is in 'Sketch to UI' mode. Asks the vision model to treat the attached
  image as a hand-drawn sketch / wireframe and produce a polished DesignSpec,
  with a clarification preamble that names the inferred style so the user can
  correct it."
  []
  (dm/str
   "You are converting a HAND-DRAWN SKETCH / wireframe into a polished design "
   "spec. The attached image is a sketch — it may be rough, monochrome, or "
   "ambiguous. Infer the intended layout, components and hierarchy, and emit "
   "a clean, production-ready DesignSpec with sensible styling (modern, clear "
   "spacing, legible typography, a coherent color palette). "
   "Begin your reasoning with one short line noting the style you inferred "
   "(e.g. 'Inferred style: minimal SaaS dashboard'), then emit the spec. "
   "If the sketch is ambiguous, make reasonable assumptions and proceed — "
   "do not ask questions.\n\n"))

;; ── P2.28 — Multi-screen size adaptation (one-shot reflow) ────────────────────
;;
;; `adapt-screen` is a prompt-driven one-shot reflow of the current selection
;; to a target viewport size (mobile / tablet / desktop). It reuses the whole
;; `generate-design` machinery — the selection snippet + bounds are threaded
;; through `build-request` exactly as a region update, and the result is
;; applied via the SAME preview → apply-design-spec path the bar already
;; renders (so Apply / Cancel / Regenerate are identical). No new apply code.
;;
;; The prompt instructs the model to reflow the selection for the target
;; width, preserving content, hierarchy and visual style while adjusting
;; flex direction, padding, gaps and element sizes. The guard requires a
;; selection (emits :ai-error when none) since this is inherently a region
;; update.

(def ^:private adapt-target-widths
  {"mobile"  390
   "tablet"  834
   "desktop" 1440})

(defn adapt-screen-prompt
  "Build the reflow prompt for `target` (\"mobile\" | \"tablet\" | \"desktop\")."
  [target]
  (let [w (get adapt-target-widths target 1440)]
    (dm/str
     "Reflow the current selection for a " target " viewport (~" w "px wide). "
     "Preserve the content text, the visual hierarchy and the component "
     "structure. Adjust the flex direction, padding, gaps and element sizes "
     "so the layout reads well at the target width — stack columns vertically "
     "for narrow widths, use multi-column layouts for wide widths, and resize "
     "typography and touch targets appropriately. Keep the same color scheme "
     "and styling. Return a COMPLETE DesignSpec for the reflowed selection "
     "that fits the " target " width.\n\n")))

(defn adapt-screen
  "WatchEvent. One-shot AI reflow of the current selection to `target`
  (\"mobile\" | \"tablet\" | \"desktop\"). Requires a selection; emits
  :ai-error when none. Otherwise fires `invoke-generate` with the reflow
  prompt and `target \"update-selection\"`, feeding the result back through
  the SAME preview slot the bar already renders (byte-identical apply path)."
  [{:keys [target]}]
  (ptk/reify ::adapt-screen
    ptk/WatchEvent
    (watch [_ state _]
      (let [bounds (dg/selection-bounds state)]
        (if-not bounds
          (rx/of (set-ai-error (tr "workspace.ai.bar.adapt-select")))
          (let [my-id    (swap! gen-id inc)
                target-v "update-selection"
                snippet (or (dg/selection->snippet state) [])
                selection {:bounds bounds :shapes snippet}
                file-id  (str (:current-file-id state))
                prompt   (adapt-screen-prompt target)
                opts     {:target target-v
                          :quality "auto"
                          :frame-preset "auto"
                          :use-memory true
                          :file-id file-id
                          :selection selection}
                request  (build-request {:prompt prompt :files [] :options opts})
                handle
                (fn [result]
                  (when (= my-id @gen-id)
                    (let [res (js->clj result :keywordize-keys true)
                          spec (if (contains? res :specs)
                                 (first (:specs res))
                                 res)]
                      (st/emit! (set-ai-busy false)
                                (set-ai-error nil)
                                (set-ai-preview {:spec spec :target target-v})))))
                handle-err
                (fn [err]
                  (when (= my-id @gen-id)
                    (st/emit! (set-ai-busy false)
                              (set-ai-error (err->str err)))))]
            (-> (invoke-generate request)
                (p/then handle)
                (p/catch handle-err))
            (rx/of (set-ai-busy true))))))))