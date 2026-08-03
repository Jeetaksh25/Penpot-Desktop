# Ovion Desktop vs. Penpot Desktop — Differences, Added Features, Logic Systems & AI

> A comprehensive, first-hand map of everything Ovion Desktop adds, changes, or re-implements on top of base Penpot — including how each piece works underneath, how to use it, and where it lives in the UI.
>
> **Scope note:** "Ovion Desktop" = the Tauri-wrapped, offline, single-user fork in this repo. "Penpot" = upstream Penpot (the OSS web design tool whose JVM backend + CLJS frontend Ovion wraps unmodified). Everything here is verified against the source under no-build static analysis; runtime/behaviour is the authoritative claim where noted.

---

## 0. TL;DR — the one-paragraph difference

Ovion Desktop wraps the **unmodified Penpot JVM backend + ClojureScript frontend** in a **Tauri v2 shell** that auto-boots bundled **PostgreSQL + Redis + a jlink JRE** on launch (backend on port 3449, same-origin proxy on 1420), injects desktop-only runtime flags into the SPA at build time, and **rebrands the user-facing surfaces Penpot → Oriole → Ovion** (name + logo + warm-coral theme). On top of that it adds **four net-new feature areas with no upstream equivalent**: (1) a **Google Fonts offline proxy**, (2) **client-side code export to 8 UI frameworks**, (3) a **closed AI design-generation layer** (CLJS bar → Tauri command → Rust → reqwest → OpenAI-compatible providers), and (4) **region-select / URL-clone AI flows**. It also runs a **Figma-parity program** that adds 75 of 78 missing Figma features at the schema + UI + i18n level (most renderers implemented, a few still deferred). When no AI provider/key is configured the app behaves byte-identically to base Penpot.

---

## 1. The architecture delta

| Layer | Base Penpot | Ovion Desktop |
|---|---|---|
| Distribution | Docker-compose / SaaS web app | Single **NSIS installer** per-user (no admin), bundles Postgres + Redis + jlink JRE + ImageMagick |
| Process shell | nginx + JVM + Postgres + Redis in containers | **Tauri v2** (Rust) shell; spawns Postgres/Redis/JVM as silent child processes (`CREATE_NO_WINDOW`), kills them on window close |
| Frontend serve | nginx-served SPA | **Same-origin Rust reverse proxy on :1420** (`proxy.rs`) that serves the SPA and proxies `/api /internal /ws /assets` to the JVM on :3449 |
| Auth | SaaS login / OIDC | **Auto-login** (release-only): proxy injects a session cookie into every proxied request; `penpotIsSaas=false` |
| Rendering | SVG (default) or WASM/Skia | **SVG only** (render-wasm disabled, no-op `render.js` stub) |
| AI | none | **Two-layer AI pipeline** (CLJS → Tauri → Rust → reqwest) |
| Brand | "Penpot" | **"Ovion Desktop"** (user-facing only; internal identifiers stay `penpot`) |
| Theme | Penpot's palette | Warm **coral** accent `#F28B82` (light + dark) on app chrome; AI surfaces pin reference coral `#f28b82` |
| Multiplayer / collaboration | full live collaboration | Single-user offline; presence/cursors exist but unused; **audio/cursor-chat deferred** (gap #67) |

**What is byte-identical to base Penpot:** the JVM uberjar (`penpot.jar`), all `PENPOT_*` env vars, the `penpot` DB role/database, the Clojure/ClojureScript workspace source, the Sente/WebSocket protocol, the SVG renderer, the filesystem storage backend, and the entire data schema. Internal code paths, identifiers, and the `penpot.jar` filename are unchanged.

**What diverges:** (a) Tauri shell + Rust same-origin proxy on :1420 replacing nginx; (b) auto-boot of Postgres/Redis/JVM + auto-login replacing docker-compose/SaaS auth; (c) injected `penpotFlags` (render-wasm disabled, demo-users/registration enabled, telemetry off, secure-session-cookies off) and `penpotIsSaas=false`; (d) user-facing rebrand to Ovion; (e) the four net-new features + figma-parity; (f) native bundling; (g) `loading.html` boot screen with `#boot-status`; (h) no-op `render.js` and render-wasm disabled (SVG renderer only).

---

## 2. Boot & service lifecycle (underneath)

**How it works underneath (`src-tauri/src/lib.rs`):**

1. App launches → `setup()` starts the same-origin Rust proxy on `:1420` and opens `loading.html` (branded, shows `#boot-status`).
2. `std::thread::spawn(boot_backend)` runs on a background thread:
   - `start_postgres` → `initdb` on first run, creates the `penpot` role + database, then `pg_ctl start` (null stdio so the long-lived postmaster grandchild can't deadlock `wait()`).
   - `start_redis` → `redis-server --bind 127.0.0.1` (loopback only → no firewall prompt).
   - `spawn_backend` → JVM uberjar on `http://localhost:3449` with `backend_env` (`PENPOT_PUBLIC_URI=http://localhost:1420`, `DATABASE_URI`, `REDIS_URI`, filesystem storage, `PENPOT_FLAGS=disable-secure-session-cookies disable-telemetry enable-demo-users enable-registration disable-feature-render-wasm ...`, `PENPOT_ALLOWED_ORIGINS` includes `localhost:1420/3449 + tauri.localhost`).
   - `wait_for_port(3449, 180s)` polls readiness.
   - On ready → `proxy::enable_auto_login()` (RPC login → register-profile → extract auth cookie into a `Mutex`) + emit `"backend-ready"` + `eval location.replace('/')`.
3. `WindowEvent::Destroyed` → `shutdown_services`: kills Redis child, JVM child, `pg_ctl stop fast`.

**Key gotchas (encoded in memory):**
- `silent()` sets `CREATE_NO_WINDOW` on every child — no cmd windows pop. Don't remove it.
- jlink JRE **must include `jdk.net`** or the backend dies building the Redis client.
- `bundle.resources` `**/*` globs flatten to basename → use bare dir paths to keep subdirs (postgres `share/timezonesets`, `jre/lib`).
- CSS-modules scoped-name generator splits on `/` only → broke styling on Windows (fixed + rebuild needed).
- CI must `clojure -T:build compile` `penpot-source/common` **before** the CLJS build or `ClassNotFoundException: app.common.UUIDv8`.
- Routing `valid-location` guard: rejects `tauri.localhost` vs public-uri `localhost:1420` → bypassed for non-SaaS.

---

## 3. The same-origin proxy (`proxy.rs`) — not a Tauri command

**Important:** `proxy.rs` exposes **no `#[tauri::command]`s**. The frontend reaches it via plain `fetch` / `WebSocket`, not `invoke`. It is a production HTTP/WS server on port 1420 that:

- Serves static SPA files and reverse-proxies `/api/ /internal/ /ws/ /assets/` to the JVM on 3449.
- **Feature 1**: intercepts `/internal/gfonts/css` + `/internal/gfonts/font/*` with an on-disk cache (see §5).
- Handles CORS preflight, **WebSocket upgrade relay** for the Sente channel (raw `io::copy`, no read timeout), **auto-login session injection** (the cookie from §2 is added to every proxied request + WS upgrade), **logout interception** (empty success), **`x-accel-redirect` asset serving** from the filesystem storage dir, and a `/__desktop_log` webview diagnostic channel.

---

## 4. Rebrand (Penpot → Ovion)

**What changed (user-facing only):** window title, loading screen, installer, start menu, SPA metadata → **"Ovion Desktop"**; the logo mark → Ovion logo; the accent palette → warm **coral `#F28B82`** (light + dark) via `colors.scss` + `color-defs.scss`; AI surfaces pin their own reference coral `#f28b82` / grey `#7d7d7d` / **Helvetica Now Display** (self-hosted at `/fonts/`).

**What did NOT change:** internal identifiers, file paths, env vars, the backend jar name, the DB role, code references — all stay `penpot` (`PENPOT_*`, `penpot.jar`, `resources/public`).

**Where to edit the brand:** `resources/public/` is **gitignored build output** — do NOT edit it. Edit instead:
- `penpot-source/frontend/resources/templates/index.mustache` (SPA `<title>`, meta).
- `data/assets/penpot-light.svg` (logo used by `loading.html`).
- `scripts/inject-desktop-config.js` (writes `loading.html` title + inlines the logo).
- `src-tauri/tauri.conf.json` (`productName`, `identifier`, `startMenuFolder`).
- `src/app/main/ui/ds/colors.scss` + `resources/styles/common/refactor/color-defs.scss` (coral theme).
- `translations/en.po`.

---

## 5. Feature 1 — Google Fonts offline proxy

**Why it exists:** the JVM backend has **no gfonts routes**, so the desktop app serves Google Fonts itself.

**How to use it:** nothing explicit for the normal path — when you pick a Google font in the font picker, the SPA fetches `internal/gfonts/css?<css2-query>` and font files from `internal/gfonts/font/*`; the proxy fetches from `fonts.googleapis.com` / `gstatic.com` over TLS and **caches them under the app-data fonts cache on first use**. For full offline use, click the **download icon** on a Google font row in the **Typography** menu (right sidebar, Design tab) → `fonts_download_family` Tauri command pre-caches the family's CSS2 + every variant + the menu-preview TTF.

**Underneath:**
- Online path (`fonts.cljs`): `load-font :google` → `generate-gfonts-url` → `fetch-gfont-css` → `process-gfont-css` (rewrites `fonts.gstatic.com` → proxy base) → `add-font-css!`.
- The font-picker row name renders **in its own typeface** via a tiny menu TTF `@font-face` (`ensure-gfont-preview!`), also served through the proxy.
- `fonts.rs::fonts_download_family` uses a shared `reqwest::blocking::Client` (safe because sync Tauri commands run on a worker thread). `slugify` **must stay byte-identical** to `proxy::slugify` or the offline pre-warm writes a CSS file the proxy's `/internal/gfonts/css` route won't find.

**Where it lives:** `src-tauri/src/proxy.rs` (routes + slugify), `src-tauri/src/fonts.rs` (download command), `penpot-source/frontend/src/app/main/fonts.cljs` + `fonts.clj`, `…/sidebar/options/menus/typography.cljs` (download icon + first Tauri invoke).

---

## 6. Feature 2 — Code export to UI frameworks

**How to use it:** open **Inspect** (right sidebar tab) → **Code** sub-panel → pick a framework from the dropdown → preview generated code live → **Copy** or click the **download button** to emit a full project ZIP via a native Save-As dialog (Tauri) / in-browser blob fallback (web/preview).

**Supported frameworks (10 markup options = HTML, SVG + 8 UI frameworks):** **React, Next.js, React Native, Android XML, WinUI 3 XAML, Flutter, Tailwind CSS, SwiftUI.** (Memory once said "7"; the actual `framework-types` list has 8 UI frameworks — SwiftUI was added later.)

**Underneath (entirely client-side for the preview string):**
- `app.util.code_gen.cljs` dispatches `generate-framework-code` / `generate-framework-project` to per-framework generators in `…/util/code_gen/frameworks/{react,tailwind,react_native,android_xml,winui3_xml,flutter,swift,common,components}.cljs`.
- A project map = `{:files {<path> <content>} :binary-assets [{:path :bytes}] :raster-requests [{:shape-id :name :scale}] :primary <path> :label …}`.
- Only **two** backend touches: raster PNG resolution (`resolve-rasters!` via the export RPC, for complex svg-shapes deferred to PNG) and the final ZIP write (`write_code_zip`).
- `write_code_zip` (`code_export.rs`): `ZipWriter` with Deflated compression; enforces exactly one of `content`/`binary` per entry; creates parent dir of the out path; nested in-archive paths (`src/App.jsx`, `assets/icon.png`) work directly.

**Gotcha:** `dm/fmt=cuerdas` ffmt has **NO** `%s/%d/{}` support (`%%` = literal `%`). Use `dm/str` concatenation — the codebase consistently does.

**Where it lives:** `…/ui/inspect/code.cljs` (panel + handle-download-code), `…/util/code_gen/` (generators), `…/data/exports/code.cljs` (`request-code-project-export`, `native-save-and-write!`), `src-tauri/src/code_export.rs`.

---

## 7. Feature 3 + 4 — The Ovion AI layer

This is the headline net-new subsystem. It is described in full here because it is also the subject of the separate AI audit.

### 7.1 What the AI can do today (the complete capability set)

The Ovion AI exposes exactly **four** user-facing capabilities:

1. **Generate a new design board** — type a prompt + optional image attachments + optional reference URL pasted into the prompt; pick Auto/Max mode + a Screen (frame-preset); click the coral send disc. Produces a DesignSpec preview; **Apply** commits it to the canvas as one undo transaction.
2. **Update the current selection in place** ("region update") — select shapes with the normal Move/Lasso tool, ensure **"Update only the selection"** is on (default on), send a prompt. The AI's output **replaces that selection in place** (selection bounds + a per-shape snippet are sent as context; on apply, the selection is deleted and the generated frames are translated to the selection's top-left origin).
3. **Rename a shape with AI** — in **AI Settings → Selection tools → Rename** (single shape selected). Crafts a ≤4-word name; result is **best-effort scraped out of a returned DesignSpec** (no plain-text response mode exists yet).
4. **Generate placeholder copy** — in **AI Settings → Selection tools → Generate text** (exactly one text shape selected). Generates one line of copy and **copies it to the clipboard**.

### 7.2 How it works underneath (the two-layer pipeline)

**Layer 1 — CLJS frontend (`ai_gen.cljs`, `design_gen.cljs`):**
- `ai-bar*` (`ai_bar.cljs`) calls `ai/generate-design` (a potok `WatchEvent`).
- `generate-design` captures a fresh `gen-id` (monotonic; stale-guard for cancelled/superseded runs), computes `selection-bounds` + `selection->snippet` only when `target=update-selection` and bounds are non-nil (the backend's `bounds` is non-Option, so a JSON null would fail deserialization), injects `:file-id` from state, `build-request` → `invoke-generate` → Tauri `llm_generate`.
- On resolve (guarded by `gen-id`) → `set-ai-preview {:spec :target}`. The bar's **Apply** button then emits `dg/apply-design-spec`.
- `apply-design-spec` (`design_gen.cljs`): `cds/check-design-spec` → `cds/spec->shape-tree` (invalid → warning toast, no partial commit) → `bake-interactions` → `translate-tree` (`gtr/move` on every shape, since spec `:x/:y` are page-absolute) → for `update-selection`: `pcb/remove-objects` the current selection first → `add-object` parent-first → attach prototype flows → `select-shapes` the new top-level frames → **one undo transaction**.
- State keys (all under `:workspace-local`): `:ai-busy`, `:ai-stage`, `:ai-preview`, `:ai-error`, `:ai-update-sel`.

**Layer 2 — Rust (`src-tauri/src/llm.rs`, 1704 lines):** `llm_generate(app, request: GenerateRequest)` orchestrates:
- `extract_urls()` → `fetch_firecrawl()` (if key) or `fetch_url_context()` + `download_url_images()`.
- Attach user files (images vs text) → append target/preset/selection/memory hints to `prompt_text`.
- Pick pipeline by quality mode:
  - **MAX**: if vision present → **Kimi** runs `SCOUT_PROMPT` (a design-language brief) → **GLM** runs `DRAW_SYSTEM_PROMPT` grounded in the brief (no images to GLM); else GLM draws directly.
  - **AUTO**: if vision → Kimi single combined call (`COMBINED_PROMPT_AUTO`) with images; else if complexity ≥ 3 GLM draws; else Kimi draws.
- `call_provider` dispatches to `call_deepinfra` / `call_ovion_cloud` / `call_ollama`.
- `extract_json` finds the **first balanced `{...}`** in the response string.
- Append-only memory appended for user + assistant turns (capped 24, default replay 6).
- Emits `ai-progress` Tauri events at each stage (`starting/fetching-url/scouting/generating/finalizing/done`).

### 7.3 Providers & models

| Provider | Endpoint | Auth | Body shape | Default models |
|---|---|---|---|---|
| **DeepInfra** (default, BYO-key) | `{deepinfra_base}/chat/completions` (default `https://api.deepinfra.com/v1/openai`) | `Bearer deepinfra_api_key` | `{model, messages:[{system,content},{user,content:[{text},{image_url}]}], response_format:{type:json_object}, temperature:0.7}` | GLM `zai-org/GLM-5.2`, Kimi `moonshotai/Kimi-K2.7-Code` |
| **Ovion Cloud** (subscription, gated "Coming soon") | `{ovion_cloud_endpoint}/chat/completions` (default `https://api.ovion.app/v1`) | `Bearer ovion_cloud_token` | same as DeepInfra | reuses the DeepInfra GLM/Kimi slugs |
| **Ollama** (local testing) | `{ollama_url}/api/chat` (default `127.0.0.1:11434`) | optional `Bearer ollama_api_key` | `{model, messages:[{system},{user,content,images:[b64]}], format:"json", stream:false}` | GLM `glm4`, Kimi `kimi-k2.7` |

**Two logical models (never named in UI):** **GLM** = the "drawing" model (structured JSON, no vision); **Kimi** = the "vision" model (multimodal). Provider is orthogonal to the GLM/Kimi orchestration.

### 7.4 Memory, config, cancellation

- **Per-file conversation memory**: append-only JSON at `<app-data>/ai-memory/<sanitized-file_id>.json`, capped 24 turns. No summarization model — the "assistant summary" is the spec JSON truncated to 4000 chars. The last `memory_max_turns` (default 6) turns are concatenated into `prompt_text` as a "Conversation so far" block. Cleared via `llm_clear_memory` (the **Clear memory** button in AI Settings).
- **Config**: `<app-data>/llm.json` (`LlmConfig`); first run seeds from a `.env.local`. `llm_get_config` returns a **masked** view (API keys → `*_set: bool` presence flags; model slugs + endpoints returned in clear). `llm_set_config` preserves existing keys when the panel sends blank strings (so saving never wipes a configured key).
- **Cancellation**: `llm_cancel` sets a **process-global `AtomicBool ABORT`**; `llm_generate` resets it at start and checks `check_aborted()` between stages. The HTTP request itself is **not** interruptible (no cancel token); on cancel the spec is returned but the frontend `gen-id` guard drops it and memory is not appended.

### 7.5 The five Tauri commands (the complete AI invoke surface)

`lib.rs` `generate_handler!` registers exactly **8 commands total**: `ping`, `llm_get_config`, `llm_set_config`, `llm_generate` (async), `llm_cancel`, `llm_clear_memory`, `fonts_download_family`, `write_code_zip`. **`llm_generate` is the single AI entry point.** There is no separate `llm_text` command (rename/text-gen reuse `llm_generate` + best-effort text scraping — a faithful plain-text mode is **deferred** under the no-build constraint).

### 7.6 The AI UI surfaces (reference-pinned)

Four files in `…/ui/workspace/`:
- **`ai_bar.cljs`** — bottom-centered dock. Primary row = `[cluster pill][input pill][Screen pill]`: cluster = `[mode pill | paperclip | settings]`; input pill = `[prompt textarea | coral send disc]`; **Screen pill on the RIGHT** (custom themed popover, **not** a native `<select>`; 8 presets: Auto/Mobile/Mobile small/Tablet/Web/Web wide/Desktop/Watch). **Mode pill icon is on the RIGHT**. Progress stage line + error line below; attachment thumbnails in the cluster; preview modal overlay (Regenerate/Cancel/Apply) when the backend returns a spec.
- **`ai_settings.cljs`** — modal opened from the bar's gear. Hosts: (top) the **Selection tools section** (the three controls relocated out of the bar — "Update only the selection" checkbox, Rename, Generate text); (below) the full LLM config (provider/mode/per-provider model slugs + keys + base URLs, Firecrawl, Ovion Cloud conditional section, memory, timeout).
- **`ai_design.cljs`** — shared design tokens (coral `#f28b82`, grey `#7d7d7d`, Helvetica Now Display `@font-face`, keyframes, reduced-motion guard), injected as a `<style>` block.
- **`ai_motion.cljs`** — GSAP + anime.js motion: **box-shadow-only** hover/press (`hov-*`/`press-*` white + coral faces), `pop-in` for the Screen popover. **NO size/scale animations anywhere** (explicit user ban). Reduced-motion non-negotiable.

**Visual identity:** the AI surfaces deliberately use the **reference-pinned coral `#f28b82`** (not the app theme's peach `#ff7a52`) and **Helvetica Now Display** so they match `UI_Reference/*.html` exactly in both light and dark app themes. Lucide icons, stroke-width 2, currentColor. Resting shadows are exact reference matches; hover/press shadows are new calm enhancements (a slightly deeper offset+blur and a touch stronger inset ring — the reference has no hover state).

### 7.7 The "magic line" / region-select — what it actually is

**There is no "magic line circle" tool anywhere in the codebase.** Grep for `magic-line`, `magic line`, `magicline`, `region-tool`, `region_select`, `ai-tool` across the workspace UI returns **nothing**. The top-toolbar tool list (move, scale, frame, rect/circle/polygon/star, text, note, image, path/curve, slice, lasso, brush, plugins, mcp) has **no AI tool**.

What the user calls "the magic line circle for updating a specific thing" maps to the **normal Penpot selection + the "Update only the selection" toggle**:
- Select one or more shapes (Move/Lasso).
- Ensure **"Update only the selection"** is on (default on; nil ⇒ true), shared between the bar and the AI Settings modal via `refs/ai-update-sel`.
- Send a prompt. The bar computes `target = "update-selection"` only when `has-sel? AND update-sel?`.
- The selection's bounds + a per-shape snippet (`{id,type,name,x,y,width,height,fills,content}`) go to the backend as `options.selection`.
- The model returns **one frame at 0,0**; the frontend translates it to the selection origin, **deletes the old selection**, and commits the new tree — a **whole-region regeneration**, **not** granular per-element operations.

### 7.8 URL-clone — what it actually is

**Not a literal clone.** The reference-URL input was **removed** from the bar; you paste URLs **directly into the prompt**. The backend's `extract_urls` parses them out, fetches/scouts via Firecrawl as a **design-language reference**, then **generates a NEW design inspired by it**. No HTML/DOM → Penpot-shape conversion happens.

### 7.9 The central gap (why a separate AI audit exists)

The AI layer has **NO function/tool calling**:
- Request bodies contain **no** `tools`/`functions`/`tool_choice` fields. The `OpenAiMessage` response struct has a single `content: String` field and **no `tool_calls` deserialization** — if a provider returned tool_calls they'd be silently dropped by serde.
- There is **no tool-use loop** (the only multi-call path is the max-quality Kimi-scout → GLM-draw sequence, two independent prompt→text completions).
- There is **no streaming** (no SSE; Ollama hardcodes `stream:false`; DeepInfra/Ovion omit it).
- The model **cannot call back into the frontend** — it only emits a DesignSpec JSON. It cannot read the live scene graph, cannot select/move/style individual shapes, cannot invoke any of the ~150 user capabilities. The AI can do **~4 of ~150** user actions.

The audit→fix→verify run (separate) closes this gap by adding a real tool-calling layer, live scene-graph access, code + visual screen understanding, and granular region targeting — natively, to compete with established design-AI tools.

---

## 8. Figma-parity program (78 gaps)

`Figma_Parity.md` ranks 78 Figma feature gaps (P0=8, P1=35, P2=35). Every gap was addressed under the **DONE-v1** pattern: **additive optional schema** in common types (defaults to nil so unmodified shapes render byte-identically), **guarded UI** mounted in the right sidebar / top toolbar / inspect panel / viewport, and **i18n keys** (tagged `figma-parity/...` in `en.po`) fully wired — with the underlying runtime/renderer **deferred** to a build-verified pass. A subsequent renderer-completion pass (task #25, commit `3e5a799`) then **implemented most deferred renderers** as additive guarded frontend-SVG code.

**Status: 75 DONE-v1 + 3 SCOPE-DEFERRED-v1** (out of scope for offline single-user desktop):
- **#38 branching/merging** (needs server-side branch storage + merge review).
- **#41 AI image editing** (remove background / erase / isolate / expand — needs a Rust image-pipeline backend).
- **#67 audio calls + cursor chat** (needs realtime multiplayer).

**Now genuinely rendering** (implemented in the renderer pass): mesh gradient (#21), glass (#61), noise (#62), texture (#63), shader SVG presets (#64), stacked blur (#74), 3D transform (#66), brush stamp (#52), variable-width stroke (#53), pixel-preview (#46), per-shape outline-stroke fallback (#45), text-SVG fields (#17/#18/#78/#12/#13/#50), spell-check (#48), multi-text edit (#49).

**Still genuinely deferred** (need a real build/WebGPU): arbitrary shader presets (only SVG-filter presets implemented), the per-shape outline-stroke fallback edge.

**Where the gaps surface:** right sidebar **Design tab** = bulk (constraints, layout_container, layout_item, stroke, measures, component, interactions, text, typography, fill, blur, shadow, border_radius, exports, glass_row/noise_row/texture_row + rows/*); **Inspect tab** = dev-mode/a11y/code-export; **top toolbar** = tools (slice, smart-selection, scale, lasso, brush, polygon, star, arc); **main menu + command palette** = view toggles (outline mode, pixel preview) + quick actions; **viewport** = in-canvas handles/renderers.

**Toolbar tools Ovion adds over base Penpot:** Scale, Polygon, Star, Sticky-note, Lasso, Brush (some DEFERRED: Scale = no drag-scale handler, Lasso = selection mode only, Brush = renderer deferred).

---

## 9. Native bundling specifics

- **NSIS installer**, `installMode=currentUser` (no admin); `installerIcon=icons/icon.ico` (a real icon generated by `scripts/generate-icons.mjs` from branding — used by `.exe` + installer).
- **Bundles:** `penpot.jar`, built SPA, full PostgreSQL + Redis distributions, a **jlink-custom JRE** (must include `jdk.net`), **ImageMagick** (for thumbnails + fixes a Windows `posix:permissions` NIO upload error; prepended to `PATH` at runtime).
- **Redis binds `127.0.0.1` only** (no firewall prompt). All child processes get `CREATE_NO_WINDOW`.
- **Config locations:** LLM config `<OS app-data>/llm.json`; `.env.local` seeds first-run; gfonts cache under OS app-data (survives upgrades); app-writable data under `<root>/data/{assets,postgres,redis,backend.log,fonts}`.

---

## 10. Quick reference — where everything lives

| Concern | File(s) |
|---|---|
| Tauri shell / boot / shutdown | `src-tauri/src/lib.rs` |
| Same-origin proxy | `src-tauri/src/proxy.rs` |
| AI backend | `src-tauri/src/llm.rs` |
| Tauri commands (path helpers, ping) | `src-tauri/src/commands.rs` |
| Fonts proxy command | `src-tauri/src/fonts.rs` |
| Code export ZIP command | `src-tauri/src/code_export.rs` |
| Desktop config injection | `scripts/inject-desktop-config.js` |
| Icon generation | `scripts/generate-icons.mjs` |
| AI frontend data layer | `…/data/workspace/ai_gen.cljs`, `…/data/workspace/design_gen.cljs` |
| AI UI surfaces | `…/ui/workspace/ai_bar.cljs`, `ai_settings.cljs`, `ai_design.cljs`, `ai_motion.cljs` |
| Code-gen frameworks | `…/util/code_gen/frameworks/*.cljs` |
| Figma-parity menus | `…/ui/workspace/sidebar/options/menus/*.cljs` + `…/rows/*.cljs` |
| Figma-parity renderers | `…/ui/shapes/{gradients,fills,filters,custom_stroke,path}.cljs`, `…/viewport/pixel_preview.cljs` |
| i18n (central) | `penpot-source/frontend/translations/en.po` |
| Brand / theme | `…/resources/templates/index.mustache`, `data/assets/penpot-light.svg`, `…/ui/ds/colors.scss`, `…/resources/styles/common/refactor/color-defs.scss` |
| Figma parity catalogue | `Figma_Parity.md` |

---

## 11. What stays the same (so you don't accidentally "fix" it)

- The `penpot.jar` backend, the schema, the Sente WS protocol, the SVG renderer core, the filesystem storage backend — all upstream Penpot. Do not add gfonts handling to the Penpot backend (the desktop app intentionally bypasses it).
- Internal identifiers / env vars / paths stay `penpot`.
- When no AI provider/key is configured, the app is functionally base Penpot (Ovion Cloud transport code is unreachable without a token; `default_provider=deepinfra`).