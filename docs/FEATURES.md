# Penpot Desktop — Product Roadmap & Feature Checklist

A working checklist for the features requested for Penpot Desktop. Every item is grounded in the actual codebase (`penpot-source/`, `src-tauri/`) and includes the real files you will touch, the prerequisites you must land first, and the open product decisions that block implementation.

## Guiding invariants (read this before starting any feature)

1. **Offline-first desktop app.** The installer bundles Postgres, Redis, the JVM backend, the SPA, and now the builtin templates. Network egress is opt-in, not default.
2. **Same-origin SPA on `http://localhost:1420`.** The Rust proxy in `src-tauri/src/proxy.rs` forwards `/api/`, `/internal/`, `/ws/`, `/assets/` to the JVM backend on `:3449`. The frontend cannot call external APIs directly.
3. **No Tauri commands are registered today.** `src-tauri/src/lib.rs` declares only `mod proxy;` and the builder has no `.invoke_handler`. The FIRST `#[tauri::command]` + `invoke_handler` registration is shared infrastructure for multiple features.
4. **Google Fonts and render-wasm are disabled in the desktop build.** `src-tauri/src/lib.rs:443-446` sets `disable-google-fonts-provider` and several `disable-*-render-wasm*` flags. Re-enabling them has real dependencies (offline fonts, WASM build pipeline).
5. **Bare-directory resource bundling.** `src-tauri/tauri.conf.json bundle.resources` flattens `**/*` globs to basename. Use bare directory paths to preserve subdirectories (templates, fonts, MCP dist, JRE/lib, etc.).
6. **MCP/AI is external today.** `penpot-source/mcp/` is a standalone TypeScript MCP server + plugin. The LLM is the external MCP client; the server is only a tool host. Any in-app AI requires packaging that server or adding a Rust-side LLM client.

---

## Phase 0 — Foundations (build these once, then reuse)

These are the shared prerequisites that multiple features depend on. Do them first, before touching any feature-specific code.

### F1 — Tauri command foundation
- [ ] Add `mod commands;` (or one module per feature) to `src-tauri/src/lib.rs`.
- [ ] Register the first `.invoke_handler(tauri::generate_handler![commands::ping])` on the builder.
- [ ] Add a trivial `ping` command and confirm the frontend can invoke it end-to-end.
- [ ] Decide naming convention: one module per feature (`code_export.rs`, `designgen.rs`, `relay.rs`) vs. a single `commands.rs`.

**Needed by:** code export, MCP server, AI generation, code-to-design, relay collaboration, Figma parity in-app AI.

### F2 — Cargo dependency bump (coordinated)
Add these to `src-tauri/Cargo.toml` in ONE commit, pin versions, and update the build cache key.
- [ ] `reqwest` — outbound HTTP to LLM APIs (AI generation, code-to-design, optional Figma AI).
- [ ] `zip` — writing code-export and generated `.penpot` ZIPs.
- [ ] `tokio-tungstenite` — only if you build the collaboration relay client/server in Rust.
- [ ] `dirs` (optional) — app-data config path instead of hand-rolled `%APPDATA%` logic.

**Needed by:** code export, AI generation, code-to-design, relay collaboration.

### F3 — DesignSpec schema + `apply-design-spec`
- [ ] Create `penpot-source/common/src/app/common/types/design_spec.cljc`: a small Malli schema for generated designs (`:frames` → `:shapes` with a constrained subset of `shape.cljc` attrs).
- [ ] Add `spec->shapes` that emits valid Penpot shape maps via `cts/setup-shape`.
- [ ] Create `penpot-source/frontend/src/app/main/data/workspace/design_gen.cljs` with `apply-design-spec`, mirroring `app.plugins.api/create-shape` (`pcb/empty-changes`, `pcb/add-object`, `dch/commit-changes`, undo transaction).
- [ ] Add a preview renderer: pass generated shapes through `app.main.render` for a modal preview before commit.

**Needed by:** AI design generation, code-to-design, optional Figma parity AI assistant.

### F4 — LLM provider abstraction in Rust
- [ ] Create `src-tauri/src/llm.rs` (or `designgen.rs`) with a provider enum: `openai`, `anthropic`, `ollama`.
- [ ] Read config from a JSON file in the app data dir; never expose the API key to the frontend.
- [ ] Implement `generate(prompt, system, provider) -> DesignSpec` with JSON response parsing + timeout.
- [ ] Add Tauri commands `llm_generate`, `llm_get_config`, `llm_set_config` registered via F1.

**Needed by:** AI design generation, code-to-design, Figma parity in-app AI.

### F5 — MCP server sidecar + MCP plugin auto-load
- [ ] Pre-build `penpot-source/mcp/packages/server/dist/index.js` and `penpot-source/mcp/packages/plugin/dist/`.
- [ ] Add bare-directory entries in `src-tauri/tauri.conf.json bundle.resources`.
- [ ] Add `start_mcp_server(root)` in `src-tauri/src/lib.rs` using `std::process::Command` + `silent()` + `wait_for_port` on `:4401` and `:4402`; add `BackendState.mcp` and kill it in `shutdown_services`.
- [ ] In the frontend, auto-load the bundled plugin via `app.main.data.plugins/open-plugin!` and post `start-server` to the plugin iframe (`plugin/src/main.ts:306`) so it connects to `ws://localhost:4402` without a user click.
- [ ] Add a one-time consent UX for the bundled first-party plugin (it has `content:write` permission).
- [ ] Decide bundling strategy: ship a bundled `node.exe` (like the bundled JRE) OR compile the MCP server to a single executable via `pkg`/`@yao-pkg/pkg`/`bun --compile`.

**Needed by:** AI Agent MCP server, code-to-design (shared execution path), AI generation Phase 5.

### F6 — Packaged binfile-import bridge (no nREPL)
- [ ] Generalize the import path used by `penpot-source/mcp/packages/server/src/tools/ImportPenpotFileTool.ts` so it works outside dev mode.
- [ ] Add a frontend helper that takes a local `.penpot` file path (from Tauri dialog or resources) and emits `app.main.worker/ask-many!` with `{:cmd :import-files :type :binfile-v3}`.
- [ ] Wire it to `app.worker/import.cljs` without requiring a shadow-cljs nREPL.

**Needed by:** AI design generation Phase 5, code-to-design Phase 5, Figma parity component-properties round-trip.

---

## Feature 1 — Updated Google Fonts library

Refresh, broaden, and make the Google Fonts library work offline in the desktop app.

### Phase 1 — Refresh the catalog list (online-only)
- [ ] Confirm `disable-google-fonts-provider` is in `src-tauri/src/lib.rs:443`; decide whether to remove it at the end of Phase 2 or gate it behind offline binary support.
- [ ] Fetch a fresh Google Fonts webfontList snapshot from `https://www.googleapis.com/webfonts/v1/webfonts?key=...`.
- [ ] Save it as `penpot-source/frontend/resources/fonts/gfonts.<YYYY.MM.DD>.json` **and** `penpot-source/frontend/resources/public/fonts/gfonts.<YYYY.MM.DD>.json` (the public copy ships).
- [ ] Update the filename in `penpot-source/frontend/src/app/main/fonts.cljs:29`.
- [ ] Recompile ClojureScript so the `preload-gfonts` macro embeds the new list.
- [ ] Verify the font picker (`penpot-source/frontend/src/app/main/ui/workspace/sidebar/options/menus/typography.cljs`) shows the refreshed families.
- [ ] Regression-test existing files: font-id→uuid mapping in `penpot-source/frontend/src/app/render_wasm/api/fonts.cljs` falls back to Source Sans Pro when an ID disappears; keep the old JSON to diff removals/renames.

### Phase 2 — Offline font binaries
- [ ] Curate a subset of families (all 1900+ families is too large). Options:
  - bundle a popularity-based subset;
  - bundle every family as a single variable woff2 (still large but smaller than static instances);
  - cache-on-first-fetch via proxy or a local disk cache.
- [ ] Download woff2 files (static or variable) under `penpot-source/frontend/resources/public/fonts/gfonts/<slug>/<variant>.woff2`.
- [ ] Add the bare `public/fonts/gfonts/` directory to `src-tauri/tauri.conf.json bundle.resources`.
- [ ] Intercept `/internal/gfonts/font/<path>` in `src-tauri/src/proxy.rs` and serve the bundled woff2 with correct `Content-Type` and long cache headers; fall through to the JVM backend only for missing files.
- [ ] Verify the font picker works and glyphs render with no network connection.
- [ ] Add OFL/Apache license files alongside bundled fonts.

### Phase 3 — Variable fonts
- [ ] Switch to the Google Fonts CSS2 / webfonts v2 list to get `axes`.
- [ ] Extend `penpot-source/frontend/src/app/main/fonts.clj` `parse-gfont-variant` to emit `:variable` variants carrying axes.
- [ ] Update `get-variant`, `find-variant`, `find-closest-variant` in `penpot-source/frontend/src/app/main/fonts.cljs` for variable axes.
- [ ] Add a weight/axis slider in `typography.cljs` when the selected font is variable.
- [ ] Bundle variable woff2 files covering full axis ranges from the CSS2 endpoint.

### Open decisions
- [ ] Scope: catalog refresh only, or full offline + variable fonts?
- [ ] Curated subset or cache-on-first-fetch?
- [ ] How to keep the two `gfonts.*.json` copies from drifting?

---

## Feature 2 — Code export: React, Next.js, React Native, Android XML

One-click export of a Penpot frame/page/file to runnable code. The codebase already has a complete HTML+CSS code generator (`app.util.code-gen`); this feature adds new formatters on top of it.

### Phase 0 — Architecture decision
- [ ] **Do NOT revive the dead Rust modules** `src-tauri/src/codegen.rs`, `codegen_react.rs`, `codegen_winui.rs`, `db.rs`. They reference undefined structs, have no autolayout mapping, and are not compiled today. Either delete them or move them to `src-tauri/src/_archived/`.
- [ ] Build all emit logic in ClojureScript under `penpot-source/frontend/src/app/util/code_gen/`; use the Rust shell only for file I/O / ZIP writing.

### Phase 1 — Shared shape-walk refactor
- [ ] Extract the recursion in `penpot-source/frontend/src/app/util/code_gen/markup_html.cljs` into a multimethod in `penpot-source/frontend/src/app/util/code_gen/common.cljs` keyed by target (`:html`, `:react`, `:react-native`, `:android-xml`) so each formatter only implements leaf emission.
- [ ] Keep `markup_html.cljs` as the first consumer.

### Phase 2 — React / TSX export
- [ ] Create `code_gen/markup_react.cljs`: JSX walker (frame→`<div>`, text→`<div>`, image→`<img>`, SVG shapes→inline SVG or wrapper).
- [ ] Create `code_gen/style_react.cljs`: emit `style={{…}}` CSSProperties or a `styles.ts` Record keyed by selector.
- [ ] Extend `code_gen.cljs` dispatch: add `"react"` markup and `"react-style"` style cases.
- [ ] Add a "React" radio to `penpot-source/frontend/src/app/main/ui/inspect/code.cljs`.
- [ ] Create `ui/exports/code.cljs` export modal and `app.main.data.exports.code` event namespace, modeled on `ui/exports/assets.cljs`.
- [ ] Add Tauri command `write_code_zip` in a new `src-tauri/src/code_export.rs` (uses `zip` crate) and register via F1.
- [ ] Rasterize non-HTML shapes (paths, booleans, SVG raw, masks) to PNG via the existing asset exporter (`app.main.data.exports.assets` → `:export-shapes :png`) and reference them in emitted code.

### Phase 3 — Next.js
- [ ] Create `code_gen/nextjs.cljs` that wraps Phase 2 output in app-router scaffolding (`app/page.tsx`, `app/layout.tsx`, `globals.css`, `next.config.js`, `package.json`).
- [ ] Add "Next.js" target to the export modal.

### Phase 4 — React Native
- [ ] Create `code_gen/markup_react_native.cljs`: `<View>`/`<Text>`/`<Image>` tree.
- [ ] Create `code_gen/style_rn.cljs`: `StyleSheet.create({…})` with RN flexbox defaults (column direction, flex fill).
- [ ] Grid layouts fallback to absolute positioning using existing `get-shape-position`/`get-shape-size`.
- [ ] Add dependency notes in generated `package.json` (`react-native-svg` for scalable SVG shapes).
- [ ] Add "React Native" target.

### Phase 5 — Android XML
- [ ] Create `code_gen/markup_android.cljs`: `LinearLayout`/`ConstraintLayout` mapping from flex + absolute positioning.
- [ ] Create `code_gen/style_android.cljs`: generate `res/values/colors.xml`, `strings.xml`, `dimens.xml`, `res/drawable/<name>.xml` for rounded backgrounds.
- [ ] Add "Android XML" target; ZIP output uses `res/layout/*.xml` structure.

### Phase 6 — Polish
- [ ] Detect Penpot component instances and hoist them into reusable React components.
- [ ] Add golden-string tests under `test/frontend_tests/` modeled on `code_gen_style_test.cljs`.
- [ ] Handle font face emission for web (`@font-face`) and RN/Android (font assets).

### Open decisions
- [ ] Inline styles vs `styles.ts` vs CSS Modules?
- [ ] App router or pages router for Next.js output?
- [ ] Rasterize complex shapes to PNG or generate `VectorDrawable` / `react-native-svg`?

---

## Feature 3 — AI Agent MCP server

Package and host the existing upstream MCP server (`penpot-source/mcp/`) inside the desktop app so external AI agents (Claude Code, Claude Desktop) can read and edit designs via the Penpot Plugin API.

### Phase 1 — Verify upstream build
- [ ] From `penpot-source/mcp`, run `pnpm install && pnpm run build` and confirm:
  - `packages/server/dist/index.js` starts and listens on `:4401` (HTTP/SSE) and `:4402` (WebSocket).
  - `packages/plugin/dist/` builds and can be served as a static Penpot plugin.
- [ ] Decide Node bundling strategy (see Foundation F5).

### Phase 2 — Spawn the MCP server from the Tauri shell
- [ ] Add `start_mcp_server(root)` in `src-tauri/src/lib.rs` mirroring `start_redis`/`boot_backend`: `Command::new(node_or_exe).arg("mcp/dist/index.js")`, `silent()`, `current_dir(root)`, env vars `PENPOT_MCP_SERVER_HOST=127.0.0.1`, `PENPOT_MCP_SERVER_PORT=4401`, `PENPOT_MCP_WEBSOCKET_PORT=4402`, `PENPOT_MCP_REMOTE_MODE=false`.
- [ ] Add `BackendState.mcp: Mutex<Option<Child>>` and kill it in `shutdown_services`.
- [ ] Serve the plugin static dist either from a tiny static server on `:4400` or from `src-tauri/src/proxy.rs` at `/mcp-plugin/`.
- [ ] Add bare-directory entries in `src-tauri/tauri.conf.json bundle.resources`.

### Phase 3 — Auto-load the MCP plugin
- [ ] On workspace boot, dispatch `app.main.data.plugins/open-plugin!` with the bundled manifest URL.
- [ ] After the plugin iframe loads, post `{type: "start-server", url: "ws://localhost:4402", token: <local>}` so it connects to the PluginBridge without user interaction.
- [ ] Add a one-time user consent for bundled-plugin write permissions.
- [ ] Match `PENPOT_MCP_VERSION` baked into the plugin build to the desktop Penpot version to avoid mismatch warnings.

### Phase 4 — UI + status
- [ ] Add an MCP status indicator in the workspace plugins panel or a new settings entry.
- [ ] Surface the connection URL `http://localhost:4401/mcp` and a copy-paste command for Claude Code / Claude Desktop.
- [ ] (Optional) Add Tauri command `mcp_status` via Foundation F1.

### Phase 5 — Typed mutation tools (optional)
- [ ] Add higher-level tools in `penpot-source/mcp/packages/server/src/tools/`: `create_shape`, `update_shape`, `delete_shape`, `get_selection`, `get_page_tree`.
- [ ] Each tool builds a plugin-code string and dispatches via `PluginBridge.executePluginTask`/`ExecuteCodeTaskHandler`, just like `ExportShapeTool`.
- [ ] Register them in `PenpotMcpServer.initTools()`.

### Phase 6 — Headless backend-RPC transport (optional)
- [ ] Add a second tool class that calls the JVM backend directly using the `proxy.rs` `rpc_call` pattern (`POST /api/main/methods/{method}` transit+json with the auto-login auth token).
- [ ] Expose read-only file-level tools (`get-file`, `get-file-snapshot`, `get-project`) and batch mutation (`update-file` with change-ops).
- [ ] Document that this path bypasses the live workspace (no undo integration, no realtime broadcast).

### Phase 7 — stdio transport + resources (optional)
- [ ] Add `--stdio` mode to `packages/server/src/index.ts` using `@modelcontextprotocol/sdk/server/stdio.js`.
- [ ] Expose MCP resources/templates for the current page tree and component library.

### Open decisions
- [ ] Bundle Node runtime or compile the server to a single binary?
- [ ] Auto-load the plugin by default, or default-off with explicit enable?
- [ ] Ports: keep `:4401/:4402/:4403` or make them dynamic?

---

## Feature 4 — AI design generation from a prompt

Generate a Penpot frame/file from a text prompt using a cloud LLM or a local model. The output is a DesignSpec that feeds Foundation F3 (`apply-design-spec`).

### Phase 1 — Reuse Foundation F3
- [ ] Ensure DesignSpec schema and `apply-design-spec` are implemented.

### Phase 2 — LLM-to-DesignSpec in Rust
- [ ] Build prompt engineering: system prompt embeds the DesignSpec schema + a one-shot example + instruction to return ONLY valid JSON.
- [ ] Implement cloud provider calls (OpenAI `gpt-4o` with `response_format: json_object`, Anthropic Messages with JSON prefill) in Foundation F4.
- [ ] Implement local Ollama provider at `http://127.0.0.1:11434/api/chat` with `format: "json"`.
- [ ] Parse response into the Rust-side `DesignSpec` struct; validate with `serde`.

### Phase 3 — Frontend panel
- [ ] Create `penpot-source/frontend/src/app/main/ui/workspace/sidebar/panels/design_gen.cljs`.
- [ ] Prompt textarea, provider dropdown (cloud/local), generate button, spinner, preview, apply/cancel.
- [ ] Call the Tauri command from Foundation F4 via `@tauri-apps/api/core invoke`.
- [ ] Render preview using the preview component from Foundation F3.

### Phase 4 — Robustness
- [ ] Validate every generated shape with `sm/check` + `cts/check-shape` on the CLJS side.
- [ ] Drop or clamp invalid shapes; report "X of Y shapes applied" to the user.
- [ ] Cap coordinates to a sane canvas range.
- [ ] Add cancel + timeout handling.

### Phase 5 — Optional whole-file generation + MCP tool
- [ ] Generate a `.penpot` ZIP conforming to `app.binfile.v3` layout for multi-file/library designs.
- [ ] Import it via Foundation F6.
- [ ] Expose a `generate_design` MCP tool in `PenpotMcpServer.initTools()`.

### Open decisions
- [ ] Generate a single frame on the current page, a new page, or a new file?
- [ ] Which cloud provider is default? OpenAI vs Anthropic.
- [ ] Support flex/grid auto-layout in the spec, or absolute positioning only for v1?

---

## Feature 5 — Code-to-Design AI generation from prompt + reference

Generate a design from a prompt plus an optional reference: screenshot, website URL, image of an app, or existing code. Shares the backend with Feature 4; the reference only changes the LLM context.

### Phase 1 — Prompt-only generation
- [ ] Implement Feature 4 first; the panel and command are shared.

### Phase 2 — Screenshot / image reference
- [ ] Add an image drop-zone in the panel; read as base64.
- [ ] Pass the image to the LLM as a vision input (requires a vision-capable model).
- [ ] Place the screenshot as a reference rectangle on the canvas via `PenpotUtils.importImage` (`penpot-source/mcp/packages/plugin/src/PenpotUtils.ts:365`) so the user can compare fidelity.
- [ ] Extend the system prompt with a reference-handling section (inspect layout, match colors, use `addFlexLayout`).

### Phase 3 — URL reference
- [ ] Add URL input in the panel.
- [ ] In the Rust orchestrator or as an MCP tool (`FetchUrlTool`), fetch the page HTML + inline CSS via Node `https/http` (pattern from `ImportPenpotFileTool.downloadFile`).
- [ ] Include raw HTML/CSS in the LLM context and let the model emit DesignSpec.
- [ ] Document fidelity ceiling: CSS grid, absolute positioning, pseudo-elements, transforms do not round-trip exactly.

### Phase 4 — Code reference
- [ ] Add a code textarea / file drop accepting `.jsx`, `.tsx`, `.html`, `.vue`, `.swiftui`.
- [ ] Pass code verbatim as text in the LLM prompt with a language hint.
- [ ] The LLM maps the code to Penpot shapes via the existing plugin/DesignSpec path.

### Phase 5 — Exact clone via existing Penpot file
- [ ] Allow dropping a `.penpot` file; import it via Foundation F6 as a baseline.
- [ ] Then let the LLM customize it based on the prompt.

### Open decisions
- [ ] Does the LLM live in the Rust shell, or do we drive the local MCP server (Foundation F5) with an embedded MCP client?
- [ ] Default to "customize on reference" mode; is exact-clone mode ever exposed?
- [ ] Should the generated shapes be grouped onto a single Board per generation for easy undo?

---

## Feature 6 — Real-time team collaboration via a relay server

Multiple desktop peers edit the same file live through a small relay server. Designs stay on each peer's local storage; the relay only forwards transient change/presence/pointer messages.

### Phase 1 — Document the protocol
- [ ] Extract the implicit protocol from:
  - `penpot-source/backend/src/app/http/websocket.clj` — client→server verbs and server→client events.
  - `penpot-source/backend/src/app/rpc/commands/files_update.clj:463-487` — `:file-change` message shape, `revn`, `vern`.
  - `penpot-source/common/src/app/common/files/changes.cljc` — change ops wire format.
- [ ] Confirm changes round-trip through `app.common.transit` and can be applied client-side via `cpc/process-changes`.

### Phase 2 — Decide revn authority
- [ ] **Critical:** choose whether the relay's revn is authoritative, or each peer's localhost backend keeps its own counter.
- [ ] Recommendation: relay revn is authoritative; localhost backend persists file-data but adopts relay revn on each `:file-change`. This may require a small backend flag or an `update-file` variant.

### Phase 3 — Build the relay
- [ ] Implement a thin relay server. Two options:
  - **Clojure** (recommended): copy `websocket.clj`, replace Redis msgbus with an in-memory `map<topic, set<chan>>`, drop DB persistence, keep `:subscribe-file`/`pointer-update`/`broadcast` semantics.
  - **Rust**: use `tokio-tungstenite` and mirror the protocol.
- [ ] Add per-file monotonic `revn` sequencer and a small ring buffer of recent `:file-change` messages for lagged catch-up.
- [ ] Add client→server verb `:publish-change {:file-id :revn :vern :changes}`; fan out `:file-change` to other subscribers.
- [ ] Filter out messages whose `:session-id` equals the sender's.

### Phase 4 — Wire the desktop client
- [ ] In `penpot-source/frontend/src/app/main/data/websocket.cljs`, add a second `relay-conn` + `send-relay`.
- [ ] In `penpot-source/frontend/src/app/main/data/workspace/notifications.cljs`, route `:subscribe-file`, `:unsubscribe-file`, and `:pointer-update` to the relay conn when relay mode is on; keep `:subscribe-team` on localhost.
- [ ] In `penpot-source/frontend/src/app/main/data/persistence.cljs`, dual-path each commit: call `rp/cmd! :update-file` against localhost **and** send `:publish-change` to the relay. Decide if `revn` is relay-authoritative first.
- [ ] Handle `:revn-conflict` from the relay by requesting lagged changes and pausing local commits until caught up.

### Phase 5 — Share/room UX
- [ ] Create a small "Collaborate" panel: current relay URL + file-id as a shareable link, input to join a room, toggle relay mode.
- [ ] Add Tauri commands `set_relay_url`, `get_relay_url`, `relay_status` via Foundation F1; persist config to app data dir.
- [ ] Verify presence/cursors work via the existing `handle-presence` / `handle-pointer-update` handlers.

### Phase 6 — Hardening
- [ ] Reconnection: re-subscribe + request lagged changes on reconnect.
- [ ] Validate incoming changes against `cpc/schema:change` on the relay before fan-out.
- [ ] Shared secret auth on the relay; rate-limit `:publish-change` per session.
- [ ] Document conflict policy: relay-sequenced last-write-wins for MVP; OT/CRDT is future work.

### Open decisions
- [ ] Standalone relay service or peer-as-host?
- [ ] Ephemeral per-session identities, or real Penpot accounts?
- [ ] WebRTC data-channel P2P with relay signaling, or pure WebSocket relay?
- [ ] RAM-only history buffer or disk-backed? (RAM aligns with "no cloud storage".)

---

## Feature 7 — Support for newer Figma features

A prioritized gap-closing checklist, mapping Figma capabilities onto Penpot's existing model where possible.

### Phase 1 — Variables / Modes v2 (map onto tokens + themes)
- [ ] Extend `penpot-source/common/src/app/common/types/token.cljc` `:applied-tokens` and add per-instance mode override in `penpot-source/common/src/app/common/types/shape.cljc`.
- [ ] Add per-instance theme picker in `penpot-source/frontend/src/app/main/ui/workspace/sidebar/options/menus/component.cljs`.
- [ ] Add token-bound component properties in `penpot-source/common/src/app/common/types/variant.cljc`.
- [ ] Add mode-transition prototype interaction in `penpot-source/common/src/app/common/types/shape/interactions.cljc` + execution in `app.main.data.viewer.cljs`.
- [ ] Add migrations in `common/files/migrations.clj` + `backend/binfile/migrations.clj`; round-trip in `backend/binfile/v3.clj`.

### Phase 2 — Component properties, variants, instance swap
- [ ] Promote name-parsed variant properties (`penpot-source/common/src/app/common/types/variant.cljc`) to typed first-class properties: `:boolean`, `:text`, `:variant`, `:instance-swap`.
- [ ] Reuse the existing swap-slot system (`component.cljc` `build-swap-slot-group`/`get-swap-slot`/`set-swap-slot`) for `:instance-swap` properties.
- [ ] Add property editor + override persistence in `ui/workspace/sidebar/options/menus/component.cljs`.
- [ ] Ensure binfile round-trip + spec updates.

### Phase 3 — Dev Mode (code-gen panel)
- [ ] Add Dev/Design mode toggle in `penpot-source/frontend/src/app/main/ui/workspace/left_header.cljs`.
- [ ] Add React/TSX + Tailwind generators in `penpot-source/frontend/src/app/util/code_gen/` (reuse Feature 2 Phase 2; do NOT revive dead Rust modules).
- [ ] Surface them in `penpot-source/frontend/src/app/main/ui/inspect/code.cljs`.
- [ ] Add `:dev-ready` badge + per-frame dev notes (small schema add in `shape.cljc`).

### Phase 4 — Sections, shape annotations, sticky polish
- [ ] Add `:section` shape type in `penpot-source/common/src/app/common/types/shape.cljc`; render branch in `app.main.render.cljs`.
- [ ] Add Section tool to `penpot-source/frontend/src/app/main/ui/workspace/toolbar.cljs`.
- [ ] Add shape-level `:annotation` attr and an Annotation menu + inline label.
- [ ] Verify `:fixed-scroll` (sticky) in the prototype viewer; expose in constraints menu if missing.

### Phase 5 — Advanced prototyping
- [ ] Add `:smart-animate` animation type in `interactions.cljc`; implement diff+tween in `app.main.data.viewer.cljs`.
- [ ] Add conditional interactions using token values / active-themes.
- [ ] Add `:on-scroll` and `:after-delay` triggers in `interactions.cljc` + UI in `interactions.cljs`.

### Phase 6 — Multi-edit
- [ ] Add `select-similar` + `select-all-with-same` events in `frontend/src/app/main/data/workspace/`.
- [ ] Add context-menu entries in `frontend/src/app/main/ui/workspace/context_menu.cljs`.
- [ ] Add batch-override-by-property across multi-selection using `changes-builder.cljc`.

### Phase 7 — In-app AI assistant (optional)
- [ ] Reuse Foundation F4 (LLM provider) + Foundation F5 (MCP server) + Foundation F3 (DesignSpec).
- [ ] Add a workspace AI panel; stream LLM responses; route tool calls to the local MCP server.

### Open decisions
- [ ] True Figma variable parity, or extend DTCG tokens + themes?
- [ ] Frontend CLJS codegen vs. native Rust codegen?
- [ ] Upstream Penpot PRs, or fork-only?

---

## Cross-cutting open decisions (own these before implementation)

| Decision | Options | Blocks | Owner (assign someone) |
|----------|---------|--------|------------------------|
| Tauri command registration | one module per feature vs. single `commands.rs` | 5 features | TBD |
| Cargo crate additions | reqwest, zip, tokio-tungstenite, dirs | all shell features | TBD |
| Node runtime for MCP | bundled `node.exe` vs. single compiled binary | MCP server, code-to-design | TBD |
| LLM provider default | OpenAI / Anthropic / both | AI generation, code-to-design | TBD |
| API key storage | app-data JSON (plaintext) vs. OS keyring | all LLM features | TBD |
| DesignSpec owner | one feature team owns the schema + converter | AI gen, code-to-design, Figma AI | TBD |
| Dead Rust codegen modules | delete to `_archived/` vs. repurpose name helpers | code export, Figma Dev Mode | TBD |
| Google Fonts scope | catalog refresh vs. offline binaries + variable fonts | Feature 1 | TBD |
| Relay revn authority | relay-authoritative vs. localhost backend | Feature 6 | TBD |
| Offline egress policy | default-off + opt-in per feature vs. always-online | all network features | TBD |
| Installer-size budget | e.g. +50MB review gate | all resource-bundling features | TBD |

---

## Suggested execution order

1. **Land Foundation F1–F3 first.** Without Tauri commands and DesignSpec, the AI and code-export features cannot progress.
2. **Feature 1 (Google Fonts).** Smallest, no AI dependency, surfaces the disabled-flag blocker early.
3. **Feature 2 (Code export) React MVP.** Reuses existing CLJS codegen heavily; only needs F1 + `zip` crate.
4. **Feature 3 (MCP server).** Enables Features 4 and 5; requires F5 (Node bundling decision).
5. **Feature 4 (AI design generation).** Needs F3 + F4; ships prompt-only generation.
6. **Feature 5 (Code-to-Design).** Superset of Feature 4 + reference ingestion.
7. **Feature 6 (Relay collaboration).** Self-contained after F1 + `tokio-tungstenite`; needs the revn-authority decision.
8. **Feature 7 (Figma parity).** Broad, pulls from every foundation; do after the others are stable.
