// Penpot Desktop — closed AI layer (Foundation F4 + Feature 3 backend).
//
// The user only ever sees the input bar (Feature 3) and the final result on
// the canvas. Everything in this module is an implementation detail: the
// model, the prompts, the URL fetching, the provider switching. The frontend
// calls `llm_generate` with `{prompt, files, options}` and receives a
// DesignSpec JSON (validated + applied CLJS-side by Foundation F3); it never
// sees the API key, the provider, or the system prompt.
//
// Underlying model: Kimi K2.6, exposed through two interchangeable providers
// that both speak the OpenAI-compatible chat-completions shape:
//
//   • DeepInfra — production. Users get a monthly/yearly plan. The model
//     slug is configurable (`llm.json` `deepinfra_model`); default
//     `moonshotai/Kimi-K2.6` (confirm the exact DeepInfra slug at deploy).
//   • Ollama — local testing only (`http://127.0.0.1:11434/api/chat`,
//     `model: kimi-k2.6`, `format: "json"`). No API key.
//
// URL references: if the prompt contains a URL, the shell visits it FIRST
// (Feature 3 Phase 3), extracts text/CSS/asset URLs, and includes that as
// context before calling the model — so the model grounds the design in the
// referenced page instead of guessing.

use std::time::Duration;
use serde::{Deserialize, Serialize};
use tauri::AppHandle;

use crate::commands::llm_config_path;

// ── Provider config ─────────────────────────────────────────────────────────

/// Persisted to `<app-data>/llm.json`. The API key NEVER crosses to the
/// frontend — `llm_get_config` returns a masked copy.
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct LlmConfig {
    /// "deepinfra" (production default) or "ollama" (local testing).
    #[serde(default = "default_provider")]
    pub provider: String,

    /// DeepInfra API key (Bearer). Empty for Ollama.
    #[serde(default)]
    pub deepinfra_api_key: String,

    /// DeepInfra model slug. Confirm the exact Kimi K2.6 slug at deploy.
    #[serde(default = "default_deepinfra_model")]
    pub deepinfra_model: String,

    /// Ollama server base URL.
    #[serde(default = "default_ollama_url")]
    pub ollama_url: String,

    /// Ollama model name.
    #[serde(default = "default_ollama_model")]
    pub ollama_model: String,

    /// Per-request timeout in seconds.
    #[serde(default = "default_timeout")]
    pub timeout_secs: u64,
}

fn default_provider() -> String { "deepinfra".into() }
fn default_deepinfra_model() -> String { "moonshotai/Kimi-K2.6".into() }
fn default_ollama_url() -> String { "http://127.0.0.1:11434".into() }
fn default_ollama_model() -> String { "kimi-k2.6".into() }
fn default_timeout() -> u64 { 180 }

impl Default for LlmConfig {
    fn default() -> Self {
        Self {
            provider: default_provider(),
            deepinfra_api_key: String::new(),
            deepinfra_model: default_deepinfra_model(),
            ollama_url: default_ollama_url(),
            ollama_model: default_ollama_model(),
            timeout_secs: default_timeout(),
        }
    }
}

/// What `llm_get_config` returns — the API key is masked to a presence flag.
#[derive(Serialize)]
struct LlmConfigView {
    provider: String,
    deepinfra_model: String,
    deepinfra_api_key_set: bool,
    ollama_url: String,
    ollama_model: String,
    timeout_secs: u64,
}

fn load_config(app: &AppHandle) -> LlmConfig {
    match llm_config_path(app) {
        Ok(path) if path.exists() => {
            let raw = std::fs::read_to_string(&path).unwrap_or_default();
            serde_json::from_str(&raw).unwrap_or_default()
        }
        _ => LlmConfig::default(),
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
struct GenerateOptions {
    /// "design" | "prototype" | "design+prototype" (default auto from prompt).
    #[serde(default)]
    mode: Option<String>,
    /// "current-page" | "new-page" | "new-board" (default new-board on current page).
    #[serde(default)]
    target: Option<String>,
}

#[derive(Debug, Deserialize)]
struct GenerateRequest {
    prompt: String,
    #[serde(default)]
    files: Vec<FileInput>,
    #[serde(default)]
    options: GenerateOptions,
}

// ── URL reference fetching (Feature 3 Phase 3) ──────────────────────────────

/// Extract `http(s)://…` URLs from the prompt so the shell can visit them
/// before calling the model. Returns URLs in order of appearance, de-duped.
/// (A tiny dependency-free scanner — we avoid pulling the `regex` crate.)
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
                        || c == b'"' || c == b'\'' || c == b'`' || c == b'<' || c == b'>' {
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

/// Fetch a URL and condense it to a compact text context for the model:
/// visible text (tags stripped), inline `<style>` blocks, and `<img src>` /
/// `<link href>` asset URLs. Capped to stay within the model context window.
fn fetch_url_context(client: &reqwest::Client, url: &str) -> Option<String> {
    let resp = client.get(url).send().ok()?;
    let ct = resp.headers().get(reqwest::header::CONTENT_TYPE)
        .and_then(|v| v.to_str().ok())
        .unwrap_or("")
        .to_string();
    let body = resp.text().ok()?;
    let is_html = ct.contains("text/html") || body.trim_start().to_lowercase().starts_with("<!doctype")
        || body.trim_start().to_lowercase().starts_with("<html");

    if !is_html {
        // Non-HTML (CSS, JSON, plain text, an image URL, …): include verbatim, capped.
        let capped = body.chars().take(8_000).collect::<String>();
        return Some(format!("--- URL: {url} (content-type: {ct}) ---\n{capped}"));
    }

    // Naive HTML → text + asset extraction. Good enough to ground the model;
    // we don't need a full DOM.
    let style: Vec<&str> = extract_blocks(&body, "<style", "</style>");
    let imgs = extract_attrs(&body, "<img", "src");
    let links = extract_attrs(&body, "<link", "href");

    // Strip <script> blocks (JS is noise for design), then strip all tags
    // for a rough visible-text view.
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
    let visible: String = visible.split_whitespace()
        .collect::<Vec<_>>().join(" ")
        .chars().take(12_000).collect();

    let mut out = format!("--- URL: {url} ---\n[visible text]\n{visible}\n");
    if !imgs.is_empty() {
        out.push_str("[img sources]\n");
        for u in imgs.iter().take(40) { out.push_str(u); out.push('\n'); }
    }
    if !links.is_empty() {
        out.push_str("[link hrefs]\n");
        for u in links.iter().take(40) { out.push_str(u); out.push('\n'); }
    }
    if !style.is_empty() {
        out.push_str("[inline CSS]\n");
        for s in style.iter().take(8) {
            out.push_str(s); out.push('\n');
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
        // only consider the opening tag (up to first '>')
        let tag_end = chunk.find('>').unwrap_or(chunk.len());
        let tag_slice = &chunk[..tag_end.min(2048)];
        if let Some(j) = tag_slice.to_lowercase().find(&needle) {
            let after = &tag_slice[j + needle.len()..];
            let val = take_attr_value(after);
            if !val.is_empty() { out.push(val); }
        }
        rest = &chunk[tag_end..];
    }
    out
}

fn take_attr_value(s: &str) -> String {
    let s = s.trim_start();
    let bytes = s.as_bytes();
    if bytes.is_empty() { return String::new(); }
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

// ── System prompt + DesignSpec schema ───────────────────────────────────────
//
// The DesignSpec JSON shape is owned CLJS-side (Foundation F3
// `app.common.types.design-spec`). The model is taught the same shape here so
// its JSON parses cleanly into F3's Malli schema. Keeping the canonical schema
// in CLJS means one source of truth; this prompt is a faithful prose mirror.

const SYSTEM_PROMPT: &str = r#"You are the design-generation engine inside a Penpot-based desktop design studio. You output a single JSON object describing a Penpot design AND, when interaction is requested, a runnable prototype. Return ONLY valid JSON — no markdown, no prose, no code fences.

DesignSpec JSON shape:
{
  "target": "current-page" | "new-page" | "new-board",   // where to place it
  "frames": [
    {
      "id": "f1",                       // short stable id, referenced by interactions/flows
      "name": "string",
      "x": 0, "y": 0, "width": 1440, "height": 900,
      "fills": [{"fill-color": "#ffffff", "fill-opacity": 1}],
      "layout": {"type": "flex", "direction": "row" | "column"},   // optional
      "shapes": [
        {
          "id": "s1",
          "type": "rect" | "text" | "circle" | "image" | "group" | "path",
          "name": "string",
          "x": 0, "y": 0, "width": 100, "height": 100,
          "fills": [{"fill-color": "#cccccc", "fill-opacity": 1}],
          "r1": 0, "r2": 0, "r3": 0, "r4": 0,            // corner radii (rect)
          "content": "string",                           // (text only)
          "font-family": "string", "font-weight": "400", "font-style": "normal",
          "font-size": 16, "line-height": 1.2, "letter-spacing": 0,
          "text-align": "left" | "center" | "right",
          "fills": [{"fill-color": "#000000", "fill-opacity": 1}], // text color
          "image-data": "base64...",                      // (image only, with image-mime)
          "image-mime": "image/png",
          "shapes": []                                    // (group/frame children)
        }
      ]
    }
  ],
  "interactions": [                          // optional — only when a prototype is requested
    {
      "frame": "f1", "shape": "s1",
      "event-type": "click" | "after-delay",
      "delay": 0,                            // ms, for after-delay
      "action-type": "navigate" | "open-overlay" | "toggle-overlay" | "close-overlay" | "open-url",
      "destination": "f2",                   // frame id, for navigate/overlay actions
      "overlay-position": "center" | "top-left" | "top-right" | "top-center" | "bottom-left" | "bottom-right" | "bottom-center" | "manual",
      "url": "https://...",                  // for open-url
      "animation": {"type": "dissolve" | "slide" | "push", "duration": 300, "easing": "linear" | "ease" | "ease-in" | "ease-out" | "ease-in-out", "direction": "left" | "right" | "up" | "down"}
    }
  ],
  "flows": [                                 // optional — defines prototype flows
    {"id": "flow1", "name": "Main", "starting-frame": "f1"}
  ]
}

Rules:
- Every shape id and frame id must be unique. Interactions reference existing frame/shape ids.
- Coordinates are relative to the PARENT (frame for top-level shapes, group for nested). Use a sensible absolute layout OR flex; prefer flex on frames when content is a list.
- Colors are hex strings. Keep contrast readable. Honor a warm, design-studio aesthetic (soft peach/coral accents on neutral surfaces) unless the prompt says otherwise.
- For text shapes, set `content` + `font-family` + `font-size` + text color via `fills`. Use widely available fonts (e.g. "Inter", "Source Sans Pro", "Roboto") unless the prompt names a font.
- If the user asks for a "prototype", "interactive", "app", "flow", or "screens", ALWAYS include `interactions` and at least one `flow` with a `starting-frame`.
- If the user only asks for a static design/logo/illustration, omit `interactions` and `flows`.
- If reference URL context or reference files are provided, ground the layout, colors, and hierarchy in them; do not copy copyrighted text verbatim — paraphrase placeholder copy.
- Output ONLY the JSON object. Nothing else."#;

// ── Provider calls ───────────────────────────────────────────────────────────
//
// Messages are built as `serde_json::Value` (not a typed struct) because the
// two providers use DIFFERENT multimodal shapes:
//   • DeepInfra (OpenAI-compatible): user content is an ARRAY of parts:
//        [{type:"text",text:…}, {type:"image_url",image_url:{url:"data:…/base64,…"}}]
//   • Ollama: user content is a STRING, images ride in a sibling `images:[b64,…]`.
// Both providers' responses are OpenAI-shaped (`choices[0].message.content`)
// except Ollama (`message.content`).

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
    system: &str,
    user_text: &str,
    images: &[ImageInput],
) -> Result<String, String> {
    let key = cfg.deepinfra_api_key.trim();
    if key.is_empty() {
        return Err("DeepInfra provider selected but no API key is configured (set it in Settings).".into());
    }
    let url = "https://api.deepinfra.com/v1/openai/chat/completions";
    let body = serde_json::json!({
        "model": cfg.deepinfra_model,
        "messages": deepinfra_messages(system, user_text, images),
        "response_format": {"type": "json_object"},
        "temperature": 0.7,
    });
    let client = reqwest::Client::builder()
        .timeout(Duration::from_secs(cfg.timeout_secs))
        .build()
        .map_err(|e| format!("http client build failed: {e}"))?;
    let resp = client
        .post(url)
        .bearer_auth(key)
        .json(&body)
        .send()
        .await
        .map_err(|e| format!("DeepInfra request failed: {e}"))?;
    let status = resp.status();
    let text = resp.text().await.map_err(|e| format!("DeepInfra read failed: {e}"))?;
    if !status.is_success() {
        let snippet: String = text.chars().take(500).collect();
        return Err(format!("DeepInfra error {status}: {snippet}"));
    }
    let parsed: OpenAiResponse = serde_json::from_str(&text)
        .map_err(|e| format!("DeepInfra response parse failed: {e} (body: {})", &text.chars().take(300).collect::<String>()))?;
    parsed
        .choices
        .into_iter()
        .next()
        .map(|c| c.message.content)
        .ok_or_else(|| "DeepInfra returned no choices".into())
}

async fn call_ollama(
    cfg: &LlmConfig,
    system: &str,
    user_text: &str,
    images: &[ImageInput],
) -> Result<String, String> {
    let url = format!("{}/api/chat", cfg.ollama_url.trim_end_matches('/'));
    let body = serde_json::json!({
        "model": cfg.ollama_model,
        "messages": ollama_messages(system, user_text, images),
        "format": "json",
        "stream": false,
    });
    let client = reqwest::Client::builder()
        .timeout(Duration::from_secs(cfg.timeout_secs))
        .build()
        .map_err(|e| format!("http client build failed: {e}"))?;
    let resp = client
        .post(&url)
        .json(&body)
        .send()
        .await
        .map_err(|e| format!("Ollama request failed ({url}): {e}. Is Ollama running with model '{}' pulled? (ollama pull {})", cfg.ollama_model, cfg.ollama_model))?;
    let status = resp.status();
    let text = resp.text().await.map_err(|e| format!("Ollama read failed: {e}"))?;
    if !status.is_success() {
        return Err(format!("Ollama error {status}: {}", text.chars().take(500).collect::<String>()));
    }
    let parsed: OllamaResponse = serde_json::from_str(&text)
        .map_err(|e| format!("Ollama response parse failed: {e}"))?;
    Ok(parsed.message.content)
}

/// Pull the JSON object out of the model's text response. The system prompt
/// asks for JSON only, but models occasionally wrap it in ```json fences or
/// trailing prose. We find the first balanced `{ … }` run.
fn extract_json(content: &str) -> Result<serde_json::Value, String> {
    let trimmed = content.trim();
    // Fast path: already valid JSON.
    if let Ok(v) = serde_json::from_str::<serde_json::Value>(trimmed) {
        return Ok(v);
    }
    // Strip ```json … ``` fences if present.
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
    // Find the outermost balanced object.
    let bytes = body.as_bytes();
    let start = bytes.iter().position(|&b| b == b'{')
        .ok_or_else(|| "model response had no JSON object".to_string())?;
    let mut depth = 0i32;
    let mut in_str = false;
    let mut esc = false;
    let mut end = start;
    for (i, &b) in bytes[start..].iter().enumerate() {
        if in_str {
            if esc { esc = false; }
            else if b == b'\\' { esc = true; }
            else if b == b'"' { in_str = false; }
        } else if b == b'"' { in_str = true; }
        else if b == b'{' { depth += 1; }
        else if b == b'}' {
            depth -= 1;
            if depth == 0 { end = start + i + 1; break; }
        }
    }
    let slice = &body[start..end];
    serde_json::from_str(slice)
        .map_err(|e| format!("could not parse model JSON: {e} (snippet: {})", &slice.chars().take(200).collect::<String>()))
}

// ── Tauri commands ──────────────────────────────────────────────────────────

#[tauri::command]
pub fn llm_get_config(app: AppHandle) -> LlmConfigView {
    let cfg = load_config(&app);
    LlmConfigView {
        provider: cfg.provider,
        deepinfra_model: cfg.deepinfra_model,
        deepinfra_api_key_set: !cfg.deepinfra_api_key.trim().is_empty(),
        ollama_url: cfg.ollama_url,
        ollama_model: cfg.ollama_model,
        timeout_secs: cfg.timeout_secs,
    }
}

#[tauri::command]
pub fn llm_set_config(app: AppHandle, config: LlmConfig) -> Result<(), String> {
    save_config(&app, &config)
}

/// The closed AI entry point. Returns a DesignSpec JSON value that the
/// frontend validates + applies via Foundation F3 `apply-design-spec`.
#[tauri::command]
pub async fn llm_generate(
    app: AppHandle,
    request: GenerateRequest,
) -> Result<serde_json::Value, String> {
    let cfg = load_config(&app);

    let fetch_client = reqwest::Client::builder()
        .timeout(Duration::from_secs(30))
        .user_agent("PenpotDesktop/1.0 (design-fetch)")
        .build()
        .map_err(|e| format!("fetch client build failed: {e}"))?;

    // Phase 3 — visit URL references FIRST, before the model runs.
    let mut url_context = String::new();
    for url in extract_urls(&request.prompt) {
        match fetch_url_context(&fetch_client, &url) {
            Some(ctx) => {
                url_context.push_str(&ctx);
                url_context.push_str("\n\n");
            }
            None => url_context.push_str(&format!("[could not fetch {url}; generating from prompt only]\n\n")),
        }
    }

    // Build the user text: prompt + file text + URL context. Images are
    // collected separately so each provider can encode them in its own shape.
    let mut prompt_text = String::new();
    prompt_text.push_str(&request.prompt);
    if !url_context.is_empty() {
        prompt_text.push_str("\n\n--- Reference URL context (the shell fetched these for you; ground the design in them) ---\n");
        prompt_text.push_str(&url_context);
    }
    if let Some(mode) = &request.options.mode {
        prompt_text.push_str(&format!("\n\n[generation mode: {mode}]"));
    }
    if let Some(target) = &request.options.target {
        prompt_text.push_str(&format!("\n[placement target: {target}]"));
    }

    let mut images: Vec<ImageInput> = Vec::new();
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
                images.push(ImageInput { mime, b64: base64_encode(&b) });
            }
            Some(b) => {
                let text = String::from_utf8_lossy(&b).chars().take(20_000).collect::<String>();
                prompt_text.push_str(&format!("\n\n--- Attached file: {} ({}) ---\n{}", f.name, mime, text));
            }
            None => {
                // No bytes resolvable — note it so the model knows.
                prompt_text.push_str(&format!("\n\n[attached file {} could not be read]", f.name));
            }
        }
    }

    // Phase 4 — call the provider (closed: the user never sees which one).
    let raw = match cfg.provider.as_str() {
        "ollama" => call_ollama(&cfg, SYSTEM_PROMPT, &prompt_text, &images).await?,
        _ => call_deepinfra(&cfg, SYSTEM_PROMPT, &prompt_text, &images).await?,   // default: deepinfra (production)
    };

    extract_json(&raw)
}

fn guess_mime(name: &str) -> String {
    let l = name.to_lowercase();
    if l.ends_with(".png") { "image/png".into() }
    else if l.ends_with(".jpg") || l.ends_with(".jpeg") { "image/jpeg".into() }
    else if l.ends_with(".gif") { "image/gif".into() }
    else if l.ends_with(".webp") { "image/webp".into() }
    else if l.ends_with(".svg") { "image/svg+xml".into() }
    else { "text/plain".into() }
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
            if b == b'=' || b.is_ascii_whitespace() { continue; }
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
        let v = ((b[0] as u32) << 16) | (if n > 1 { (b[1] as u32) << 8 } else { 0 }) | (if n > 2 { b[2] as u32 } else { 0 });
        out.push(TBL[((v >> 18) & 0x3f) as usize] as char);
        out.push(TBL[((v >> 12) & 0x3f) as usize] as char);
        if n > 1 { out.push(TBL[((v >> 6) & 0x3f) as usize] as char); } else { out.push('='); }
        if n > 2 { out.push(TBL[(v & 0x3f) as usize] as char); } else { out.push('='); }
    }
    out
}