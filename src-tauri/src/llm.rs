// Penpot Desktop — closed AI layer (Foundation F4 + Feature 3 backend).
//
// The user only ever sees the input bar (Feature 3) and the final result on the
// canvas. Everything in this module is an implementation detail: the models,
// the prompts, the URL fetching, the provider switching, the conversation
// memory. The frontend calls `llm_generate` with `{prompt, files, options}`
// and receives a DesignSpec JSON (validated + applied CLJS-side by Foundation
// F3 `apply-design-spec`); it never sees API keys, model slugs, or the system
// prompt.
//
// ── Dual-model orchestration (GLM 5.2 + Kimi K2.7 code) ──────────────────────
//
// Two underlying models, never named in the UI:
//
//   • GLM 5.2 — the "drawing" model. Excellent at structured JSON emission and
//     tool-style reasoning, but it CANNOT see images. It owns DesignSpec
//     generation (the actual drawing) and complex reasoning.
//   • Kimi K2.7 code — the "vision" model. Multimodal; it sees images, website
//     screenshots, and reference attachments. It owns the "scout" step
//     (extracting a real design-language brief from reference visuals) and
//     cost-efficient single-shot generation.
//
// Two USER-FACING quality modes (the only thing the user picks):
//
//   • "max"  (Max quality) — GLM draws; Kimi provides vision. Pipeline:
//        - if reference images/URL exist: Kimi scouts → design-language brief
//          (JSON), then GLM draws the DesignSpec grounded in that brief.
//        - if no reference: GLM draws directly (it infers the design language
//          from the prompt).
//   • "auto" (Auto) — mostly Kimi for token/cost efficiency; GLM is only used
//     for complex asks (prototypes, dashboards, multi-screen, long prompts)
//     where its structured-drawing strength is worth the cost.
//        - if reference images/URL exist: Kimi does a single combined call
//          (describe the references, then emit the DesignSpec).
//        - if no reference, simple: Kimi draws.
//        - if no reference, complex: GLM draws.
//
// ── Providers (transport only) ──────────────────────────────────────────────
//
//   • DeepInfra — production. OpenAI-compatible chat-completions; serves both
//     GLM and Kimi model slugs (configurable). Bearer auth.
//   • Ollama — local testing (`{ollama_url}/api/chat`, `images:[b64,…]` for
//     multimodal). No key required (an optional key is sent as Bearer for
//     hosted Ollama-compatible gateways and ignored by local Ollama).
//
// The `provider` switch selects transport; the `mode` switch selects the
// GLM/Kimi orchestration above. They are orthogonal.
//
// ── Seeding from .env.local ──────────────────────────────────────────────────
//
// On first run (no `llm.json`), config is seeded from a `.env.local` file in
// the current working directory (dev) so the keys the developer already
// placed there are used automatically: OLLAMA_KEY, DEEPINFRA_KEY,
// FIRECRAWL_API_KEY, OLLAMA_URL, OLLAMA_GLM_MODEL, OLLAMA_KIMI_MODEL,
// DEEPINFRA_GLM_MODEL, DEEPINFRA_KIMI_MODEL, AI_PROVIDER, AI_MODE. In a
// packaged install `.env.local` is absent, so the user configures via the
// Settings panel (`llm_set_config`), which writes `llm.json`.
//
// ── URL reference (the AI "sees" the site) ───────────────────────────────────
//
// If the prompt contains a URL, the shell visits it FIRST (Feature 3 Phase 3):
// it extracts visible text + inline CSS + `<img src>`/`<link href>` URLs, AND
// downloads up to N referenced images to pass them as VISION INPUTS to Kimi,
// so the model actually sees the site's imagery (logos, heroes, UI shots) and
// grounds the design in it rather than guessing. If a Firecrawl key is
// present, a real rendered screenshot is fetched instead (higher fidelity).
//
// ── Conversation memory ──────────────────────────────────────────────────────
//
// A per-file append-only memory (`<app-data>/ai-memory/<file-id>.json`) keeps
// the last N turns (prompt + a compact spec summary) so the AI has context and
// "understanding" across generations within a file. Cleared via
// `llm_clear_memory`.

use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

use serde::{Deserialize, Serialize};
use tauri::{AppHandle, Emitter};

use crate::commands::llm_config_path;

// ── Provider config ─────────────────────────────────────────────────────────

/// Persisted to `<app-data>/llm.json`. Keys NEVER cross to the frontend —
/// `llm_get_config` returns a masked view (`*_set: bool`).
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct LlmConfig {
    /// Transport: "deepinfra" (production) or "ollama" (local testing).
    #[serde(default = "default_provider")]
    pub provider: String,

    /// User-facing quality mode: "max" or "auto". Models are never named.
    #[serde(default = "default_mode")]
    pub mode: String,

    // ── DeepInfra (production transport) ──
    #[serde(default)]
    pub deepinfra_api_key: String,

    #[serde(default = "default_deepinfra_base")]
    pub deepinfra_base: String,

    /// GLM slug on DeepInfra (the "drawing" model).
    #[serde(default = "default_deepinfra_glm")]
    pub deepinfra_glm_model: String,

    /// Kimi slug on DeepInfra (the "vision" model).
    #[serde(default = "default_deepinfra_kimi")]
    pub deepinfra_kimi_model: String,

    // ── Ollama (local testing transport) ──
    #[serde(default = "default_ollama_url")]
    pub ollama_url: String,

    /// Optional Bearer key for hosted Ollama-compatible gateways; local Ollama
    /// ignores it.
    #[serde(default)]
    pub ollama_api_key: String,

    #[serde(default = "default_ollama_glm")]
    pub ollama_glm_model: String,

    #[serde(default = "default_ollama_kimi")]
    pub ollama_kimi_model: String,

    // ── Firecrawl (optional, URL → rendered screenshot) ──
    #[serde(default)]
    pub firecrawl_api_key: String,

    #[serde(default = "default_firecrawl_base")]
    pub firecrawl_base: String,

    /// Per-request timeout in seconds.
    #[serde(default = "default_timeout")]
    pub timeout_secs: u64,

    // ── Conversation memory ──
    #[serde(default = "default_memory_enabled")]
    pub memory_enabled: bool,

    #[serde(default = "default_memory_turns")]
    pub memory_max_turns: usize,
}

fn default_provider() -> String { "deepinfra".into() }
fn default_mode() -> String { "auto".into() }
fn default_deepinfra_base() -> String { "https://api.deepinfra.com/v1/openai".into() }
fn default_deepinfra_glm() -> String { "zai-org/GLM-5.2".into() }
fn default_deepinfra_kimi() -> String { "moonshotai/Kimi-K2.7-Code".into() }
fn default_ollama_url() -> String { "http://127.0.0.1:11434".into() }
fn default_ollama_glm() -> String { "glm4".into() }
fn default_ollama_kimi() -> String { "kimi-k2.7".into() }
fn default_firecrawl_base() -> String { "https://api.firecrawl.dev".into() }
fn default_timeout() -> u64 { 240 }
fn default_memory_enabled() -> bool { true }
fn default_memory_turns() -> usize { 6 }

impl Default for LlmConfig {
    fn default() -> Self {
        Self {
            provider: default_provider(),
            mode: default_mode(),
            deepinfra_api_key: String::new(),
            deepinfra_base: default_deepinfra_base(),
            deepinfra_glm_model: default_deepinfra_glm(),
            deepinfra_kimi_model: default_deepinfra_kimi(),
            ollama_url: default_ollama_url(),
            ollama_api_key: String::new(),
            ollama_glm_model: default_ollama_glm(),
            ollama_kimi_model: default_ollama_kimi(),
            firecrawl_api_key: String::new(),
            firecrawl_base: default_firecrawl_base(),
            timeout_secs: default_timeout(),
            memory_enabled: default_memory_enabled(),
            memory_max_turns: default_memory_turns(),
        }
    }
}

/// What `llm_get_config` returns — keys masked to presence flags. Model slugs
/// ARE returned (they're not secret) so the Settings panel can show/edit them;
/// API keys are masked.
#[derive(Serialize)]
struct LlmConfigView {
    provider: String,
    mode: String,
    deepinfra_base: String,
    deepinfra_glm_model: String,
    deepinfra_kimi_model: String,
    deepinfra_api_key_set: bool,
    ollama_url: String,
    ollama_glm_model: String,
    ollama_kimi_model: String,
    ollama_api_key_set: bool,
    firecrawl_api_key_set: bool,
    firecrawl_base: String,
    timeout_secs: u64,
    memory_enabled: bool,
    memory_max_turns: usize,
}

fn load_config(app: &AppHandle) -> LlmConfig {
    match llm_config_path(app) {
        Ok(path) if path.exists() => {
            let raw = std::fs::read_to_string(&path).unwrap_or_default();
            serde_json::from_str(&raw).unwrap_or_default()
        }
        Ok(path) => {
            // First run — seed from .env.local (dev) if present, then persist.
            let seeded = seed_from_env_file().unwrap_or_default();
            if let Err(e) = save_config(app, &seeded) {
                eprintln!("[llm] could not persist seeded config: {e}");
            } else {
                let _ = path; // touched
            }
            seeded
        }
        Err(_) => seed_from_env_file().unwrap_or_default(),
    }
}

fn save_config(app: &AppHandle, cfg: &LlmConfig) -> Result<(), String> {
    let path = llm_config_path(app)?;
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)
            .map_err(|e| format!("could not create config dir: {e}"))?;
    }
    let json = serde_json::to_string_pretty(cfg)
        .map_err(|e| format!("could not serialize llm config: {e}"))?;
    std::fs::write(&path, json)
        .map_err(|e| format!("could not write llm config: {e}"))?;
    Ok(())
}

// ── .env.local seeding (dev) ────────────────────────────────────────────────

/// Parse a simple KEY=VALUE `.env` file into a map. Strips `export `, surrounding
/// quotes, and `#` comment lines. Tolerates missing file.
fn parse_env_file(path: &std::path::Path) -> Option<std::collections::HashMap<String, String>> {
    let raw = std::fs::read_to_string(path).ok()?;
    let mut out = std::collections::HashMap::new();
    for line in raw.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let line = line.trim_start_matches("export ");
        if let Some((k, v)) = line.split_once('=') {
            let k = k.trim().to_string();
            let mut v = v.trim().to_string();
            // Strip inline comment not inside quotes (best-effort).
            if !v.starts_with('"') && !v.starts_with('\'') {
                if let Some(idx) = v.find(" #") {
                    v = v[..idx].trim_end().to_string();
                }
            }
            // Strip surrounding quotes.
            if (v.starts_with('"') && v.ends_with('"'))
                || (v.starts_with('\'') && v.ends_with('\''))
            {
                if v.len() >= 2 {
                    v = v[1..v.len() - 1].to_string();
                }
            }
            if !k.is_empty() {
                out.insert(k, v);
            }
        }
    }
    Some(out)
}

/// Look for `.env.local` in cwd and the first few parent dirs (dev repo root).
fn find_env_local() -> Option<std::collections::HashMap<String, String>> {
    let mut dir = std::env::current_dir().ok()?;
    for _ in 0..6 {
        let candidate = dir.join(".env.local");
        if candidate.exists() {
            return parse_env_file(&candidate);
        }
        if !dir.pop() {
            break;
        }
    }
    None
}

/// Build a `LlmConfig` seeded from `.env.local` env vars (falling back to Rust
/// defaults). Used only on first run when no `llm.json` exists yet.
fn seed_from_env_file() -> Option<LlmConfig> {
    let env = find_env_local()?;
    let mut cfg = LlmConfig::default();

    if let Some(v) = env.get("DEEPINFRA_KEY") { cfg.deepinfra_api_key = v.clone(); }
    if let Some(v) = env.get("OLLAMA_KEY") { cfg.ollama_api_key = v.clone(); }
    if let Some(v) = env.get("FIRECRAWL_API_KEY") { cfg.firecrawl_api_key = v.clone(); }

    if let Some(v) = env.get("OLLAMA_URL") { if !v.is_empty() { cfg.ollama_url = v.clone(); } }
    if let Some(v) = env.get("DEEPINFRA_BASE") { if !v.is_empty() { cfg.deepinfra_base = v.clone(); } }
    if let Some(v) = env.get("DEEPINFRA_GLM_MODEL") { if !v.is_empty() { cfg.deepinfra_glm_model = v.clone(); } }
    if let Some(v) = env.get("DEEPINFRA_KIMI_MODEL") { if !v.is_empty() { cfg.deepinfra_kimi_model = v.clone(); } }
    if let Some(v) = env.get("OLLAMA_GLM_MODEL") { if !v.is_empty() { cfg.ollama_glm_model = v.clone(); } }
    if let Some(v) = env.get("OLLAMA_KIMI_MODEL") { if !v.is_empty() { cfg.ollama_kimi_model = v.clone(); } }

    if let Some(v) = env.get("AI_PROVIDER") {
        match v.as_str() {
            "ollama" | "deepinfra" => cfg.provider = v.clone(),
            _ => {}
        }
    } else {
        // Infer: if only Ollama has a key, prefer it (dev testing); if
        // DeepInfra has a key, prefer it (production). Default deepinfra.
        let has_di = !cfg.deepinfra_api_key.trim().is_empty();
        let has_oll = !cfg.ollama_api_key.trim().is_empty();
        if !has_di && has_oll {
            cfg.provider = "ollama".into();
        }
    }

    if let Some(v) = env.get("AI_MODE") {
        match v.as_str() {
            "max" | "auto" => cfg.mode = v.clone(),
            _ => {}
        }
    }

    Some(cfg)
}

// ── Memory ──────────────────────────────────────────────────────────────────

fn memory_dir(app: &AppHandle) -> Option<std::path::PathBuf> {
    let cfg_path = llm_config_path(app).ok()?;
    let parent = cfg_path.parent()?.to_path_buf();
    Some(parent.join("ai-memory"))
}

fn sanitize_id(id: &str) -> String {
    let s: String = id.chars().filter(|c| c.is_ascii_alphanumeric() || *c == '-' || *c == '_').collect();
    if s.is_empty() { "default".into() } else { s }
}

#[derive(Clone, Serialize, Deserialize)]
struct MemoryTurn {
    role: String,         // "user" | "assistant"
    content: String,      // user prompt OR assistant spec-summary
    ts: u64,             // unix seconds (caller-supplied via args if needed; else 0)
    target: Option<String>,
}

#[derive(Default, Serialize, Deserialize)]
struct MemoryFile {
    turns: Vec<MemoryTurn>,
}

fn load_memory(app: &AppHandle, file_id: &str) -> MemoryFile {
    let dir = match memory_dir(app) {
        Some(d) => d,
        None => return MemoryFile::default(),
    };
    let path = dir.join(format!("{}.json", sanitize_id(file_id)));
    if !path.exists() {
        return MemoryFile::default();
    }
    let raw = std::fs::read_to_string(&path).unwrap_or_default();
    serde_json::from_str(&raw).unwrap_or_default()
}

fn append_memory(app: &AppHandle, file_id: &str, turn: MemoryTurn) {
    let dir = match memory_dir(app) {
        Some(d) => d,
        None => return,
    };
    if let Err(e) = std::fs::create_dir_all(&dir) {
        eprintln!("[llm] could not create memory dir: {e}");
        return;
    }
    let path = dir.join(format!("{}.json", sanitize_id(file_id)));
    let mut mem = load_memory(app, file_id);
    mem.turns.push(turn);
    // Cap turns to 2*max for a rolling window.
    let cap = 24;
    if mem.turns.len() > cap {
        let drop = mem.turns.len() - cap;
        mem.turns.drain(0..drop);
    }
    if let Ok(json) = serde_json::to_string_pretty(&mem) {
        let _ = std::fs::write(&path, json);
    }
}

fn clear_memory(app: &AppHandle, file_id: &str) -> Result<(), String> {
    let dir = memory_dir(app).ok_or_else(|| "no app-data dir".to_string())?;
    let path = dir.join(format!("{}.json", sanitize_id(file_id)));
    if path.exists() {
        std::fs::remove_file(&path).map_err(|e| format!("could not remove memory: {e}"))?;
    }
    Ok(())
}

/// Render the last N turns as a compact conversation transcript for the model.
fn memory_transcript(mem: &MemoryFile, max_turns: usize) -> String {
    if mem.turns.is_empty() {
        return String::new();
    }
    let start = mem.turns.len().saturating_sub(max_turns);
    let slice = &mem.turns[start..];
    let mut out = String::from("--- Conversation so far (for context; honor prior decisions) ---\n");
    for t in slice {
        out.push_str(match t.role.as_str() {
            "user" => "User: ",
            _ => "Assistant: ",
        });
        out.push_str(&t.content);
        out.push('\n');
    }
    out
}

// ── Generate request ────────────────────────────────────────────────────────

#[derive(Debug, Deserialize)]
struct FileInput {
    name: String,
    #[serde(default)]
    mime: String,
    /// Optional absolute path the frontend resolved via the Tauri dialog/fs
    /// plugin. If present we read bytes from disk (preferred over base64).
    #[serde(default)]
    path: Option<String>,
    /// Inline base64 (data already in the message). Used when the frontend
    /// doesn't have a durable path (e.g. pasted screenshot).
    #[serde(default)]
    base64: Option<String>,
}

#[derive(Debug, Default, Deserialize)]
struct Rectf {
    #[serde(default)] x: f64,
    #[serde(default)] y: f64,
    #[serde(default)] width: f64,
    #[serde(default)] height: f64,
}

#[derive(Debug, Default, Deserialize)]
struct SelectionContext {
    #[serde(default)] bounds: Rectf,
    /// Serialized DesignSpec snippet of the selected subtree (JSON value).
    #[serde(default)] shapes: serde_json::Value,
    /// Parent frame id (so the frontend can place the update in context).
    #[serde(default)] parent_id: Option<String>,
    #[serde(default)] frame_id: Option<String>,
    #[serde(default)] page_id: Option<String>,
}

#[derive(Debug, Default, Deserialize)]
struct GenerateOptions {
    /// "design" | "prototype" | "design+prototype" (default auto from prompt).
    #[serde(default)]
    mode: Option<String>,
    /// "current-page" | "new-page" | "new-board" | "update-selection".
    #[serde(default)]
    target: Option<String>,
    /// Override the user-facing quality mode for this call: "max" | "auto".
    #[serde(default)]
    quality: Option<String>,
    /// File id for conversation memory.
    #[serde(default)]
    file_id: Option<String>,
    /// Explicit per-call memory toggle (else `cfg.memory_enabled`).
    #[serde(default)]
    use_memory: Option<bool>,
    /// Frame preset: "mobile" | "mobile-sm" | "tablet" | "web" | "web-wide"
    /// | "desktop" | "watch" | "auto".
    #[serde(default)]
    frame_preset: Option<String>,
    #[serde(default)] frame_width: Option<f64>,
    #[serde(default)] frame_height: Option<f64>,
    /// Selection context for `target: "update-selection"` (Feature 4).
    #[serde(default)]
    selection: Option<SelectionContext>,
}

#[derive(Debug, Deserialize)]
struct GenerateRequest {
    prompt: String,
    #[serde(default)]
    files: Vec<FileInput>,
    #[serde(default)]
    options: GenerateOptions,
}

// ── Progress events to the frontend ─────────────────────────────────────────

#[derive(Clone, Serialize)]
struct ProgressPayload {
    stage: String,
    detail: String,
}

fn emit_progress(app: &AppHandle, stage: &str, detail: &str) {
    let _ = app.emit(
        "ai-progress",
        ProgressPayload { stage: stage.into(), detail: detail.into() },
    );
}

// ── Cancellation ────────────────────────────────────────────────────────────

static ABORT: AtomicBool = AtomicBool::new(false);

fn check_aborted() -> Result<(), String> {
    if ABORT.load(Ordering::Relaxed) {
        Err("cancelled".into())
    } else {
        Ok(())
    }
}

// ── URL reference fetching (Feature 3 Phase 3) ──────────────────────────────

fn extract_urls(prompt: &str) -> Vec<String> {
    let bytes = prompt.as_bytes();
    let mut out = Vec::new();
    let mut i = 0;
    while i < bytes.len() {
        let rest = &prompt[i..];
        let hit = rest.find("http://").or_else(|| rest.find("https://"));
        match hit {
            Some(idx) => {
                let start = i + idx;
                let mut end = start;
                while end < bytes.len() {
                    let c = bytes[end];
                    if c == b' ' || c == b'\t' || c == b'\n' || c == b'\r'
                        || c == b'"' || c == b'\'' || c == b'`' || c == b'<' || c == b'>'
                    {
                        break;
                    }
                    end += 1;
                }
                let s = prompt[start..end]
                    .trim_end_matches(&[',', '.', ')', ';', '!', '?'][..])
                    .to_string();
                if !out.contains(&s) {
                    out.push(s);
                }
                i = end;
            }
            None => break,
        }
    }
    out
}

async fn fetch_url_context(client: &reqwest::Client, url: &str) -> Option<String> {
    let resp = client.get(url).send().await.ok()?;
    let ct = resp
        .headers()
        .get(reqwest::header::CONTENT_TYPE)
        .and_then(|v| v.to_str().ok())
        .unwrap_or("")
        .to_string();
    let body = resp.text().await.ok()?;
    let is_html = ct.contains("text/html")
        || body.trim_start().to_lowercase().starts_with("<!doctype")
        || body.trim_start().to_lowercase().starts_with("<html");

    if !is_html {
        let capped = body.chars().take(8_000).collect::<String>();
        return Some(format!("--- URL: {url} (content-type: {ct}) ---\n{capped}"));
    }

    let style: Vec<&str> = extract_blocks(&body, "<style", "</style>");
    let imgs = extract_attrs(&body, "<img", "src");
    let links = extract_attrs(&body, "<link", "href");

    let text = body.replace("<script", "<div").replace("</script>", "</div>");
    let mut in_tag = false;
    let mut visible = String::new();
    for c in text.chars() {
        match c {
            '<' => in_tag = true,
            '>' => in_tag = false,
            _ if !in_tag => visible.push(c),
            _ => {}
        }
    }
    let visible: String = visible
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
        .chars()
        .take(12_000)
        .collect();

    let mut out = format!("--- URL: {url} ---\n[visible text]\n{visible}\n");
    if !imgs.is_empty() {
        out.push_str("[img sources]\n");
        for u in imgs.iter().take(40) {
            out.push_str(u);
            out.push('\n');
        }
    }
    if !links.is_empty() {
        out.push_str("[link hrefs]\n");
        for u in links.iter().take(40) {
            out.push_str(u);
            out.push('\n');
        }
    }
    if !style.is_empty() {
        out.push_str("[inline CSS]\n");
        for s in style.iter().take(8) {
            out.push_str(s);
            out.push('\n');
        }
    }
    Some(out)
}

fn extract_blocks<'a>(html: &'a str, open: &str, close: &str) -> Vec<&'a str> {
    let mut out = Vec::new();
    let mut rest = html;
    while let Some(i) = rest.to_lowercase().find(&open.to_lowercase()) {
        let after = &rest[i..];
        if let Some(end) = after.to_lowercase().find(&close.to_lowercase()) {
            out.push(&after[..end + close.len()]);
            rest = &after[end + close.len()..];
        } else {
            break;
        }
    }
    out
}

fn extract_attrs(html: &str, tag: &str, attr: &str) -> Vec<String> {
    let needle = format!("{attr}=");
    let mut out = Vec::new();
    let mut rest = html;
    while let Some(i) = rest.to_lowercase().find(&tag.to_lowercase()) {
        let chunk = &rest[i..];
        let tag_end = chunk.find('>').unwrap_or(chunk.len());
        let tag_slice = &chunk[..tag_end.min(2048)];
        if let Some(j) = tag_slice.to_lowercase().find(&needle) {
            let after = &tag_slice[j + needle.len()..];
            let val = take_attr_value(after);
            if !val.is_empty() {
                out.push(val);
            }
        }
        rest = &chunk[tag_end..];
    }
    out
}

fn take_attr_value(s: &str) -> String {
    let s = s.trim_start();
    let bytes = s.as_bytes();
    if bytes.is_empty() {
        return String::new();
    }
    let (quote, body) = match bytes[0] {
        b'"' => ('"', &s[1..]),
        b'\'' => ('\'', &s[1..]),
        _ => (' ', s),
    };
    let end = if quote == ' ' {
        body.find(|c: char| c.is_whitespace()).unwrap_or(body.len())
    } else {
        body.find(quote).unwrap_or(body.len())
    };
    body[..end].to_string()
}

/// Resolve a possibly-relative URL against a page URL.
fn resolve_url(page_url: &str, href: &str) -> Option<String> {
    let href = href.trim();
    if href.is_empty() || href.starts_with("data:") || href.starts_with("javascript:") {
        return None;
    }
    if href.starts_with("http://") || href.starts_with("https://") {
        return Some(href.to_string());
    }
    if href.starts_with("//") {
        let scheme = page_url.split("://").next().unwrap_or("https");
        return Some(format!("{scheme}:{href}"));
    }
    // Absolute path → origin.
    let base = page_url.find("://").and_then(|i| {
        let after = &page_url[i + 3..];
        let end = after.find('/').unwrap_or(after.len());
        Some(&page_url[..i + 3 + end])
    })?;
    if href.starts_with('/') {
        return Some(format!("{base}{href}"));
    }
    // Relative → directory of page URL.
    let dir = page_url.rfind('/').map(|i| &page_url[..i + 1]).unwrap_or(base);
    Some(format!("{dir}{href}"))
}

/// Download up to `max` images referenced by a page, base64-encode them, and
/// return them as vision inputs. Skips SVGs (Kimi vision expects raster) and
/// caps per-image + total bytes.
async fn download_url_images(
    client: &reqwest::Client,
    page_url: &str,
    img_urls: &[String],
    max: usize,
) -> Vec<ImageInput> {
    let mut out = Vec::new();
    let mut total_bytes = 0usize;
    let max_total = 6 * 1024 * 1024; // 6 MB cap
    for raw in img_urls.iter().take(60) {
        if out.len() >= max {
            break;
        }
        let url = match resolve_url(page_url, raw) {
            Some(u) => u,
            None => continue,
        };
        if url.ends_with(".svg") || url.contains(".svg") {
            continue;
        }
        let resp = match client.get(&url).send().await {
            Ok(r) => r,
            Err(_) => continue,
        };
        let ct = resp
            .headers()
            .get(reqwest::header::CONTENT_TYPE)
            .and_then(|v| v.to_str().ok())
            .unwrap_or("")
            .to_string();
        if !ct.starts_with("image/") {
            continue;
        }
        let bytes = match resp.bytes().await {
            Ok(b) => b.to_vec(),
            Err(_) => continue,
        };
        if bytes.is_empty() || bytes.len() > 2_500_000 {
            continue;
        }
        if total_bytes + bytes.len() > max_total {
            break;
        }
        total_bytes += bytes.len();
        out.push(ImageInput { mime: ct, b64: base64_encode(&bytes) });
    }
    out
}

/// Fetch a rendered screenshot of `url` via Firecrawl (if a key is set). The
/// Firecrawl `/v1/scrape` endpoint with `formats: ["markdown","screenshot"]`
/// returns `data.screenshot` as a URL or base64. Returns the screenshot image
/// bytes + the markdown text.
async fn fetch_firecrawl(
    client: &reqwest::Client,
    cfg: &LlmConfig,
    url: &str,
) -> Option<(ImageInput, String)> {
    let key = cfg.firecrawl_api_key.trim();
    if key.is_empty() {
        return None;
    }
    let endpoint = format!("{}/v1/scrape", cfg.firecrawl_base.trim_end_matches('/'));
    let body = serde_json::json!({
        "url": url,
        "formats": ["markdown", "screenshot"],
        "onlyMainContent": false
    });
    let resp = client
        .post(&endpoint)
        .bearer_auth(key)
        .json(&body)
        .send()
        .await
        .ok()?;
    if !resp.status().is_success() {
        return None;
    }
    let val: serde_json::Value = resp.json().await.ok()?;
    let data = val.get("data")?;
    let markdown = data
        .get("markdown")
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .chars()
        .take(12_000)
        .collect::<String>();
    let screenshot = data.get("screenshot").and_then(|v| v.as_str())?;
    if screenshot.is_empty() {
        return None;
    }
    // Firecrawl returns screenshot as a URL (https://...) or a data URI
    // (data:image/png;base64,...). Download URL form, pass data URI through.
    if screenshot.starts_with("data:") {
        let (mime, b64) = parse_data_uri(screenshot)?;
        Some((ImageInput { mime, b64 }, markdown))
    } else {
        let r = client.get(screenshot).send().await.ok()?;
        let ct = r
            .headers()
            .get(reqwest::header::CONTENT_TYPE)
            .and_then(|v| v.to_str().ok())
            .unwrap_or("image/png")
            .to_string();
        let bytes = r.bytes().await.ok()?.to_vec();
        if bytes.is_empty() || bytes.len() > 4_000_000 {
            return None;
        }
        Some((ImageInput { mime: ct, b64: base64_encode(&bytes) }, markdown))
    }
}

fn parse_data_uri(uri: &str) -> Option<(String, String)> {
    // data:<mime>;base64,<b64>
    let after = uri.strip_prefix("data:")?;
    let (head, b64) = after.split_once(',')?;
    let mime = head.split(';').next()?.to_string();
    Some((mime, b64.to_string()))
}

// ── System prompts ───────────────────────────────────────────────────────────
//
// The DesignSpec JSON shape is owned CLJS-side (Foundation F3
// `app.common.types.design-spec`). The model is taught the same shape here so
// its JSON parses cleanly into F3's Malli schema. Keeping the canonical schema
// in CLJS means one source of truth; these prompts are a faithful prose mirror.

const DESIGN_SPEC_SHAPE: &str = r#"DesignSpec JSON shape:
{
  "target": "current-page" | "new-page" | "new-board" | "update-selection",
  "frames": [
    {
      "id": "f1",
      "name": "string",
      "x": 0, "y": 0, "width": 1440, "height": 900,
      "fills": [{"fill-color": "#ffffff", "fill-opacity": 1}],
      "shapes": [
        {
          "id": "s1",
          "type": "rect" | "text" | "circle" | "image" | "group" | "path",
          "name": "string",
          "x": 0, "y": 0, "width": 100, "height": 100,
          "fills": [{"fill-color": "#cccccc", "fill-opacity": 1}],
          "r1": 0, "r2": 0, "r3": 0, "r4": 0,
          "content": "string",                       // text only
          "font-family": "string", "font-weight": "400", "font-style": "normal",
          "font-size": 16, "line-height": 1.2, "letter-spacing": 0,
          "text-align": "left" | "center" | "right",
          "shapes": []                                // group/frame children
        }
      ]
    }
  ],
  "interactions": [
    {
      "frame": "f1", "shape": "s1",
      "event-type": "click" | "after-delay",
      "delay": 0,
      "action-type": "navigate" | "open-overlay" | "toggle-overlay" | "close-overlay" | "open-url",
      "destination": "f2",
      "overlay-position": "center" | "top-left" | "top-right" | "top-center" | "bottom-left" | "bottom-right" | "bottom-center" | "manual",
      "url": "https://...",
      "animation": {"type": "dissolve" | "slide" | "push", "duration": 300, "easing": "linear" | "ease" | "ease-in" | "ease-out" | "ease-in-out", "direction": "left" | "right" | "up" | "down"}
    }
  ],
  "flows": [ {"id": "flow1", "name": "Main", "starting-frame": "f1"} ]
}"#;

const ANTI_SLOP_RULES: &str = r#"ANTI-SLOP RULES (non-negotiable — this is what separates real design from AI slop):
- DO NOT default to purple/indigo gradients, generic glassmorphism, or centered "hero with two buttons" templates unless the brief explicitly calls for them.
- DO NOT use "Lorem ipsum". Write real, specific, plausible copy that fits the product (real-sounding headlines, real nav labels, real metrics with units).
- Use a REAL typographic hierarchy: a clear type scale (e.g. 12/14/16/20/28/40/56), real line-heights (1.1–1.25 for headings, 1.5–1.6 for body), real letter-spacing where the language uses it.
- Use a REAL spacing scale (multiples of 4 or 8): 4/8/12/16/24/32/48/64. Never arbitrary numbers like 13, 27, 33.
- Use a constrained palette: background, surface, surface-alt, border, primary, primary-text, secondary-text, accent, and semantic success/warning/danger. Text must be AA-contrast on its background.
- Use a radius scale (0/4/8/12/16/24) consistently — pick one family and stick to it.
- Components must look like REAL production components of the named design language, not generic boxes. A button has real padding, real radius, real hover weight. A card has real elevation/border. An input has a real border + focus affordance. A nav has real item height + active state.
- Prefer flex layouts (frame.layout or nested groups) for lists and rows; only use absolute positioning for genuine free placement.
- Every interactive element that should do something gets an interaction. Group related screens into flows with a starting frame.
- Prefer fewer, larger, well-spaced elements over dense clutter. Whitespace is a feature."#;

const DRAW_SYSTEM_PROMPT: &str = r#"You are the design-generation engine inside a Penpot-based desktop design studio. You output a single JSON object (a DesignSpec) describing a Penpot design AND, when interaction is requested, a runnable prototype. Return ONLY valid JSON — no markdown, no prose, no code fences.

DESIGN-LANGUAGE FIRST. Before drawing, decide the design language:
1. If a "Design-language brief" is provided, FOLLOW IT EXACTLY (palette, type scale, spacing, radius, component style, motion, density).
2. Otherwise, infer the appropriate REAL production design language from the prompt:
   - Mobile app → Material 3 (Android) or Apple HIG (iOS), depending on platform cue.
   - SaaS / dashboard / admin → Linear / Vercel-Geist / Stripe / Notion style (clean, dense, neutral).
   - Marketing / landing → Linear / Vercel / Stripe / Apple style (editorial, generous whitespace).
   - Default (no cue) → a calm, modern neutral system (Geist/Inter + neutral grays + one accent).
   Name the language in your reasoning, then emulate how REAL production apps using it actually look.

{DESIGN_SPEC_SHAPE}

Rules:
- Every shape id and frame id must be unique. Interactions reference existing frame/shape ids.
- Coordinates are relative to the PARENT (frame for top-level shapes, group for nested). Prefer flex on frames when content is a list; use absolute layout only for genuine free placement.
- Colors are hex strings. Keep contrast readable (AA). Use the brief's palette; do NOT invent clashing colors.
- For text shapes, set `content` + `font-family` + `font-size` + text color via `fills`. Use widely available fonts ("Inter", "Source Sans Pro", "Roboto", "Geist") unless the brief/prompt names a font.
- If the user asks for a "prototype", "interactive", "app", "flow", or "screens", ALWAYS include `interactions` and at least one `flow` with a `starting-frame`. If only a static design is requested, omit them.
- If reference URL context or reference files/images are provided, ground the layout, colors, and hierarchy in them; do NOT copy copyrighted text verbatim — paraphrase real placeholder copy.
- Honor the requested frame size for the primary frame. If a frame preset is given, the first/primary frame uses those dimensions.
- For `target: "update-selection"`, emit ONE frame sized exactly to the selection bounds containing ONLY the updated region. Do not touch anything outside the selection. Keep ids stable where the user might want continuity.

{ANTI_SLOP_RULES}

Output ONLY the JSON object. Nothing else."#;

const SCOUT_PROMPT: &str = r#"You are a senior design-systems analyst. You are given reference visuals (screenshots/images of a website or app) and/or a design request. Extract a concrete DESIGN-LANGUAGE BRIEF that another model will use to produce a Penpot design that faithfully follows the reference's design language. Return ONLY JSON:
{
  "design_language": "the named system(s) the reference follows — e.g. Material 3, Apple HIG, Fluent 2, Linear, Vercel/Geist, Stripe, Notion, Atlassian, Tailwind UI, Bootstrap. If unsure, name the closest REAL production design language.",
  "rationale": "1-2 sentences why",
  "palette": {"background":"#hex","surface":"#hex","surface_alt":"#hex","primary":"#hex","primary_text":"#hex","secondary_text":"#hex","accent":"#hex","border":"#hex","success":"#hex","warning":"#hex","danger":"#hex"},
  "typography": {"font_family":"...","scale":[{"name":"h1","size":48,"weight":"700","line_height":1.1},{"name":"h2","size":32,"weight":"700","line_height":1.15},{"name":"h3","size":24,"weight":"600","line_height":1.2},{"name":"title","size":20,"weight":"600","line_height":1.25},{"name":"body","size":16,"weight":"400","line_height":1.5},{"name":"small","size":14,"weight":"400","line_height":1.45},{"name":"caption","size":12,"weight":"500","line_height":1.4}],"body_size":16},
  "spacing_scale": [4,8,12,16,24,32,48,64],
  "radius_scale": [0,4,8,12,16,24],
  "elevation": "description of shadow/depth style",
  "motion": "description of motion language (durations, easings, common patterns)",
  "component_style": "how buttons, cards, inputs, nav, tables look",
  "density": "compact | comfortable | spacious",
  "anti_slop_notes": "concrete things to avoid for this language"
}
Rules:
- Ground EVERY value in the reference visuals when provided; otherwise infer from the named real production design language. Do NOT invent generic values.
- All colors must be valid hex with AA-contrast for text on background.
- Keep it REAL: emulate how actual production apps using this language look, not a generic AI template.
- Output ONLY the JSON."#;

const COMBINED_PROMPT_AUTO: &str = r#"You are the design engine inside a Penpot-based desktop design studio. You can SEE the attached reference images (screenshots/website imagery). First, internally analyze the reference visuals and extract the design language (palette, typography scale, spacing, radius, component style, motion, density). Then produce a Penpot DesignSpec JSON that follows that design language for the user's request. Return ONLY the final DesignSpec JSON — no markdown, no prose, no fences.

{DESIGN_SPEC_SHAPE}

{ANTI_SLOP_RULES}

Follow the reference's real design language exactly. If the user asks for a prototype/interactive/app/flow/screens, include `interactions` and at least one `flow` with a `starting-frame`. Honor the requested frame size for the primary frame. For `target: "update-selection"`, emit ONE frame sized to the selection bounds with ONLY the updated region.

Output ONLY the JSON object."#;

fn build_prompt(template: &str) -> String {
    template
        .replace("{DESIGN_SPEC_SHAPE}", DESIGN_SPEC_SHAPE)
        .replace("{ANTI_SLOP_RULES}", ANTI_SLOP_RULES)
}

// ── Provider calls ───────────────────────────────────────────────────────────
//
// Messages are built as `serde_json::Value` because the two providers use
// DIFFERENT multimodal shapes:
//   • DeepInfra (OpenAI-compatible): user content is an ARRAY of parts:
//        [{type:"text",text:…}, {type:"image_url",image_url:{url:"data:…/base64,…"}}]
//   • Ollama: user content is a STRING, images ride in a sibling `images:[b64,…]`.
// Responses: DeepInfra OpenAI-shaped (`choices[0].message.content`); Ollama
// (`message.content`).

struct ImageInput {
    mime: String,
    b64: String,
}

/// DeepInfra/OpenAI message list: system(text) + user(text + image_url parts).
fn deepinfra_messages(system: &str, user_text: &str, images: &[ImageInput]) -> serde_json::Value {
    let mut user_content = vec![serde_json::json!({"type":"text","text": user_text})];
    for img in images {
        user_content.push(serde_json::json!({
            "type":"image_url",
            "image_url":{"url": format!("data:{};base64,{}", img.mime, img.b64)}
        }));
    }
    serde_json::json!([
        {"role":"system","content": system},
        {"role":"user","content": user_content},
    ])
}

/// Ollama message list: system(text) + user(text, images:[b64,…]).
fn ollama_messages(system: &str, user_text: &str, images: &[ImageInput]) -> serde_json::Value {
    let imgs: Vec<&str> = images.iter().map(|i| i.b64.as_str()).collect();
    serde_json::json!([
        {"role":"system","content": system},
        {"role":"user","content": user_text, "images": imgs},
    ])
}

#[derive(Deserialize)]
struct OpenAiResponse {
    choices: Vec<OpenAiChoice>,
}
#[derive(Deserialize)]
struct OpenAiChoice {
    message: OpenAiMessage,
}
#[derive(Deserialize)]
struct OpenAiMessage {
    content: String,
}

#[derive(Deserialize)]
struct OllamaResponse {
    message: OllamaMessage,
}
#[derive(Deserialize)]
struct OllamaMessage {
    content: String,
}

async fn call_deepinfra(
    cfg: &LlmConfig,
    model: &str,
    system: &str,
    user_text: &str,
    images: &[ImageInput],
) -> Result<String, String> {
    let key = cfg.deepinfra_api_key.trim();
    if key.is_empty() {
        return Err("DeepInfra provider selected but no API key is configured (set it in Settings or .env.local).".into());
    }
    let url = format!("{}/chat/completions", cfg.deepinfra_base.trim_end_matches('/'));
    let body = serde_json::json!({
        "model": model,
        "messages": deepinfra_messages(system, user_text, images),
        "response_format": {"type": "json_object"},
        "temperature": 0.7,
    });
    let client = reqwest::Client::builder()
        .timeout(Duration::from_secs(cfg.timeout_secs))
        .build()
        .map_err(|e| format!("http client build failed: {e}"))?;
    let resp = client
        .post(&url)
        .bearer_auth(key)
        .json(&body)
        .send()
        .await
        .map_err(|e| format!("DeepInfra request failed: {e}"))?;
    let status = resp.status();
    let text = resp.text().await.map_err(|e| format!("DeepInfra read failed: {e}"))?;
    if !status.is_success() {
        let snippet: String = text.chars().take(500).collect();
        return Err(format!("DeepInfra error {status} (model {model}): {snippet}"));
    }
    let parsed: OpenAiResponse = serde_json::from_str(&text).map_err(|e| {
        format!(
            "DeepInfra response parse failed: {e} (body: {})",
            text.chars().take(300).collect::<String>()
        )
    })?;
    parsed
        .choices
        .into_iter()
        .next()
        .map(|c| c.message.content)
        .ok_or_else(|| "DeepInfra returned no choices".into())
}

async fn call_ollama(
    cfg: &LlmConfig,
    model: &str,
    system: &str,
    user_text: &str,
    images: &[ImageInput],
) -> Result<String, String> {
    let url = format!("{}/api/chat", cfg.ollama_url.trim_end_matches('/'));
    let body = serde_json::json!({
        "model": model,
        "messages": ollama_messages(system, user_text, images),
        "format": "json",
        "stream": false,
    });
    let client = reqwest::Client::builder()
        .timeout(Duration::from_secs(cfg.timeout_secs))
        .build()
        .map_err(|e| format!("http client build failed: {e}"))?;
    let mut req = client.post(&url).json(&body);
    let key = cfg.ollama_api_key.trim();
    if !key.is_empty() {
        req = req.bearer_auth(key);
    }
    let resp = req
        .send()
        .await
        .map_err(|e| {
            format!(
                "Ollama request failed ({url}): {e}. Is Ollama running at '{}' with model '{}' pulled? (ollama pull {})",
                cfg.ollama_url, model, model
            )
        })?;
    let status = resp.status();
    let text = resp.text().await.map_err(|e| format!("Ollama read failed: {e}"))?;
    if !status.is_success() {
        return Err(format!(
            "Ollama error {status} (model {model}): {}",
            text.chars().take(500).collect::<String>()
        ));
    }
    let parsed: OllamaResponse = serde_json::from_str(&text)
        .map_err(|e| format!("Ollama response parse failed: {e}"))?;
    Ok(parsed.message.content)
}

/// Call whichever transport `cfg.provider` selects, with an explicit model slug.
async fn call_provider(
    cfg: &LlmConfig,
    model: &str,
    system: &str,
    user_text: &str,
    images: &[ImageInput],
) -> Result<String, String> {
    match cfg.provider.as_str() {
        "ollama" => call_ollama(cfg, model, system, user_text, images).await,
        _ => call_deepinfra(cfg, model, system, user_text, images).await,
    }
}

fn glm_model(cfg: &LlmConfig) -> String {
    match cfg.provider.as_str() {
        "ollama" => cfg.ollama_glm_model.clone(),
        _ => cfg.deepinfra_glm_model.clone(),
    }
}

fn kimi_model(cfg: &LlmConfig) -> String {
    match cfg.provider.as_str() {
        "ollama" => cfg.ollama_kimi_model.clone(),
        _ => cfg.deepinfra_kimi_model.clone(),
    }
}

/// Pull the JSON object out of the model's text response. The system prompt
/// asks for JSON only, but models occasionally wrap it in ```json fences or
/// trailing prose. We find the first balanced `{ … }` run.
fn extract_json(content: &str) -> Result<serde_json::Value, String> {
    let trimmed = content.trim();
    if let Ok(v) = serde_json::from_str::<serde_json::Value>(trimmed) {
        return Ok(v);
    }
    let body = if trimmed.starts_with("```") {
        let inner = trimmed
            .trim_start_matches("```json")
            .trim_start_matches("```")
            .trim_end_matches("```")
            .trim();
        inner.to_string()
    } else {
        trimmed.to_string()
    };
    let bytes = body.as_bytes();
    let start = bytes
        .iter()
        .position(|&b| b == b'{')
        .ok_or_else(|| "model response had no JSON object".to_string())?;
    let mut depth = 0i32;
    let mut in_str = false;
    let mut esc = false;
    let mut end = start;
    for (i, &b) in bytes[start..].iter().enumerate() {
        if in_str {
            if esc {
                esc = false;
            } else if b == b'\\' {
                esc = true;
            } else if b == b'"' {
                in_str = false;
            }
        } else if b == b'"' {
            in_str = true;
        } else if b == b'{' {
            depth += 1;
        } else if b == b'}' {
            depth -= 1;
            if depth == 0 {
                end = start + i + 1;
                break;
            }
        }
    }
    let slice = &body[start..end];
    serde_json::from_str(slice).map_err(|e| {
        format!(
            "could not parse model JSON: {e} (snippet: {})",
            slice.chars().take(200).collect::<String>()
        )
    })
}

// ── Complexity scoring (Auto-mode routing) ───────────────────────────────────

fn score_complexity(prompt: &str) -> u32 {
    let p = prompt.to_lowercase();
    let mut score: u32 = 0;
    let len = prompt.chars().count();
    if len > 240 {
        score += 2;
    }
    if len > 600 {
        score += 2;
    }
    for kw in [
        "prototype", "interactive", "flow", "screens", "dashboard", "admin",
        "analytics", "table", "data table", "complex", "multi-screen",
        "application", "full app", "ecommerce", "e-commerce", "checkout",
        "settings panel", "kanban", "spreadsheet", "calendar",
    ] {
        if p.contains(kw) {
            score += 1;
        }
    }
    score
}

/// Frame preset → (width, height, label) for the prompt + frontend fallback.
fn preset_dims(preset: &str) -> Option<(f64, f64, &'static str)> {
    match preset {
        "mobile" => Some((390.0, 844.0, "mobile (390×844, iPhone)")),
        "mobile-sm" => Some((360.0, 740.0, "small mobile (360×740)")),
        "tablet" => Some((834.0, 1194.0, "tablet (834×1194, iPad)")),
        "web" => Some((1440.0, 900.0, "web desktop (1440×900)")),
        "web-wide" => Some((1920.0, 1080.0, "wide web (1920×1080)")),
        "desktop" => Some((1280.0, 800.0, "desktop app (1280×800)")),
        "watch" => Some((324.0, 394.0, "watch (324×394)")),
        _ => None,
    }
}

fn infer_preset(prompt: &str) -> &'static str {
    let p = prompt.to_lowercase();
    if p.contains("watch") {
        "watch"
    } else if p.contains("tablet") || p.contains("ipad") {
        "tablet"
    } else if p.contains("mobile") || p.contains("iphone") || p.contains("android app") || p.contains("ios app") {
        "mobile"
    } else if p.contains("desktop app") || p.contains("mac app") || p.contains("windows app") {
        "desktop"
    } else if p.contains("wide") || p.contains("hero") || p.contains("landing") {
        "web-wide"
    } else {
        "web"
    }
}

// ── Tauri commands ──────────────────────────────────────────────────────────

#[tauri::command]
pub fn llm_get_config(app: AppHandle) -> LlmConfigView {
    let cfg = load_config(&app);
    LlmConfigView {
        provider: cfg.provider,
        mode: cfg.mode,
        deepinfra_base: cfg.deepinfra_base,
        deepinfra_glm_model: cfg.deepinfra_glm_model,
        deepinfra_kimi_model: cfg.deepinfra_kimi_model,
        deepinfra_api_key_set: !cfg.deepinfra_api_key.trim().is_empty(),
        ollama_url: cfg.ollama_url,
        ollama_glm_model: cfg.ollama_glm_model,
        ollama_kimi_model: cfg.ollama_kimi_model,
        ollama_api_key_set: !cfg.ollama_api_key.trim().is_empty(),
        firecrawl_api_key_set: !cfg.firecrawl_api_key.trim().is_empty(),
        firecrawl_base: cfg.firecrawl_base,
        timeout_secs: cfg.timeout_secs,
        memory_enabled: cfg.memory_enabled,
        memory_max_turns: cfg.memory_max_turns,
    }
}

#[tauri::command]
pub fn llm_set_config(app: AppHandle, mut config: LlmConfig) -> Result<(), String> {
    // Validate provider/mode.
    if !matches!(config.provider.as_str(), "deepinfra" | "ollama") {
        return Err("provider must be 'deepinfra' or 'ollama'".into());
    }
    if !matches!(config.mode.as_str(), "max" | "auto") {
        return Err("mode must be 'max' or 'auto'".into());
    }
    // `llm_get_config` only exposes *_api_key presence flags, not the secrets,
    // so the Settings panel sends blank strings for "unchanged" keys. Preserve
    // the existing key in that case so saving never wipes a configured key.
    let prev = load_config(&app);
    if config.deepinfra_api_key.trim().is_empty() {
        config.deepinfra_api_key = prev.deepinfra_api_key;
    }
    if config.ollama_api_key.trim().is_empty() {
        config.ollama_api_key = prev.ollama_api_key;
    }
    if config.firecrawl_api_key.trim().is_empty() {
        config.firecrawl_api_key = prev.firecrawl_api_key;
    }
    save_config(&app, &config)
}

/// Reset the abort flag and clear in-flight generation. The next `llm_generate`
/// call clears the flag at its start, so a cancel must arrive during a call.
#[tauri::command]
pub fn llm_cancel() -> Result<(), String> {
    ABORT.store(true, Ordering::Relaxed);
    Ok(())
}

/// Clear conversation memory for a file.
#[tauri::command]
pub fn llm_clear_memory(app: AppHandle, file_id: Option<String>) -> Result<(), String> {
    let id = file_id.unwrap_or_else(|| "default".to_string());
    clear_memory(&app, &id)
}

/// The closed AI entry point. Returns a DesignSpec JSON value that the
/// frontend validates + applies via Foundation F3 `apply-design-spec`.
#[tauri::command]
pub async fn llm_generate(
    app: AppHandle,
    request: GenerateRequest,
) -> Result<serde_json::Value, String> {
    // A new generation cancels any pending abort from a prior cancelled call.
    ABORT.store(false, Ordering::Relaxed);
    check_aborted()?;

    let cfg = load_config(&app);
    let quality = request
        .options
        .quality
        .as_deref()
        .unwrap_or(cfg.mode.as_str())
        .to_string();
    let is_max = quality == "max";

    emit_progress(&app, "starting", &format!("mode:{}", quality));

    let fetch_client = reqwest::Client::builder()
        .timeout(Duration::from_secs(30))
        .user_agent("PenpotDesktop/1.0 (design-fetch)")
        .build()
        .map_err(|e| format!("fetch client build failed: {e}"))?;

    // ── Visit URL references FIRST, before the model runs ──
    let mut url_context = String::new();
    let mut url_images: Vec<ImageInput> = Vec::new();
    let urls = extract_urls(&request.prompt);
    if !urls.is_empty() {
        emit_progress(&app, "fetching-url", &format!("{} url(s)", urls.len()));
    }
    for url in &urls {
        // Firecrawl path (rendered screenshot) if a key is configured.
        if let Some((shot, md)) = fetch_firecrawl(&fetch_client, &cfg, url).await {
            url_images.push(shot);
            url_context.push_str(&format!("--- URL: {url} (Firecrawl render) ---\n[markdown]\n{md}\n\n"));
            continue;
        }
        // Fallback: scrape HTML + download referenced images as vision inputs.
        match fetch_url_context(&fetch_client, url).await {
            Some(ctx) => {
                // Re-parse the [img sources] block from the scraped context to
                // download those images as vision inputs (Kimi "sees" the site).
                let img_lines: Vec<String> = ctx
                    .lines()
                    .skip_while(|l| !l.starts_with("[img sources]"))
                    .skip(1)
                    .take_while(|l| !l.starts_with('['))
                    .map(|l| l.trim().to_string())
                    .filter(|l| !l.is_empty())
                    .collect();
                if !img_lines.is_empty() {
                    let extra = download_url_images(&fetch_client, url, &img_lines, 8).await;
                    url_images.extend(extra);
                }
                url_context.push_str(&ctx);
                url_context.push_str("\n\n");
            }
            None => url_context.push_str(&format!("[could not fetch {url}; generating from prompt only]\n\n")),
        }
    }

    check_aborted()?;

    // ── User-attached files → images (vision) or text ──
    let mut images: Vec<ImageInput> = url_images;
    let mut prompt_text = String::new();
    prompt_text.push_str(&request.prompt);

    if !url_context.is_empty() {
        prompt_text.push_str(
            "\n\n--- Reference URL context (the shell fetched these for you; ground the design in them) ---\n",
        );
        prompt_text.push_str(&url_context);
    }

    for f in &request.files {
        let mime = if f.mime.is_empty() { guess_mime(&f.name) } else { f.mime.clone() };
        let bytes = if let Some(p) = &f.path {
            std::fs::read(p).ok()
        } else if let Some(b64) = &f.base64 {
            base64_decode::decode(b64).ok()
        } else {
            None
        };
        let is_image = mime.starts_with("image/");
        match bytes {
            Some(b) if is_image => {
                if images.len() < 12 {
                    images.push(ImageInput { mime, b64: base64_encode(&b) });
                }
            }
            Some(b) => {
                let text = String::from_utf8_lossy(&b).chars().take(20_000).collect::<String>();
                prompt_text.push_str(&format!("\n\n--- Attached file: {} ({}) ---\n{}", f.name, mime, text));
            }
            None => {
                prompt_text.push_str(&format!("\n\n[attached file {} could not be read]", f.name));
            }
        }
    }

    // ── Generation mode + placement target hints ──
    if let Some(mode) = &request.options.mode {
        prompt_text.push_str(&format!("\n\n[generation mode: {mode}]"));
    }
    let target = request.options.target.clone().unwrap_or_else(|| "new-board".to_string());
    prompt_text.push_str(&format!("\n[placement target: {target}]"));

    // ── Frame preset ──
    let preset = request
        .options
        .frame_preset
        .as_deref()
        .filter(|p| *p != "auto")
        .map(String::from)
        .unwrap_or_else(|| infer_preset(&request.prompt).to_string());
    let (fw, fh, flabel) = request
        .options
        .frame_width
        .zip(request.options.frame_height)
        .map(|(w, h)| (w, h, "custom"))
        .or_else(|| preset_dims(&preset).map(|(w, h, l)| (w, h, l)))
        .unwrap_or((1440.0, 900.0, "web desktop (1440×900)"));
    prompt_text.push_str(&format!("\n[primary frame size: {flabel} — use {fw}×{fh} for the primary frame]"));

    // ── Selection context (region update) ──
    let is_update = target == "update-selection";
    if is_update {
        if let Some(sel) = &request.options.selection {
            let b = &sel.bounds;
            prompt_text.push_str(&format!(
                "\n\n--- REGION UPDATE — modify ONLY this region ---\nSelection bounds: {:.0}×{:.0} at ({:.0},{:.0}).\nCurrent content of the region (JSON):\n{}\nThe user wants to UPDATE ONLY this region per their prompt. Do not change anything outside it. Emit target=\"update-selection\" with ONE frame of size {:.0}×{:.0} containing the updated shapes (coordinates relative to that frame).",
                b.width, b.height, b.x, b.y,
                serde_json::to_string(&sel.shapes).unwrap_or_else(|_| "{}".into()),
                b.width.max(40.0), b.height.max(40.0)
            ));
        } else {
            // No selection provided — degrade gracefully to a new board.
            prompt_text.push_str("\n[update-selection requested but no selection context provided; generate a new board]");
        }
    }

    // ── Conversation memory ──
    let use_mem = request.options.use_memory.unwrap_or(cfg.memory_enabled);
    let file_id = request.options.file_id.clone().unwrap_or_else(|| "default".to_string());
    let mem = if use_mem { load_memory(&app, &file_id) } else { MemoryFile::default() };
    let transcript = memory_transcript(&mem, cfg.memory_max_turns);
    if !transcript.is_empty() {
        prompt_text.push_str("\n\n");
        prompt_text.push_str(&transcript);
    }

    check_aborted()?;

    // ── Pipeline: dual-model orchestration ──
    let has_vision = !images.is_empty();
    let complexity = score_complexity(&request.prompt);

    let draw_sys = build_prompt(DRAW_SYSTEM_PROMPT);
    let combined_sys = build_prompt(COMBINED_PROMPT_AUTO);

    let raw = if is_max {
        // Max quality: GLM draws; Kimi scouts vision when references exist.
        if has_vision {
            emit_progress(&app, "scouting", "analyzing references");
            let scout_raw = call_provider(&cfg, &kimi_model(&cfg), SCOUT_PROMPT, "Extract the design-language brief from the reference visuals.", &images).await?;
            check_aborted()?;
            let brief = extract_json(&scout_raw)
                .map(|v| serde_json::to_string_pretty(&v).unwrap_or_else(|_| scout_raw.clone()))
                .unwrap_or_else(|_| scout_raw.clone());
            emit_progress(&app, "generating", "drawing with GLM (grounded)");
            let user = format!(
                "Design-language brief (from reference analysis — FOLLOW IT EXACTLY):\n{brief}\n\nUser request:\n{prompt_text}"
            );
            call_provider(&cfg, &glm_model(&cfg), &draw_sys, &user, &[]).await?
        } else {
            emit_progress(&app, "generating", "drawing with GLM");
            call_provider(&cfg, &glm_model(&cfg), &draw_sys, &prompt_text, &[]).await?
        }
    } else {
        // Auto: Kimi single-shot when there is vision; GLM only for complex asks.
        if has_vision {
            emit_progress(&app, "generating", "Kimi (vision + design)");
            let user = format!("User request:\n{prompt_text}");
            call_provider(&cfg, &kimi_model(&cfg), &combined_sys, &user, &images).await?
        } else if complexity >= 3 {
            emit_progress(&app, "generating", "GLM (complex)");
            call_provider(&cfg, &glm_model(&cfg), &draw_sys, &prompt_text, &[]).await?
        } else {
            emit_progress(&app, "generating", "Kimi");
            call_provider(&cfg, &kimi_model(&cfg), &draw_sys, &prompt_text, &[]).await?
        }
    };

    check_aborted()?;
    emit_progress(&app, "finalizing", "parsing");

    let spec = extract_json(&raw)?;

    // ── Persist memory turn ──
    if use_mem {
        let summary: String = serde_json::to_string(&spec)
            .unwrap_or_default()
            .chars()
            .take(4000)
            .collect();
        append_memory(&app, &file_id, MemoryTurn {
            role: "user".into(),
            content: request.prompt.clone(),
            ts: 0,
            target: Some(target.clone()),
        });
        append_memory(&app, &file_id, MemoryTurn {
            role: "assistant".into(),
            content: format!("[generated spec for target={target}]\n{summary}"),
            ts: 0,
            target: Some(target.clone()),
        });
    }

    emit_progress(&app, "done", "ready to apply");
    Ok(spec)
}

fn guess_mime(name: &str) -> String {
    let l = name.to_lowercase();
    if l.ends_with(".png") {
        "image/png".into()
    } else if l.ends_with(".jpg") || l.ends_with(".jpeg") {
        "image/jpeg".into()
    } else if l.ends_with(".gif") {
        "image/gif".into()
    } else if l.ends_with(".webp") {
        "image/webp".into()
    } else if l.ends_with(".svg") {
        "image/svg+xml".into()
    } else {
        "text/plain".into()
    }
}

// ── base64 helpers (no extra crate) ──────────────────────────────────────────

mod base64_decode {
    pub fn decode(s: &str) -> Result<Vec<u8>, String> {
        const TBL: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        let mut out = Vec::with_capacity(s.len() * 3 / 4);
        let bytes = s.as_bytes();
        let mut buf = 0u32;
        let mut bits = 0u32;
        for &b in bytes {
            if b == b'=' || b.is_ascii_whitespace() {
                continue;
            }
            let val = TBL.iter().position(|&c| c == b).ok_or("invalid base64 char")? as u32;
            buf = (buf << 6) | val;
            bits += 6;
            if bits >= 8 {
                bits -= 8;
                out.push((buf >> bits) as u8);
                buf &= (1 << bits) - 1;
            }
        }
        Ok(out)
    }
}

fn base64_encode(data: &[u8]) -> String {
    const TBL: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = String::with_capacity((data.len() + 2) / 3 * 4);
    for chunk in data.chunks(3) {
        let b = chunk;
        let n = b.len();
        let v = ((b[0] as u32) << 16)
            | (if n > 1 { (b[1] as u32) << 8 } else { 0 })
            | (if n > 2 { b[2] as u32 } else { 0 });
        out.push(TBL[((v >> 18) & 0x3f) as usize] as char);
        out.push(TBL[((v >> 12) & 0x3f) as usize] as char);
        if n > 1 {
            out.push(TBL[((v >> 6) & 0x3f) as usize] as char);
        } else {
            out.push('=');
        }
        if n > 2 {
            out.push(TBL[(v & 0x3f) as usize] as char);
        } else {
            out.push('=');
        }
    }
    out
}