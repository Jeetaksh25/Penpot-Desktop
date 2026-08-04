// Penpot Desktop — Ovion Cloud publish MVP (ALL_APPS_PARITY P0.11).
//
// The frontend builds a static HTML bundle from the current page (CLJS
// `app.main.data.exports.publish`) and invokes `publish_site` to upload it to
// Ovion Cloud. Ovion Cloud is an OpenAI-compatible, Bearer-auth transport that
// already backs the AI layer (`llm.rs`); this module is the publish sibling —
// it POSTs the bundle as JSON to `{endpoint}/sites/publish` and returns the
// `share_url` the server assigns.
//
// Auth resolution mirrors `llm.rs`'s config read but is self-contained: this
// module does NOT import any private `llm.rs` item. It re-uses the shared
// `commands::llm_config_path` helper (the same `<app-data>/llm.json` path the
// AI layer reads) and deserializes only the two fields it needs. The frontend
// never sees the raw token — `llm_get_config` masks it to a `*_set` bool — so
// the modal passes `token: nil` and Rust resolves it from `llm.json`. An
// explicit `token`/`endpoint` override in the request wins over the config
// file (lower-risk, and useful for future per-publish keys).

use std::time::Duration;

use serde::{Deserialize, Serialize};
use tauri::AppHandle;

use crate::commands::llm_config_path;

/// The submission request for the native forms builder (P1.23 native-forms
/// half). `token`/`endpoint` are optional overrides; when absent/empty, Rust
/// resolves them from `<app-data>/llm.json` — the same resolution path as
/// `publish_site`. `payload` is the arbitrary JSON the form fields produced.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SubmitFormRequest {
    pub endpoint: Option<String>,
    pub form_name: String,
    pub payload: serde_json::Value,
    pub token: Option<String>,
}

/// The Ovion Cloud default endpoint — mirrors `llm.rs::default_ovion_cloud_endpoint`
/// without importing the private fn. Kept in sync manually.
const DEFAULT_OVION_CLOUD_ENDPOINT: &str = "https://api.ovion.app/v1";

/// One published HTML page in the bundle. `slug` is the in-site path segment
/// (e.g. `"home"` → `/home.html`); `html` is the full `<!doctype html>` document.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PageAsset {
    pub slug: String,
    pub html: String,
}

/// The static-site bundle the frontend assembles from the page's generated
/// HTML + CSS. Multi-page-ready in shape; for MVP `pages` holds the single
/// current page and `index_html` is that page's document (or a landing page).
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PublishBundle {
    pub index_html: String,
    pub pages: Vec<PageAsset>,
    pub css: String,
}

/// The publish request. `token`/`endpoint` are optional overrides; when
/// absent/empty, Rust resolves them from `<app-data>/llm.json`.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PublishRequest {
    pub bundle: PublishBundle,
    pub token: Option<String>,
    pub endpoint: Option<String>,
}

/// Minimal projection of `LlmConfig` (`llm.rs`) — only the Ovion Cloud fields
/// publish needs. Both default to empty so a missing/partial `llm.json` still
/// deserializes and the explicit empty-token guard fires cleanly.
#[derive(Debug, Clone, Default, Deserialize)]
struct PublishConfig {
    #[serde(default)]
    ovion_cloud_endpoint: String,
    #[serde(default)]
    ovion_cloud_token: String,
}

/// Read the Ovion Cloud token + endpoint from `<app-data>/llm.json` (the same
/// file `llm.rs::load_config` reads). Self-contained — does not touch `llm.rs`
/// internals; only the shared `commands::llm_config_path` helper.
fn load_publish_config(app: &AppHandle) -> PublishConfig {
    match llm_config_path(app) {
        Ok(path) if path.exists() => {
            let raw = std::fs::read_to_string(&path).unwrap_or_default();
            serde_json::from_str(&raw).unwrap_or_default()
        }
        _ => PublishConfig::default(),
    }
}

/// Resolve the effective endpoint: request override → config → built-in default.
fn resolve_endpoint(request: &PublishRequest, cfg: &PublishConfig) -> String {
    resolve_endpoint_from(&request.endpoint, cfg)
}

/// Resolve the endpoint from a raw override Option + config. Shared by
/// `publish_site` and `submit_form`.
fn resolve_endpoint_from(override_endpoint: &Option<String>, cfg: &PublishConfig) -> String {
    if let Some(ep) = override_endpoint.as_ref() {
        let trimmed = ep.trim();
        if !trimmed.is_empty() {
            return trimmed.trim_end_matches('/').to_string();
        }
    }
    let ep = cfg.ovion_cloud_endpoint.trim();
    if !ep.is_empty() {
        return ep.trim_end_matches('/').to_string();
    }
    DEFAULT_OVION_CLOUD_ENDPOINT.to_string()
}

/// Resolve the effective token: request override → config. Empty → None.
fn resolve_token(request: &PublishRequest, cfg: &PublishConfig) -> Option<String> {
    resolve_token_from(&request.token, cfg)
}

/// Resolve the token from a raw override Option + config. Shared by
/// `publish_site` and `submit_form` so the forms path does not need to
/// construct a full `PublishRequest` just to resolve auth.
fn resolve_token_from(override_token: &Option<String>, cfg: &PublishConfig) -> Option<String> {
    if let Some(t) = override_token.as_ref() {
        let trimmed = t.trim();
        if !trimmed.is_empty() {
            return Some(trimmed.to_string());
        }
    }
    let t = cfg.ovion_cloud_token.trim();
    if !t.is_empty() {
        Some(t.to_string())
    } else {
        None
    }
}

/// Publish a static HTML bundle to Ovion Cloud. Returns `{ share_url }` on
/// success. Mirrors the `llm::llm_generate` pattern: `#[tauri::command] async
/// fn ... -> Result<serde_json::Value, String>`, a `reqwest::Client::builder()`
/// with timeout + user_agent, and `.map_err(|e| ...)` error mapping.
#[tauri::command]
pub async fn publish_site(
    app: AppHandle,
    request: PublishRequest,
) -> Result<serde_json::Value, String> {
    let cfg = load_publish_config(&app);
    let token = resolve_token(&request, &cfg)
        .ok_or_else(|| {
            "Ovion Cloud not configured — add your token in AI Settings.".to_string()
        })?;
    let endpoint = resolve_endpoint(&request, &cfg);
    let url = format!("{endpoint}/sites/publish");

    // Mirror the reqwest client builder from `llm.rs` (e.g. the
    // `llm_generate` fetch client at ~line 2266):
    //   reqwest::Client::builder()
    //       .timeout(Duration::from_secs(30))
    //       .user_agent("PenpotDesktop/1.0 (design-fetch)")
    //       .build()
    //       .map_err(|e| format!("fetch client build failed: {e}"))?;
    let client = reqwest::Client::builder()
        .timeout(Duration::from_secs(120))
        .user_agent("OvionDesktop/1.0 (publish)")
        .build()
        .map_err(|e| format!("publish client build failed: {e}"))?;

    let body = serde_json::to_value(&request.bundle)
        .map_err(|e| format!("could not serialize publish bundle: {e}"))?;

    let resp = client
        .post(&url)
        .bearer_auth(&token)
        .json(&body)
        .send()
        .await
        .map_err(|e| format!("publish request failed: {e}"))?;

    let status = resp.status();
    let text = resp.text().await.unwrap_or_default();

    if status.is_success() {
        // Prefer a parsed `share_url` field; fall back to the raw body string
        // (some servers return a plain URL or a JSON we don't model here).
        let parsed: serde_json::Value = serde_json::from_str(&text).unwrap_or(serde_json::Value::Null);
        let share_url = parsed
            .get("share_url")
            .and_then(|v| v.as_str())
            .map(|s| s.to_string())
            .unwrap_or_else(|| text.clone());
        Ok(serde_json::json!({ "share_url": share_url }))
    } else {
        Err(text)
    }
}

/// Submit a native form's payload to Ovion Cloud (P1.23 native-forms half).
///
/// Mirrors `publish_site`: the same Ovion Cloud Bearer-auth transport, the
/// same `llm.json` token/endpoint resolution (via `load_publish_config`), and
/// the same `reqwest::Client::builder()` pattern. POSTs
/// `{form_name, payload}` as JSON to `{endpoint}/forms/submit`. Returns the
/// server's parsed JSON response on success, or the response body as the
/// `Err` string on failure.
#[tauri::command]
pub async fn submit_form(
    app: AppHandle,
    request: SubmitFormRequest,
) -> Result<serde_json::Value, String> {
    let cfg = load_publish_config(&app);
    let token = resolve_token_from(&request.token, &cfg)
        .ok_or_else(|| {
            "Ovion Cloud not configured — add your token in AI Settings.".to_string()
        })?;
    let endpoint = resolve_endpoint_from(&request.endpoint, &cfg);
    let url = format!("{endpoint}/forms/submit");

    let client = reqwest::Client::builder()
        .timeout(Duration::from_secs(60))
        .user_agent("OvionDesktop/1.0 (forms)")
        .build()
        .map_err(|e| format!("forms client build failed: {e}"))?;

    let body = serde_json::json!({
        "form_name": request.form_name,
        "payload": request.payload,
    });

    let resp = client
        .post(&url)
        .bearer_auth(&token)
        .json(&body)
        .send()
        .await
        .map_err(|e| format!("form submit request failed: {e}"))?;

    let status = resp.status();
    let text = resp.text().await.unwrap_or_default();

    if status.is_success() {
        // Return the server's parsed JSON verbatim (a success envelope the
        // frontend displays); fall back to a generic confirmation when the
        // body is not JSON.
        let parsed: serde_json::Value =
            serde_json::from_str(&text).unwrap_or(serde_json::Value::Null);
        if parsed.is_null() {
            Ok(serde_json::json!({ "ok": true, "message": text }))
        } else {
            Ok(parsed)
        }
    } else {
        Err(text)
    }
}