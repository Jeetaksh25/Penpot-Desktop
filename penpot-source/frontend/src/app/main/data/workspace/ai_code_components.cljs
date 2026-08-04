;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.ai-code-components
  "P1.35 — Workshop: AI-generated React code components that render on
  canvas and inherit site style (ALL_APPS_PARITY).

  Framer-style code-component GENERATION (NOT a learning center). The user
  types a prompt in the AI bar's 'Generate code component' popover; this
  module:

    1. serializes the current scene (`dg/serialize-scene`) + threads the
       project's design-system guidelines (the 'site style') from the
       LlmConfig so the model inherits the canvas's tokens;
    2. invokes the EXISTING Tauri `llm_generate` command (reused verbatim —
       no new Rust) with a system prompt instructing the LLM to emit a
       single self-contained React function component (JSX + inline styles,
       hooks allowed, no external CSS imports — React/react-dom come from
       the bundle's importmap);
    3. extracts the component source from the returned DesignSpec (the
       backend always returns a DesignSpec; like the Figma-#71 AI tools we
       best-effort pull a plain-text answer — here the React source — out of
       the spec's first text/content node, preserving newlines);
    4. wraps the source into a self-contained HTML blob (React + Babel-
       standalone from esm.sh + a prop-reading script) and creates a
       `blob:` URL via `js/URL.createObjectURL`;
    5. registers that blob URL as a code-component bundle via the P0.14 host
       (`cc/register-component-event`) and, when a rect/frame is selected,
       applies it to the selection (`cc/apply-to-shape-event`) — both in ONE
       undo transaction (dwu batching around the host's own per-event
       transactions).

  The bundle reads its props from the iframe URL fragment the host sets:
    #ovion-props=<base64-of-pr-str-props>
  (host `ui.shapes.code-component` encodes via `pr-str` + UTF-8-safe base64).
  The bundle's prop decoder therefore base64-decodes + UTF-8-decodes, then
  tries `JSON.parse` (future-proof) and falls back to a naive Clojure
  pr-str flat-map parse so the host's current pr-str encoding works today.
  See `build-bundle-url`.

  Byte-identical-when-inactive: this module is OPT-IN. Nothing here runs
  until the user opens the 'Generate code component' popover and clicks
  Generate. The AI bar with the popover closed renders exactly as today
  (one new cluster button added — mirrors how prior waves added buttons).
  No new refs / state slots are created: busy/error ride the existing
  :ai-busy / :ai-error slots from `ai-gen.cljs`; live stage text rides the
  bar's existing `ai-progress` subscription (the same `llm_generate` invoke
  emits the starting/routing/generating/done events the bar already maps).

  Session-scoped limitation: blob: URLs do not persist across app reloads,
  so an AI-generated component renders only for the current session. A
  future build-step-backed bundle (esbuild/webpack) would persist the
  bundle as a file; under no-build the blob URL is the available mechanism.

  No-build verified: pure CLJS, no shadow-cljs/clojure/cargo runs."
  (:require
   ["@tauri-apps/api/core" :refer [invoke]]
   [cuerdas.core :as str]
   [app.common.data.macros :as dm]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.ai-gen :as ai]
   [app.main.data.workspace.code-components :as cc]
   [app.main.data.workspace.design-gen :as dg]
   [app.main.data.workspace.undo :as dwu]
   [app.main.data.helpers :as dsh]
   [app.main.store :as st]
   [app.util.i18n :as i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]
   [promesa.core :as p]))

;; ── Prompt crafting ───────────────────────────────────────────────────────────
;;
;; The system prompt asks for a single self-contained React function
;; component. The user prompt grounds the model in the live scene
;; (`dg/serialize-scene`) + the project's design-system guidelines (the
;; 'site style' — read from the LlmConfig via `llm_get_config`, the same
;; key `apply-design-spec` threads as `:design-system-guidelines`). The
;; backend returns a DesignSpec; we ask the model to place the ENTIRE
;; component source as the text of a single frame so `extract-source-from-
;; spec` can pull it reliably (mirrors how the Figma-#71 AI tools coax a
;; plain-text answer out of a DesignSpec result under the no-build
;; constraint — a dedicated `llm_text` command returning a raw string is
;; deferred).

(defn- code-component-system-prompt
  "The system instruction. Pinned to a self-contained function component
  named `Component(props)` — the bundle wraps it with the React import +
  a `createRoot(...).render(<Component {...props}/>)` mount call."
  []
  (dm/str
   "You are an expert React component authoring assistant inside a design "
   "tool. You generate a SINGLE self-contained React function component "
   "that renders the UI the user describes.\n\n"
   "Hard requirements for the component:\n"
   " - It MUST be a function component named exactly `Component` taking a "
   "single `props` argument.\n"
   " - Use React hooks (useState, useEffect, useMemo, etc.) as needed.\n"
   " - Use JSX.\n"
   " - Style with INLINE STYLES only, or a single <style> tag emitted via "
   "a React <style> element. NO external CSS imports, NO CSS files, NO "
   "Tailwind/utility classes, NO CSS modules.\n"
   " - Do NOT import React or react-dom — the host bundle provides them "
   "via an importmap and wraps your function with the import + the mount "
   "call. Just define `function Component(props){ ... }`.\n"
   " - Do NOT import any third-party library. Only the React + react-dom "
   "from the importmap are available.\n"
   " - Accept all dynamic data via `props` (never read window/document "
   "globals for content).\n"
   " - Inherit the project's design tokens passed in the prompt (colors, "
   "font families, spacing, radii) — use them as the inline-style values "
   "so the component matches the canvas's site style.\n"
   " - The component must render something visible and self-contained.\n\n"
   "Output format — return a DesignSpec with EXACTLY ONE frame whose name "
   "is \"code-component\" and whose single text content node contains the "
   "ENTIRE component source wrapped in one fenced ```jsx code block. Put "
   "no commentary outside the fence. The fence content is extracted "
   "verbatim and executed, so it must be valid JSX/JS."))

(defn- code-component-user-prompt
  "Ground the model: the user's request, the live scene (structured), and
  the project's design-system guidelines (the 'site style')."
  [prompt scene guidelines]
  (dm/str
   "User request:\n" (or prompt "") "\n\n"
   (when (seq guidelines)
     (dm/str
      "--- PROJECT DESIGN TOKENS (inherit these as inline-style values) ---\n"
      guidelines "\n\n"))
   (when (seq scene)
     (dm/str
      "--- LIVE CANVAS SCENE (structured; match its visual language) ---\n"
      scene "\n\n"))
   "Emit one React function component `Component(props)` that fulfills the "
   "request and inherits the design tokens above. Return a DesignSpec with "
   "a single frame named \"code-component\" whose text content is the "
   "component source in a ```jsx fenced block. Emit ONLY the spec JSON."))

;; ── Source extraction ─────────────────────────────────────────────────────────
;;
;; `llm_generate` always returns a DesignSpec JS object. We pull the
;; component source out of the first non-empty :text / :content string in
;; the spec tree (depth-first), PRESERVING newlines (unlike
;; `ai/extract-text-from-spec`, which collapses whitespace for one-line
;; names). Then strip a fenced ```jsx / ``` block if present.

(defn- first-source-in
  "Depth-first search for the first non-empty string under :text/:content
  or a node :name in a keywordized spec map. Returns the raw string
  (whitespace preserved) or nil."
  [node]
  (when (map? node)
    (or (when (string? (:text node)) (let [s (str/trim (:text node))]
                                       (when (seq s) s)))
        (when (string? (:content node)) (let [s (str/trim (:content node))]
                                          (when (seq s) s)))
        (some first-source-in (seq (:children node)))
        (some first-source-in (seq (:shapes node)))
        (some first-source-in (seq (:frames node)))
        (some first-source-in (seq (:nodes node))))))

(defn- strip-code-fence
  "If `s` contains a fenced ```jsx / ```js / ``` block, return the inner
  content (trimmed). Otherwise return `s` trimmed. Defensive — never throws."
  [s]
  (let [s (str s)]
    (if-let [start (or (str/index-of s "```jsx")
                       (str/index-of s "```js")
                       (str/index-of s "```"))]
      (let [after  (.substring s (+ start 3))
            ;; skip the language tag line up to the first newline
            nl     (str/index-of after "\n")
            body   (if (and nl (>= nl 0)) (.substring after (inc nl)) after)
            end    (str/index-of body "```")]
        (if end (.substring body 0 end) (str/trim body)))
      (str/trim s))))

(defn extract-source-from-spec
  "Best-effort extraction of the React component source from a DesignSpec
  result (the JS object returned by `llm_generate`). Returns a trimmed
  string (newlines preserved, code fence stripped) or nil."
  [result-js]
  (let [spec (js->clj result-js :keywordize-keys true)
        raw  (or (when (string? (:text spec)) (str/trim (:text spec)))
                 (when (string? (:name spec)) (str/trim (:name spec)))
                 (first-source-in spec))]
    (some-> raw strip-code-fence not-empty)))

;; ── Bundle building ───────────────────────────────────────────────────────────
;;
;; The registered `bundle-url` is a `blob:` URL pointing at a self-contained
;; HTML document. The document loads React 18 + react-dom/client from
;; esm.sh via an importmap, loads Babel-standalone (which compiles the
;; `text/babel` script containing the AI component source + the mount
;; call), and reads props from `location.hash` (`#ovion-props=...`).
;;
;; Prop encoding contract with the P0.14 host:
;;   host `ui.shapes.code-component` sets the iframe src to
;;     <bundle-url>#ovion-props=<base64-of-UTF8-safe(pr-str(props))>
;;   so the blob decodes: atob -> UTF-8 decode -> parse. The blob tries
;;   `JSON.parse` first (future-proof if the host switches to JSON) and
;;   falls back to a naive Clojure pr-str flat-map parse for the host's
;;   current pr-str encoding. This means AI components work today with no
;;   host change; if the P0.14 host later switches to
;;   `js/JSON.stringify (clj->js props)` the blob keeps working.
;;
;; Coordination note for the P0.14 agent: the bundle expects props in the
;; fragment as base64. The current host encoding (pr-str + UTF-8-safe
;; base64) is handled by the fallback parser. No host change is REQUIRED
;; for P1.35 to function; switching the host to JSON would let the bundle
;; drop the fallback parser (a small simplification) and would handle
;; nested props more robustly. Either encoding is acceptable.

(defn- escape-script-close
  "Replace `</script>` in the source so it cannot terminate the wrapping
  `<script type=\"text/babel\">` element. Defensive — the AI source is
  unlikely to contain it but a generated string literal could."
  [s]
  (str/replace (str s) #"</script>" "<\\/script>"))

(defn- bundle-html
  "Build the self-contained HTML document string for `source` (the AI
  component function body, expected to define `function Component(props)`)."
  [source]
  (dm/str
   "<!doctype html><html><head><meta charset=\"utf-8\">"
   "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
   ;; importmap provides `react` + `react-dom/client` to the module script.
   "<script type=\"importmap\">"
   "{\"imports\":{\"react\":\"https://esm.sh/react@18\","
   "\"react-dom/client\":\"https://esm.sh/react-dom@18/client\"}}"
   "</script>"
   ;; Base reset so the component fills the iframe.
   "<style>html,body,#root{margin:0;padding:0;width:100%;height:100%;}"
   "body{font-family:Helvetica,Arial,sans-serif;}</style>"
   "</head><body>"
   "<div id=\"root\"></div>"
   ;; Prop decoder: runs before the component. Reads `#ovion-props=...`,
   ;; base64-decodes (UTF-8 safe), tries JSON.parse then a naive pr-str
   ;; flat-map parse, and exposes the result on `window.__OVION_PROPS__`.
   "<script>"
   "(function(){"
   "var h=location.hash||'';"
   "var i=h.indexOf('ovion-props=');"
   "var props={};"
   "if(i>=0){"
   "var b64=h.slice(i+12);"
   "try{"
   "var dec=decodeURIComponent(escape(atob(b64)));"
   "try{props=JSON.parse(dec);}catch(e1){"
   "try{"
   "var s=dec.trim();"
   "if(s.charAt(0)==='{'&&s.charAt(s.length-1)==='}'){"
   "var inner=s.slice(1,-1).trim();"
   "var re=/:(\\w[\\w-]*)\\s+(\"(?:[^\"\\\\]|\\\\.)*\"|-?\\d+(?:\\.\\d+)?|true|false|nil|[^\\s,}]+)/g;"
   "var m,obj={};"
   "while((m=re.exec(inner))!==null){"
   "var k=m[1],v=m[2];"
   "if(v==='true'){obj[k]=true;}"
   "else if(v==='false'){obj[k]=false;}"
   "else if(v==='nil'){obj[k]=null;}"
   "else if(v.charAt(0)==='\"'){try{obj[k]=JSON.parse(v);}catch(e2){obj[k]=v;}}"
   "else if(/^-?\\d+(?:\\.\\d+)?$/.test(v)){obj[k]=Number(v);}"
   "else{obj[k]=v;}"
   "}"
   "props=obj;"
   "}"
   "}catch(e3){props={};}"
   "}"
   "}catch(e4){props={};}"
   "}"
   "window.__OVION_PROPS__=props;"
   "})();"
   "</script>"
   ;; The component + mount call. Babel-standalone compiles this in place.
   "<script type=\"text/babel\" data-type=\"module\" data-presets=\"react\">"
   "import React from 'react';"
   "import { createRoot } from 'react-dom/client';"
   (escape-script-close source)
   "createRoot(document.getElementById('root')).render("
   "<Component {...window.__OVION_PROPS__}/>);"
   "</script>"
   ;; Babel-standalone last: on DOMContentLoaded it scans for and compiles
   ;; the text/babel scripts above. Loaded from esm.sh (CDN).
   "<script src=\"https://esm.sh/standalone/babel.min.js\"></script>"
   "</body></html>"))

(defn build-bundle-url
  "Wrap `source` (the AI React component source) into a self-contained
  HTML blob and return a `blob:` URL string. Returns nil if the Blob/
  createObjectURL API is unavailable."
  [source]
  (try
    (let [html  (bundle-html source)
          blob  (js/Blob. #js [html] #js {:type "text/html;charset=utf-8"})]
      (js/URL.createObjectURL blob))
    (catch :default _ nil)))

;; ── Naming / id minting ───────────────────────────────────────────────────────

(defn- short-prompt-label
  "Collapse `prompt` to a short label (one line, <= 40 chars) for the
  registry entry name."
  [prompt]
  (let [s (-> (str prompt)
              (str/replace #"\s+" " ")
              str/trim)]
    (if (> (count s) 40)
      (dm/str (.substring s 0 37) "...")
      s)))

(defn- mint-component-id
  "Mint a unique registry id keyword (`:ai-<uuid>`) for an AI component."
  []
  (keyword (str "ai-" (str (uuid/next)))))

;; ── Selection target ──────────────────────────────────────────────────────────

(defn- code-component-target-shape-id
  "Return the id of the first selected rect or frame (the carrier shapes
  the P0.14 host renders code-components on), or nil when nothing
  applicable is selected."
  [state]
  (let [objects  (dsh/lookup-page-objects state)
        selected (dsh/lookup-selected state)]
    (some (fn [id]
            (let [s (get objects id)]
              (when (contains? #{:rect :frame} (:type s)) id)))
          selected)))

;; ── The full flow event ───────────────────────────────────────────────────────
;;
;; `run-generate-code-component` is the single user action. It:
;;   0. bumps the shared gen-id guard (so a cancel / newer generation
;;      drops a late-arriving result) + marks busy;
;;   1. reads the LlmConfig's design-system guidelines (the site style) via
;;      `llm_get_config` (non-fatal — empty guidelines just skip the
;;      token-inheritance channel);
;;   2. serializes the scene (`dg/serialize-scene`);
;;   3. invokes `llm_generate` with the system + user prompt (reusing
;;      `ai/build-request` + `ai/invoke-generate` — byte-identical wire
;;      format to design generation);
;;   4. on success: extracts the source, builds the bundle URL, mints an
;;      id, and emits ONE undo transaction containing
;;      `cc/register-component-event` + (when a rect/frame is selected)
;;      `cc/apply-to-shape-event`;
;;   5. on error: sets :ai-error + clears busy.
;;
;; Live stage text rides the bar's existing `ai-progress` subscription
;; (`llm_generate` emits starting/routing/generating/done) — no new UI.

(defn- err->str [err]
  (cond
    (string? err)          err
    (some-> err .-message) (.-message err)
    :else (tr "workspace.ai.bar.error-generic")))

(defn run-generate-code-component
  "WatchEvent. Generate a React code component from `prompt`, register it
  as a code component, and apply it to the selected rect/frame (if any).
  One undo transaction for the register + apply. Opt-in: never fires from
  the default generate path — byte-identical-when-inactive.

  Keys:
    :prompt   the user's component description"
  [{:keys [prompt]}]
  (ptk/reify ::run-generate-code-component
    ptk/WatchEvent
    (watch [_ state _]
      (let [my-id    (ai/bump-gen-id)
            scene    (or (dg/serialize-scene state) "")
            shape-id (code-component-target-shape-id state)
            sys-p    (code-component-system-prompt)

            build-and-invoke
            (fn [guidelines]
              (let [user-p (code-component-user-prompt prompt scene guidelines)
                    full-p (dm/str sys-p "\n\n" user-p)
                    opts   {:target       "new-board"
                            :quality      "auto"
                            :frame-preset "auto"
                            :use-memory   false}
                    request (ai/build-request
                             {:prompt  full-p
                              :files   []
                              :options opts})]
                (ai/invoke-generate request)))

            handle
            (fn [result]
              (when (= my-id (ai/gen-id-current))
                (let [source (extract-source-from-spec result)]
                  (cond
                    (or (nil? source) (str/empty? source))
                    (st/emit! (ai/set-ai-busy false)
                              (ai/set-ai-error
                               (tr "workspace.ai.bar.codegen-empty")))

                    :else
                    (let [bundle-url (build-bundle-url source)]
                      (if (str/empty? bundle-url)
                        (st/emit! (ai/set-ai-busy false)
                                  (ai/set-ai-error
                                   (tr "workspace.ai.bar.codegen-bundle-fail")))
                        (let [reg-id   (mint-component-id)
                              reg-name (dm/str "AI: " (short-prompt-label prompt))
                              undo-id  (uuid/next)
                              events   (cond-> [(dwu/start-undo-transaction undo-id)
                                                (cc/register-component-event
                                                 reg-id reg-name bundle-url {})]
                                         shape-id
                                         (conj (cc/apply-to-shape-event
                                                shape-id reg-id {}))
                                         true
                                         (conj (dwu/commit-undo-transaction undo-id)
                                               (ai/set-ai-busy false)
                                               (ai/set-ai-error nil)))]
                          (apply st/emit! events))))))))

            handle-err
            (fn [err]
              (when (= my-id (ai/gen-id-current))
                (st/emit! (ai/set-ai-busy false)
                          (ai/set-ai-error (err->str err)))))]

        ;; Detached promise chain: config -> invoke -> handle. The bar's
        ;; existing ai-progress subscription surfaces live stage text.
        (-> (ai/invoke-get-config)
            (p/then
             (fn [cfg-js]
               (let [cfg (js->clj cfg-js :keywordize-keys true)]
                 (or (:design_system_guidelines cfg) ""))))
            (p/catch (fn [_] ""))
            (p/then build-and-invoke)
            (p/then handle)
            (p/catch handle-err))

        (rx/of (ai/set-ai-busy true)
               (ai/set-ai-error nil))))))