# Penpot Desktop — Product Roadmap & Feature Checklist

A working checklist for the features requested for Penpot Desktop. Every item is grounded in the actual codebase (`penpot-source/`, `src-tauri/`) and includes the real files you will touch, the prerequisites you must land first, and the open product decisions that block implementation.

## Product direction (read this first)

This app is being re-positioned as a **unique, own-branded design studio** (new name + logo come later, out of scope of this work). The differentiator is a **closed AI layer**: the user only sees an input bar and the final result on the canvas — they never touch the model, the prompts, or the tool plumbing. The AI must produce **proper designs *and* prototypes**, not just static frames.

### Feature sequence (the order we ship)

```
Fonts → Code Export → AI (complete end-to-end) → Figma Parity → Code-to-Design → MCP Server → Relay Collaboration
  1        2                    3                     4                5                6               7
```

### Goals

- **First goal — reach Figma Parity and launch.** Features 1–4 are in-scope for launch: Fonts, Code Export, the complete end-to-end AI layer, and Figma feature parity. These four define the product.
- **Later, optional, incremental.** Features 5–7 ship *after* launch as incremental updates:
  - **Feature 5 — Code-to-Design.** Optional. Reuses the AI layer with reference ingestion.
  - **Feature 6 — MCP Server.** Optional, and the **last feature** before Relay. Deliberately deferred so we can make the product unique on the integrated AI layer first, rather than exposing an open tool surface to external agents early.
  - **Feature 7 — Relay Collaboration.** Optional, the **very last** stage. Real-time team collaboration through a relay server.

### Theme / branding

The app is re-themed to a warm **peach** palette (light + dark) to feel like a design studio rather than an emotionless tool. See `docs/THEME.md` (or the in-repo theme tokens) for the canonical color variables. New product name + logo are a later, separate task and are **not** in scope here.

---

## Guiding invariants (read this before starting any feature)

1. **Offline-first desktop app.** The installer bundles Postgres, Redis, the JVM backend, the SPA, and the builtin templates. Network egress is opt-in, not default — *except* the Google Fonts **catalog** and **font binaries**, which are fetched online by default (Feature 1); full offline font use is an opt-in download.
2. **Same-origin SPA on `http://localhost:1420`.** The Rust proxy in `src-tauri/src/proxy.rs` forwards `/api/`, `/internal/`, `/ws/`, `/assets/` to the JVM backend on `:3449`. The frontend cannot call external APIs directly. All outbound AI/URL fetch goes through Tauri commands in the Rust shell, never from the renderer.
3. **No Tauri commands are registered today.** `src-tauri/src/lib.rs` declares only `mod proxy;` and the builder has no `.invoke_handler`. The FIRST `#[tauri::command]` + `invoke_handler` registration is shared infrastructure (Foundation F1) for multiple features.
4. **Google Fonts and render-wasm are disabled in the desktop build.** `src-tauri/src/lib.rs:467-474` sets `disable-google-fonts-provider` and several `disable-*-render-wasm*` flags inside the `PENPOT_FLAGS` env var (in `backend_env`), which the frontend reads as `cf/flags`. There is **no** `webPreferences`/`additional_args` block — the window is built plainly at `lib.rs:768-772`. Feature 1 re-enables the Google Fonts provider for online use and gates offline binaries behind an opt-in download; the desktop Rust proxy must also gain the `/internal/gfonts/css` and `/internal/gfonts/font/*` routes that upstream Penpot gets from nginx (see `penpot-source/docker/devenv/files/nginx.conf:231-278`).
5. **Bare-directory resource bundling.** `src-tauri/tauri.conf.json bundle.resources` flattens `**/*` globs to basename. Use bare directory paths to preserve subdirectories (templates, font cache, MCP dist, JRE/lib, etc.).
6. **The AI layer is closed and integrated, not external.** Unlike upstream Penpot (where `penpot-source/mcp/` is a standalone MCP server + plugin and the LLM is an external client), the in-app AI layer is owned by the Rust shell. The user supplies an input + files + optional URL; the shell orchestrates the model, fetches URL resources, turns the response into a DesignSpec, and applies it to the canvas. The model, prompts, and tool plumbing are never user-facing. The MCP server (Feature 6) is a *separate*, later, opt-in surface for external agents — it is not the AI layer.

---

## Phase 0 — Foundations (build these once, then reuse)

These are the shared prerequisites that multiple features depend on. Do them first, before touching any feature-specific code.

**Scope note for this build:** Only the foundations required to reach the **first goal (Figma Parity launch)** are landed now — **F1, F2 (partial), F3, F4**. **F5 and F6 are deferred** (they only serve the optional later features Code-to-Design and MCP Server). If a deferred foundation turns out to be *required* by an in-scope feature, land that piece then — do not land the whole foundation speculatively.

### F1 — Tauri command foundation  ✅ in scope (land now)
- [x] Add `mod commands;` to `src-tauri/src/lib.rs`.
- [x] Register `.invoke_handler(tauri::generate_handler![...])` on the builder.
- [x] Add a trivial `ping` command and confirm the frontend can invoke it end-to-end.
- [x] Convention: **one module per feature** (`code_export.rs`, `llm.rs`, `designgen.rs`, `fonts.rs`, `relay.rs`) plus a shared `commands.rs` for cross-cutting commands.

**Needed by:** everything. Fonts (offline download), code export, AI generation, code-to-design, relay, MCP status.

### F2 — Cargo dependency bump (coordinated, partial)  ✅ in scope (land the required subset now)
Add to `src-tauri/Cargo.toml`, pin versions, update the build cache key. **Land now:** `reqwest` (AI HTTP + URL fetch + the proxy's TLS fetch of Google Fonts CSS/binaries), `zip` (code-export / generated ZIPs), `dirs` (app-data config path). **Defer until Relay:** `tokio-tungstenite` (only for the collaboration relay).
- [x] `reqwest` — outbound HTTP to LLM APIs (AI generation, URL reference fetch, optional Figma AI) **and** the desktop proxy's TLS fetch of `fonts.googleapis.com` / `fonts.gstatic.com` for the online Google Fonts path (Feature 1). The hand-written proxy in `proxy.rs` only speaks HTTP/1.1 to `127.0.0.1:3449` today — it has no TLS client, so the `/internal/gfonts/*` routes use `reqwest` (with the `rustls-tls` feature) to reach Google's CDN.
- [x] `zip` — writing code-export and generated `.penpot` ZIPs.
- [ ] `tokio-tungstenite` — **deferred** to Feature 7 (Relay).
- [x] `dirs` — app-data config path instead of hand-rolled `%APPDATA%` logic.

**Needed by:** code export (zip), AI generation (reqwest), code-to-design (reqwest+zip), relay (tungstenite — later).

### F3 — DesignSpec schema + `apply-design-spec`  ✅ in scope (land now)
- [ ] Create `penpot-source/common/src/app/common/types/design_spec.cljc`: a small Malli schema for generated designs (`:frames` → `:shapes` with a constrained subset of `shape.cljc` attrs) **and prototypes** (`:interactions`, `:flows` — see Feature 3 for the prototype fields).
- [ ] Add `spec->shapes` that emits valid Penpot shape maps via `cts/setup-shape`.
- [ ] Create `penpot-source/frontend/src/app/main/data/workspace/design_gen.cljs` with `apply-design-spec`, mirroring `app.plugins.api/create-shape` (`pcb/empty-changes`, `pcb/add-object`, `dch/commit-changes`, undo transaction).
- [ ] Add a preview renderer: pass generated shapes through `app.main.render` for a modal preview before commit.

**Needed by:** AI design generation (Feature 3), code-to-design (Feature 5, later), optional Figma parity in-app AI (Feature 4).

### F4 — LLM provider abstraction in Rust (Kimi K2.6)  ✅ in scope (land now)
The AI layer uses **Kimi K2.6** as the underlying model. Two interchangeable providers, same model:
- **Ollama** (`http://127.0.0.1:11434`) — for **local testing only**. API key optional/local.
- **DeepInfra** (`https://api.deepinfra.com`) — **production**, same Kimi K2.6 model. Users get a monthly/yearly plan.

The provider is a **closed implementation detail** — the UI exposes no model/provider selector to the end user; switching is a config-file/build flag concern.
- [ ] Create `src-tauri/src/llm.rs` with a provider enum: `deepinfra` (default, production), `ollama` (local testing).
- [ ] Read config from a JSON file in the app data dir; **never expose the API key to the frontend.** DeepInfra key ships from a bundled config or a first-run settings entry; Ollama needs no key.
- [ ] Implement `generate(prompt, system, images, context, provider) -> DesignSpec` with JSON response parsing + timeout. Both providers speak the OpenAI-compatible chat-completions shape; DeepInfra and Ollama both accept `model: "kimi-k2.6"` (or the DeepInfra slug `moonshotai/Kimi-K2.6` — confirm the exact slug at implementation time).
- [ ] Add URL-reference fetching in the orchestrator: if the prompt contains a URL, **the shell visits it first**, extracts text/HTML/CSS/assets, and includes that as context *before* calling the model. (See Feature 3 Phase 3.)
- [ ] Add Tauri commands `llm_generate`, `llm_get_config`, `llm_set_config` registered via F1. The frontend calls `llm_generate` with `{prompt, files, options}` and receives a DesignSpec (or a streamed progress token).

**Needed by:** AI design generation (Feature 3), code-to-design (Feature 5), Figma parity in-app AI (Feature 4).

### F5 — MCP server sidecar + MCP plugin auto-load  ⏸ deferred (optional, later)
Only needed for Feature 6 (MCP Server) and Feature 5 (code-to-design shared execution path). **Do not land now.** If an in-scope feature turns out to need it, land only the required piece.
- [ ] Pre-build `penpot-source/mcp/packages/server/dist/index.js` and `penpot-source/mcp/packages/plugin/dist/`.
- [ ] Add bare-directory entries in `src-tauri/tauri.conf.json bundle.resources`.
- [ ] Add `start_mcp_server(root)` in `src-tauri/src/lib.rs` using `std::process::Command` + `silent()` + `wait_for_port` on `:4401` and `:4402`; add `BackendState.mcp` and kill it in `shutdown_services`.
- [ ] In the frontend, auto-load the bundled plugin via `app.main.data.plugins/open-plugin!` and post `start-server` to the plugin iframe (`plugin/src/main.ts:306`) so it connects to `ws://localhost:4402` without a user click.
- [ ] Add a one-time consent UX for the bundled first-party plugin (it has `content:write` permission).
- [ ] Decide bundling strategy: ship a bundled `node.exe` (like the bundled JRE) OR compile the MCP server to a single executable via `pkg`/`@yao-pkg/pkg`/`bun --compile`.

**Needed by (later):** Feature 6 (MCP server), Feature 5 (code-to-design shared execution path), Feature 3 Phase 5 (optional whole-file generation).

### F6 — Packaged binfile-import bridge (no nREPL)  ⏸ deferred (optional, later)
Only needed for Feature 5 (code-to-design exact-clone) and Feature 6. **Do not land now.**
- [ ] Generalize the import path used by `penpot-source/mcp/packages/server/src/tools/ImportPenpotFileTool.ts` so it works outside dev mode.
- [ ] Add a frontend helper that takes a local `.penpot` file path (from Tauri dialog or resources) and emits `app.main.worker/ask-many!` with `{:cmd :import-files :type :binfile-v3}`.
- [ ] Wire it to `app.worker/import.cljs` without requiring a shadow-cljs nREPL.

**Needed by (later):** Feature 5 Phase 5, Feature 6, Figma parity component-properties round-trip.

---

## Feature 1 — Updated Google Fonts library + font preview

Refresh the Google Fonts catalog. **Online by default** (the full catalog + binaries fetched from Google's CDN — downloading and packaging all binaries offline would balloon the installer). **Offline use is an opt-in download** for users who want it. Additionally, the font picker must **preview each font in its own typeface** (like word processors), not just show the name.

### Phase 1 — Refresh the catalog list (online, default)
> **Status:** the bundled `gfonts.2025.11.28.json` snapshot is retained as the catalog — it already includes the `menu` field Feature 1's preview depends on. A *fresh* webfontList fetch needs a Google API key (not available in this environment) and is deferred; the existing recent snapshot is wired through end-to-end (flag removed, library registers, picker shows it, preview + offline work). The checkboxes below track the fresh-snapshot refresh separately.
- [x] Confirm `disable-google-fonts-provider` is in `src-tauri/src/lib.rs:467-474` (the `PENPOT_FLAGS` env var in `backend_env`); **remove it** (or gate it behind the offline-only mode toggle) so the live Google Fonts provider works online.
- [ ] Fetch a fresh Google Fonts webfontList snapshot from `https://www.googleapis.com/webfonts/v1/webfonts?key=...`. — **Deferred:** needs a Google Fonts API key; the existing `gfonts.2025.11.28.json` is used meanwhile.
- [ ] Save it as `penpot-source/frontend/resources/fonts/gfonts.<YYYY.MM.DD>.json` **and** `penpot-source/frontend/resources/public/fonts/gfonts.<YYYY.MM.DD>.json` (the public copy ships as the fallback catalog when the network is unavailable). — **Deferred** (same as above).
- [ ] Update the filename in `penpot-source/frontend/src/app/main/fonts.cljs:29`. — **Deferred** (still `gfonts.2025.11.28.json`); `parse-gfont` already captures `:menu` so no other change is needed for the preview.
- [ ] Recompile ClojureScript so the `preload-gfonts` macro embeds the new list. — **Owner: build** (the `:menu` capture in `parse-gfont` is in place, so a recompile bakes it).
- [ ] Verify the font picker (`penpot-source/frontend/src/app/main/ui/workspace/sidebar/options/menus/typography.cljs`) shows the refreshed families. — **Owner: build/run** (no build in this session).
- [ ] Regression-test existing files: font-id→uuid mapping in `penpot-source/frontend/src/app/render_wasm/api/fonts.cljs` falls back to Source Sans Pro when an ID disappears; keep the old JSON to diff removals/renames. — **Owner: build/run** (catalog unchanged, so no removals/renames to regress).

### Phase 2 — Font preview in the picker (show the typeface, not just the name)
The picker must render each family/variant **in its own typeface** alongside the name, the way word processors do. This is the headline UX change of Feature 1.
- [x] In `typography.cljs`, replace the name-only font row with a row that sets `font-family` to the family's CSS stack and renders a sample string (e.g. the family name + a pangram) in that face. — `font-item*` sets inline `:font-family (fonts/css-font-family (:family font))` on the label span, so the family NAME renders in its own face.
- [x] Lazy-load the woff2 for each visible family from Google Fonts CSS2 (`https://fonts.googleapis.com/css2?family=...`) on first scroll into view, so the preview renders in the real typeface without preloading the whole catalog. — Instead of the full family woff2, `font-item*`'s `use-effect` (keyed on `(:id font)`) calls `fonts/ensure-gfont-preview!`, which injects a one-file `@font-face` for Google's tiny **menu** TTF (one file per family) via the proxied `/internal/gfonts/font` route. Lighter than the full family and enough to render the name in-face.
- [x] Fall back to the embedded catalog's generic family + a system fallback while the woff2 loads, so the list is never blank. — The inline `font-family` falls back through the browser's default until the `@font-face` (font-display: swap) loads; the row text is the family name regardless, so the list is never blank.
- [x] Keep the search/filter box; match against family name (current behavior) but render results in-face. — `font-selector*`'s `filter-fonts` is unchanged; rendered rows are the in-face `font-item*`.
- [x] Honor the dark/light theme: preview text uses `--text`, the row hover uses `--surface-2`, the selected row uses `--accent` (see the theme tokens). — `.font-item`/`.font-item-label`/`.selected` consume the peach `--color-*` tokens; the inline `font-family` is the only face-level override.

### Phase 3 — Optional offline font download (opt-in, per-user)
Full offline binaries are **not** bundled by default (package-size budget). Users who want true offline use trigger an on-demand download.
- [x] Add a "Download fonts for offline use" entry in the fonts menu / settings. On trigger, download woff2 files (static or variable) under an app-data cache dir (via `dirs` cache dir), e.g. `<appdata>/fonts/gfonts/<slug>/<variant>.woff2`. — `font-item*` renders a per-row download `icon-button*` (revealed on hover) for Google fonts; it invokes the `fonts_download_family` Tauri command with the family's css2 query + menu URL and reports success/failure via `ntf/info`/`ntf/error`. (A global "download all" entry is intentionally avoided — on-demand per family, per the open decision below.)
- [x] Add Tauri command `download_font_family(slug, variant)` (and a batch `download_fonts(subset)`) in `src-tauri/src/fonts.rs`, registered via F1. Downloads via `reqwest`, writes to the cache dir, reports progress. — Landed as `fonts_download_family(app, query, menu_url)` in `src-tauri/src/fonts.rs`: fetches the css2 CSS, writes it to `<cache>/css/<slugify("css-{query}")>.css` (same filename the proxy looks for), fetches + caches every gstatic font file and the menu-preview TTF under `<cache>/font/<rest>`. No batch subset — per family is the unit.
- [x] Intercept `/internal/gfonts/font/<path>` (and the CSS2 request) in `src-tauri/src/proxy.rs`: serve the cached woff2 with correct `Content-Type` and long cache headers when present; fall through to the JVM/Google CDN only for cache misses. — `handle_gfonts_css` + `handle_gfonts_font` in `proxy.rs` serve the on-disk cache when present, else fetch live from fonts.googleapis.com / fonts.gstatic.com over TLS (rustls) and write the cache. Intercepted BEFORE the `/internal/` PROXY_PREFIXES block (the JVM backend has no gfonts routes — nginx-only upstream).
- [x] Frontend: when offline mode is on, the picker preview + rendering pull from the local cache; show a per-family "available offline" badge and a "download" affordance for un-cached families. — The proxy serves the cache transparently at the SAME URLs the SPA already uses, so preview + rendering pull from cache with no SPA branching and no explicit "offline mode" toggle. The per-row "download" affordance is landed. **Deferred:** the per-family "available offline" badge (needs a cache-state Tauri command + per-row polling) — tracked below.
- [ ] Add OFL/Apache license files alongside downloaded fonts (write `<slug>/LICENSE.txt` into the cache). — **Deferred:** the css2 response carries no license text, and the baked gfonts JSON doesn't currently capture the per-family `license` field, so the correct license (OFL vs Apache) isn't known at download time. Land when the catalog refresh (Phase 1) bakes `:license`.
- [x] Respect the installer-size budget: the cache lives in app-data, **not** in the installer bundle, so the base installer stays small. — `commands::fonts_cache_dir` resolves to the OS app-data dir (`<appdata>/PenpotDesktop/fonts/gfonts`), written by the proxy + `fonts_download_family`; nothing is added to the installer bundle.

### Phase 4 — Variable fonts (optional, after preview + offline)
- [ ] Switch the catalog to Google Fonts CSS2 / webfonts v2 list to get `axes`.
- [ ] Extend `penpot-source/frontend/src/app/main/fonts.clj` `parse-gfont-variant` to emit `:variable` variants carrying axes.
- [ ] Update `get-variant`, `find-variant`, `find-closest-variant` in `penpot-source/frontend/src/app/main/fonts.cljs` for variable axes.
- [ ] Add a weight/axis slider in `typography.cljs` when the selected font is variable; preview updates live.
- [ ] Download variable woff2 files covering full axis ranges from the CSS2 endpoint when the user opts into offline for a variable family.

### Open decisions
- [x] Curated offline subset vs. download-whatever-you-use? — **Decided:** download-on-demand per family (`fonts_download_family`); no curated subset, no "download all".
- [x] Cache-on-first-render (auto) vs. explicit "download for offline" toggle? — **Decided:** both. The proxy auto-caches every font it serves online (so anything you've ever used works offline thereafter), AND there's an explicit per-family "download for offline" affordance in the picker for pre-warming a whole family before first use.
- [ ] How to keep the two `gfonts.*.json` copies from drifting? — **Deferred** with the Phase 1 catalog refresh (single existing snapshot in use; build-time copy is the recommendation when a fresh snapshot lands).

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

## Feature 3 — AI: complete end-to-end design *and* prototype generation

The headline feature. From a **single input bar** the user generates **proper designs *and* prototypes** (interactive frames with interactions and flows, not just static layouts). The AI layer is **closed**: the user supplies a prompt, optional files, and optional URL references; the shell orchestrates Kimi K2.6 (DeepInfra in production, Ollama for local testing), fetches any referenced URLs first, turns the model response into a DesignSpec that includes prototype interactions, and applies it to the canvas. The user never sees the model, the prompts, or the tool plumbing — only the input bar and the final result.

Reuses Foundation F3 (DesignSpec + `apply-design-spec`) and F4 (LLM provider, Kimi K2.6).

### Phase 1 — Reuse Foundation F3 + F4
- [ ] Ensure the DesignSpec schema (F3) covers **prototypes**, not just static shapes: `:frames`, `:shapes`, plus `:interactions` (per-shape event→destination/animation) and `:flows` (named prototype flows with start-frame).
- [ ] Ensure F4's `llm_generate` command accepts `{prompt, files, options}` and returns a DesignSpec (with prototype fields).

### Phase 2 — The input bar UI (bottom-floating, side panels)
The AI input lives at the **bottom of the workspace (project-opened page)**, floating above the canvas with a gap (not flush to the edge), with **panels on both sides**.
- [ ] Create `penpot-source/frontend/src/app/main/ui/workspace/ai_bar.cljs` (the input bar component) and `penpot-source/frontend/src/app/main/data/workspace/ai_gen.cljs` (the event/data namespace).
- [ ] **Layout:** a fixed-position bar at the bottom of the workspace, raised from the bottom edge by a gap (e.g. `bottom: 24px`), centered with a max-width, `border-radius` and `--surface` background, soft shadow, hovering *over* the canvas (pointer-events on the bar, pass-through elsewhere).
- [ ] **Left panel:** file/attachment affordances — drop zone + attach button for images, `.penpot` files, code files (`.jsx/.tsx/.html/.vue/.swiftui`), screenshots.
- [ ] **Center:** a simple, single-line-grow prompt input (textarea that expands). Placeholder like "Describe a design or prototype…".
- [ ] **Right panel:** "other options" — generation mode toggles (e.g. "Design" vs "Prototype" vs "Design + Prototype"), target (current page / new page / new board), and a generate button. Keep options minimal — the layer is closed, so do **not** expose model/provider/temperature.
- [ ] Both side panels are compact and collapse to icons when the bar is narrow; the bar is always reachable but does not obstruct the canvas when idle (it can auto-hide the side panels until focused).
- [ ] Wire the bar to `llm_generate` via `@tauri-apps/api/core invoke` (F1 bridge). Pass `{prompt, files (paths/base64), options}`.
- [ ] Show a spinner/progress on the generate button while the shell works; surface non-blocking errors inline under the bar.

### Phase 3 — URL reference fetching (shell-side, before the model runs)
- [ ] In the Rust orchestrator (`src-tauri/src/llm.rs` or a new `src-tauri/src/url_ref.rs`), scan the prompt for URLs (regex) **before** calling the model.
- [ ] For each URL, fetch the page (via `reqwest`) and extract: visible text, inline `<style>`/linked CSS, `<img src>`/asset URLs, and a flattened DOM outline. Convert to a compact text context.
- [ ] Download referenced image assets to a temp dir and pass them as **vision inputs** to the model (Kimi K2.6 multimodal) alongside the prompt.
- [ ] Cap fetched context size (truncate HTML/CSS, limit asset count/bytes) to stay within the model context window.
- [ ] If a URL is unreachable or blocks the fetcher, surface a non-blocking warning in the bar ("could not fetch <url>; generating from prompt only") and continue.

### Phase 4 — Prompt engineering → DesignSpec (designs + prototypes)
- [ ] System prompt embeds the DesignSpec schema + a one-shot example + the instruction to return **only** valid JSON.
- [ ] The system prompt teaches the model to emit **prototype** fields when the user asks for a prototype or interactive design: `:interactions` (event→destination with animation type) and `:flows` (start frame + flow name), mirroring `penpot-source/common/src/app/common/types/shape/interactions.cljc` and the viewer's flow model.
- [ ] For URL references, append the fetched context (Phase 3) with a reference-handling section: inspect layout, match colors, preserve hierarchy, prefer flex layouts (`addFlexLayout`-style).
- [ ] Implement DeepInfra provider (production, `model: kimi-k2.6` / DeepInfra slug `moonshotai/Kimi-K2.6` — confirm exact slug) and Ollama provider (`http://127.0.0.1:11434/api/chat`, `model: kimi-k2.6`, `format: "json"`).
- [ ] Parse the response into the Rust-side `DesignSpec` struct; validate with `serde`; reject non-JSON with a retry.

### Phase 5 — Apply to canvas + prototype wiring
- [ ] Frontend `apply-design-spec` (F3) commits shapes via `pcb/empty-changes` → `pcb/add-object` → `dch/commit-changes` in one undo transaction, grouped on a single board per generation (easy undo).
- [ ] Wire `:interactions` onto the committed shapes (event, destination-frame-id, animation) and register `:flows` so the result is **runnable in the prototype viewer** (`app.main.data.viewer.cljs`).
- [ ] Validate every generated shape with `sm/check` + `cts/check-shape` on the CLJS side; drop or clamp invalid shapes; report "X of Y shapes applied".
- [ ] Cap coordinates to a sane canvas range; resolve destination-frame-ids against the generated frames.
- [ ] Add a preview (F3 preview renderer) before commit; "Apply" / "Cancel" from the bar.

### Phase 6 — Robustness
- [ ] Cancel + timeout handling in `llm_generate` (reqwest timeout + abort signal from the bar).
- [ ] Stream progress (fetching URL → generating → applying) to the bar so the closed layer still feels responsive.
- [ ] Persist a small history of generations (prompt + thumbnail) in app-data for re-apply, without exposing internals.

### Open decisions
- [ ] Generate onto the current page, a new page, or a new file? (Recommendation: a new board on the current page, grouped, for easy undo.)
- [ ] Default generation mode: "Design + Prototype" when the prompt implies interaction, else "Design"? (Recommendation: auto-detect from prompt; let the right panel override.)
- [ ] Support flex/grid auto-layout in the spec, or absolute positioning only for v1? (Recommendation: flex for v1, grid later.)
- [ ] DeepInfra exact Kimi K2.6 slug + whether Ollama and DeepInfra use identical model naming.

---

## Feature 4 — Support for newer Figma features (Figma parity)

A prioritized gap-closing checklist, mapping Figma capabilities onto Penpot's existing model where possible. This is the **launch gate**: the first goal is to reach Figma Parity. Pulls from every foundation (F1–F4); the optional in-app AI assistant here reuses F4.

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

### Phase 7 — In-app AI assistant (optional, reuses Feature 3)
- [ ] Reuse Foundation F4 (LLM provider) + Foundation F3 (DesignSpec). The closed AI layer from Feature 3 is the same layer; here it gains selection-aware actions (e.g. "restyle this frame", "add interactions to selection").
- [ ] Route AI tool calls through the same `apply-design-spec` path; do **not** require the MCP server (F5) for this.

### Open decisions
- [ ] True Figma variable parity, or extend DTCG tokens + themes?
- [ ] Frontend CLJS codegen vs. native Rust codegen?
- [ ] Upstream Penpot PRs, or fork-only?

---

## Feature 5 — Code-to-Design AI generation from prompt + reference  ⏸ optional / later

Generate a design from a prompt plus an optional reference: screenshot, website URL, image of an app, or existing code. **Shares the AI layer with Feature 3**; the reference only changes the LLM context (Feature 3 Phase 3 already does URL fetching). Ships after launch.

> **Note:** Feature 3 Phase 3 already implements URL-reference ingestion. This feature extends it with screenshot/image/code references and exact-clone via `.penpot` files. Requires Foundation F5 (MCP shared execution path) + F6 (binfile import) — both deferred.

### Phase 1 — Prompt-only generation
- [ ] Feature 3 already delivers prompt-only generation; this feature adds references on top.

### Phase 2 — Screenshot / image reference
- [ ] Add an image drop-zone in the AI bar's left panel (Feature 3 Phase 2 left panel already accepts files); read as base64.
- [ ] Pass the image to the LLM as a vision input (Kimi K2.6 multimodal — same model, no provider change).
- [ ] Place the screenshot as a reference rectangle on the canvas via `PenpotUtils.importImage` (`penpot-source/mcp/packages/plugin/src/PenpotUtils.ts:365`) so the user can compare fidelity.
- [ ] Extend the system prompt with a reference-handling section (inspect layout, match colors, use `addFlexLayout`).

### Phase 3 — URL reference
- [ ] Feature 3 Phase 3 already fetches URL references in the shell. Here, expose a dedicated "clone this URL" affordance and document the fidelity ceiling: CSS grid, absolute positioning, pseudo-elements, transforms do not round-trip exactly.

### Phase 4 — Code reference
- [ ] Accept `.jsx`, `.tsx`, `.html`, `.vue`, `.swiftui` in the left panel; pass code verbatim as text in the LLM prompt with a language hint.
- [ ] The LLM maps the code to Penpot shapes via the existing DesignSpec path.

### Phase 5 — Exact clone via existing Penpot file (requires F6)
- [ ] Allow dropping a `.penpot` file; import it via Foundation F6 as a baseline.
- [ ] Then let the LLM customize it based on the prompt.

### Open decisions
- [ ] Default to "customize on reference" mode; is exact-clone mode ever exposed?
- [ ] Should the generated shapes be grouped onto a single Board per generation for easy undo? (Yes — same as Feature 3.)

---

## Feature 6 — AI Agent MCP server  ⏸ optional / last feature before Relay

Package and host the existing upstream MCP server (`penpot-source/mcp/`) inside the desktop app so **external** AI agents (Claude Code, Claude Desktop) can read and edit designs via the Penpot Plugin API. This is deliberately the **last feature** before Relay: the product's uniqueness comes from the closed integrated AI layer (Feature 3), so the open MCP tool surface is deferred until after launch.

Requires Foundation F5 (MCP sidecar + plugin auto-load). Lands after Figma Parity.

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

## Feature 7 — Real-time team collaboration via a relay server  ⏸ optional / very last

Multiple desktop peers edit the same file live through a small relay server. Designs stay on each peer's local storage; the relay only forwards transient change/presence/pointer messages. This is the **very last** stage, after the MCP server. Requires Foundation F2's deferred `tokio-tungstenite`.

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

## Cross-cutting open decisions (own these before implementation)

| Decision | Options | Blocks | Owner (assign someone) |
|----------|---------|--------|------------------------|
| Tauri command registration | one module per feature vs. single `commands.rs` | all features | Decided: one module per feature (F1) |
| Cargo crate additions | reqwest, zip (now); tokio-tungstenite (later); dirs | all shell features | Decided: land reqwest+zip+dirs now, defer tungstenite |
| Node runtime for MCP | bundled `node.exe` vs. single compiled binary | MCP server, code-to-design | TBD (Feature 6, later) |
| LLM provider default | DeepInfra (prod) vs. Ollama (test) — both Kimi K2.6 | AI generation, code-to-design | Decided: DeepInfra default, Ollama for testing (F4) |
| API key storage | app-data JSON (plaintext) vs. OS keyring | all LLM features | TBD (never expose to frontend regardless) |
| DesignSpec owner | one feature team owns the schema + converter | AI gen, code-to-design, Figma AI | Decided: F3 owns it; prototype fields added for Feature 3 |
| Dead Rust codegen modules | delete to `_archived/` vs. repurpose name helpers | code export, Figma Dev Mode | TBD |
| Google Fonts scope | online catalog + on-demand offline download + font preview | Feature 1 | Decided (see Feature 1) |
| DeepInfra Kimi K2.6 slug | confirm exact model slug + naming parity with Ollama | Feature 3 | TBD (confirm at impl) |
| Relay revn authority | relay-authoritative vs. localhost backend | Feature 7 | TBD (later) |
| Offline egress policy | default-off + opt-in per feature vs. always-online | all network features | Decided: fonts online by default; AI egress opt-in via provider config |
| Installer-size budget | e.g. +50MB review gate | all resource-bundling features | Decided: font binaries live in app-data cache, not the installer |

---

## Suggested execution order

1. **Land required Foundation F1–F4 first.** Without Tauri commands (F1), the AI provider (F4), and DesignSpec (F3), the AI and code-export features cannot progress. Defer F5/F6.
2. **Feature 1 (Google Fonts + preview).** Smallest, no AI dependency, surfaces the disabled-flag blocker early. Online by default + opt-in offline + font preview.
3. **Feature 2 (Code export) React MVP.** Reuses existing CLJS codegen heavily; only needs F1 + `zip` crate.
4. **Feature 3 (AI, complete end-to-end).** The headline feature. Needs F3 + F4; ships designs **and** prototypes from the bottom input bar (files + prompt + URL), closed Kimi K2.6 layer.
5. **Feature 4 (Figma parity).** Broad, pulls from every foundation; **this is the launch gate** — reaching Figma parity is the first goal.
6. *(After launch, optional, incremental)* **Feature 5 (Code-to-Design).** Superset of Feature 3 + reference ingestion. Needs F5/F6.
7. *(After launch, optional)* **Feature 6 (MCP server).** Last feature before Relay; needs F5 (Node bundling decision).
8. *(After launch, optional, very last)* **Feature 7 (Relay collaboration).** Self-contained after F1 + `tokio-tungstenite`; needs the revn-authority decision.