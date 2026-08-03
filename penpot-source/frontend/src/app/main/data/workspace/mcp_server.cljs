;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.mcp-server
  "Ovion Desktop MCP round-trip — frontend half.

  The Rust MCP server (`src-tauri/src/llm.rs`) exposes the Ovion canvas to
  external MCP clients over a local TCP socket. For every `tools/call` it
  receives, Rust emits a Tauri event `mcp-tool-call` with a JS payload
  `{id <u64>, name <string>, arguments <object>}` and waits on a oneshot
  channel for the result. This namespace:

    1. listens for `mcp-tool-call` events for the lifetime of the workspace
       session (the listener is idempotent — `start-listener` is a no-op when
       already armed, so re-mounts of the AI Settings modal are safe);
    2. dispatches the tool `name` against the LIVE canvas/potok state (read
       fresh from `st/state` per call so mutations between calls are visible);
    3. ships the computed result map back to Rust via the
       `llm_mcp_tool_result` Tauri command (`#js {:requestId id :result
       (clj->js result)}`), which lets Rust resolve its oneshot and reply to
       the MCP client.

  Tool results are plain CLJS maps (`{:ok true ...}` / `{:ok false :error
  ...}`) — the same shape `ai-tools/execute-tool` returns, so the agent loop
  and the MCP server see identical value envelopes. Every tool body is
  wrapped so a crash never escapes the listener: `handle-call`'s `p/catch`
  converts any thrown error into `{:ok false :error ...}` and ships it back,
  and the mutation-touching bodies (`design_to_code`, `code_to_design`) add a
  belt-and-suspenders try/catch of their own.

  This is a DATA namespace — no UI, no CSS, no Rumext. It is loaded at
  workspace boot via a bare require in `app.main.ui.workspace` and started
  from the AI Settings mount effect."
  (:require
   ["@tauri-apps/api/core" :refer [invoke]]
   ["@tauri-apps/api/event" :as tevent]
   [app.common.files.helpers :as cfh]
   [app.common.geom.shapes :as gsh]
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.ai-gen :as ai]
   [app.main.data.workspace.ai-tools :as ait]
   [app.main.data.workspace.design-gen :as dg]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.util.code-gen :as cg]
   [promesa.core :as p]))

;; ── Listener state ───────────────────────────────────────────────────────────
;;
;; `listener-state` holds either nil (not listening) or the promesa promise
;; of the unlisten function returned by `tevent/listen`. The promise resolves
;; some milliseconds after `start-listener`; `stop-listener` awaits it (via
;; `p/then`) to actually unlisten. Idempotent: a second `start-listener` while
;; the atom is non-nil is a no-op.

(def ^:private listener-state (atom nil))

(defn listening?
  "True when the `mcp-tool-call` listener is currently armed."
  []
  (some? @listener-state))

(defn start-listener
  "Arm the `mcp-tool-call` Tauri event listener. Idempotent — a no-op when
  already listening. Stores the listen promise in `listener-state` so
  `stop-listener` can resolve it to the unlisten fn. Returns nil."
  []
  (when (nil? @listener-state)
    (let [lp (tevent/listen "mcp-tool-call" (fn [e] (handle-call (.-payload e))))]
      (reset! listener-state lp)))
  nil)

(defn stop-listener
  "Best-effort teardown of the `mcp-tool-call` listener. If the listen
  promise has resolved to an unlisten fn, call it; then reset the state
  atom so `start-listener` can arm a fresh listener later. Returns nil."
  []
  (let [lp @listener-state]
    (reset! listener-state nil)
    (when (some? lp)
      (p/then lp (fn [unlisten] (when (fn? unlisten) (unlisten))))))
  nil)

;; ── Event dispatch ───────────────────────────────────────────────────────────

(defn- handle-call
  "Handle one `mcp-tool-call` event payload. `payload` is a JS object
  `{id name arguments}`. Runs the tool (which may return a value or a
  promise) and ships the result back to Rust via `ship-result`, mapping any
  thrown/rejected error into `{:ok false :error ...}` so Rust's oneshot
  always resolves."
  [payload]
  (let [id   (.-id payload)
        name (str (.-name payload))
        args (js->clj (or (.-arguments payload) #js {}) :keywordize-keys true)]
    (-> (p/promise (run-tool name args))
        (p/then (fn [r] (ship-result id r)))
        (p/catch (fn [e] (ship-result id {:ok false
                                           :error (str (or (some-> e .-message) e))}))))))

(defn- ship-result
  "Fire-and-forget: post the tool `result` map back to the Rust MCP server
  so it can resolve the oneshot waiting on this `id`. `clj->js` turns the
  CLJS map into the JS object Rust deserializes into `serde_json::Value`.
  A failed invoke just logs a warning — there is no caller to reject into."
  [id result]
  (-> (invoke "llm_mcp_tool_result" #js {:requestId id :result (clj->js result)})
      (p/catch (fn [e] (js/console.warn "[mcp-server] llm_mcp_tool_result failed" id e))))
  nil)

;; ── Tool dispatch ────────────────────────────────────────────────────────────

(defn- run-tool
  "Dispatch the MCP tool `name` against the live workspace state. Returns
  either a plain result map or a promesa promise of one (for `get_screenshot`).
  Unknown tools yield `{:ok false :error ...}`. State is read fresh per call
  via `@st/state` so the tool observes the latest canvas."
  [name args]
  (let [state @st/state]
    (case name
      "get_document_info" (tool-document-info state)
      "get_layer_tree"    {:ok true :scene (dg/serialize-scene state)}
      "get_selection"     {:ok true :selection (or (dg/selection->snippet state) [])}
      "get_screenshot"    (tool-screenshot)
      "get_tokens"        (tool-tokens state)
      "get_components"    {:ok true :components (or (-> (dsh/lookup-file-data state) :components) {})}
      "get_libraries"     (tool-libraries)
      "run_code"          (tool-run-code args state)
      "apply_action"      (tool-run-code args state)
      "design_to_code"    (tool-design->code args state)
      "code_to_design"    (tool-code->design args state)
      {:ok false :error (str "unknown tool: " name)})))

;; ── Tool bodies ──────────────────────────────────────────────────────────────

(defn- tool-document-info
  "Summary of the current file + page: ids, names, and the shape count of the
  current page's object map."
  [state]
  (try
    (let [file    (dsh/lookup-file state)
          page    (dsh/lookup-page state)
          objects (dsh/lookup-page-objects state)]
      {:ok          true
       :file_id     (str (:id file))
       :file_name   (:name file "")
       :page_id     (str (:id page))
       :page_name   (:name page "")
       :shape_count (count objects)})
    (catch :default e
      {:ok false :error (str e)})))

(defn- tool-screenshot
  "Capture the workspace `#render` canvas as a PNG FileInput via the existing
  `ai/capture-viewport-png` helper. Returns a PROMISE of a result map —
  `run-tool` returns it directly and `handle-call`'s `p/promise` flattens it."
  []
  (p/then (ai/capture-viewport-png)
          (fn [fi]
            (if fi
              {:ok true :screenshot fi}
              {:ok false :error "no viewport canvas"}))))

(defn- tool-tokens
  "Return the file's design-token library as a plain map of token-name to
  token via `ctob/get-all-tokens-map` (the same fn `refs/workspace-all-tokens-map`
  derives from). Falls back to `{}` when the file has no tokens-lib."
  [state]
  (try
    (let [lib (-> (dsh/lookup-file-data state) :tokens-lib)]
      {:ok true :tokens (or (some-> lib ctob/get-all-tokens-map) {})})
    (catch :default e
      {:ok false :error (str e)})))

(defn- tool-libraries
  "Lean summaries of the libraries currently loaded in the workspace (the
  local file + shared libraries), keyed by file id. Keeps only the small
  identity fields so the payload stays tiny."
  []
  (try
    (let [libs @refs/libraries]
      {:ok true
       :libraries (into {}
                        (for [[id f] libs]
                          [id (select-keys f [:id :name :is-shared :project-id])]))})
    (catch :default e
      {:ok false :error (str e)})))

(defn- tool-run-code
  "Forward an `ai-tools` tool call (canvas mutation or read) against the live
  state. `args` is `{:name <tool-name> :arguments <arg-map>}`. Returns the
  `ait/execute-tool` result map directly (already `{:ok ...}`). Aliased by
  the MCP tool `apply_action`."
  [args state]
  (try
    (let [tool-name (str (:name args))
          tool-args (or (:arguments args) {})]
      (ait/execute-tool tool-name tool-args state))
    (catch :default e
      {:ok false :error (str e)})))

(defn- tool-design->code
  "Generate UI-framework source for the current selection (or the whole page
  when `:scope` is \"page\") using the SAME shape-building path as the
  Inspect \"Code\" panel (`inspect/code.cljs`):

    selection → (resolve-shapes objects selected) → translate-to-frame
    page      → all top-level frames

  `:framework` defaults to \"react\" and must be one of `cg/framework-types`.
  Returns `{:ok true :framework :scope :code}` or `{:ok false :error ...}`."
  [args state]
  (try
    (let [framework (str (or (:framework args) "react"))
          scope     (str (or (:scope args) "selection"))
          objects   (dsh/lookup-page-objects state)]
      (cond
        (not (contains? cg/framework-types framework))
        {:ok false :error "unsupported framework"}

        (empty? objects)
        {:ok false :error "no shapes on the current page"}

        :else
        (let [shapes (if (= scope "page")
                       (let [page    (dsh/lookup-page state)
                             root-ids (:shapes page)
                             root-shapes (into [] (keep #(get objects %)) root-ids)]
                         (filterv cfh/frame-shape? root-shapes))
                       (let [selected (dsh/lookup-selected state)
                             root-shapes (into [] (keep #(get objects %)) selected)
                             frame (cfh/get-frame objects (first root-shapes))]
                         (mapv #(gsh/translate-to-frame % frame) root-shapes)))]
          (if (empty? shapes)
            {:ok false :error (if (= scope "page")
                                "no top-level frames on the current page"
                                "nothing is selected")}
            {:ok true :framework framework :scope scope
             :code (cg/generate-framework-code objects framework shapes)}))))
    (catch :default e
      {:ok false :error (str e)})))

(defn- tool-code->design
  "Fire-and-forget: kick off an AI design generation that asks the model to
  recreate the supplied `:code` as a high-fidelity Ovion design. The prompt
  embeds the framework label and the raw code in a fenced markdown block.
  Shapes appear asynchronously via the existing AI bar preview/apply flow —
  the user applies the preview to commit shapes to the canvas. Returns
  `{:ok true :message ...}` immediately; no synchronous shapes are produced."
  [args state]
  (try
    (let [code      (str (:code args))
          framework (str (or (:framework args) "html"))
          target    (str (or (:target args) "new-board"))]
      (if (== 0 (count code))
        {:ok false :error "code required"}
        (let [prompt (str "Recreate the following " framework " code as a "
                          "high-fidelity, fully editable Ovion design. Preserve "
                          "layout, spacing, typography, color, hierarchy and "
                          "interactive structure. Output a single artboard.\n\n"
                          "```" framework "\n"
                          code
                          "\n```")]
          (st/emit! (ai/generate-design {:prompt prompt
                                         :files  []
                                         :options {:target     target
                                                   :quality    "auto"
                                                   :use-memory true}}))
          {:ok true
           :message "design generation started — preview will appear in the AI bar; apply to commit shapes"})))
    (catch :default e
      {:ok false :error (str e)})))