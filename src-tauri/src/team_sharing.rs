// Penpot Desktop — P2.37 Team sharing: webhook integrations.
//
// `#[tauri::command]` backing the Slack/Teams/Jira/Confluence webhook
// integration in the comments menu. The frontend holds the webhook URL +
// provider in browser localStorage (`ovion.webhook-url` /
// `ovion.webhook-provider`) and passes them to `post_webhook` on each send.
//
// `post_webhook(url, payload)` POSTs `payload` (a JSON string already shaped
// for the target provider by the CLJS layer) to `url` via reqwest blocking
// (Content-Type application/json), returning the response body text on
// success. Mirrors the `stock_assets.rs` convention:
//   • empty `url`  -> `Err("webhook-url-missing")`   (no network call)
//   • network / non-2xx -> `Err("webhook-post-failed")`
// The frontend matches these exact sentinel strings to render the right
// empty/error state, exactly like `pexels-key-missing` / `pexels-key-invalid`.
//
// Tauri v2 runs each non-async `#[tauri::command]` on the tokio blocking
// pool, so a blocking reqwest call is safe here and does not stall the UI
// thread (same reasoning as `stock_assets::stock_search_photos`).

use std::time::Duration;

use serde_json::Value;

// 30 s — webhooks are fire-and-forget; this only guards against a hung
// endpoint. Matches the `REQUEST_TIMEOUT` in `stock_assets.rs`.
const REQUEST_TIMEOUT: Duration = Duration::from_secs(30);

/// Build a blocking reqwest client. Mirrors `stock_assets::blocking_client`
/// but with a webhook-specific user-agent.
fn blocking_client() -> Result<reqwest::blocking::Client, String> {
    reqwest::blocking::Client::builder()
        .timeout(REQUEST_TIMEOUT)
        .user_agent("OvionDesktop/1.0 (team-sharing)")
        .build()
        .map_err(|_| "webhook-post-failed".to_string())
}

/// `post_webhook` — POST a provider-shaped JSON payload to an incoming
/// webhook URL (Slack/Teams/Jira/Confluence).
///
/// `url` is the full incoming-webhook URL the user configured in the
/// comments menu. `payload` is a JSON string the CLJS layer has already
/// shaped for the target provider:
///   • Slack: `{"text": "<body>"}` (Slack's incoming-webhook contract)
///   • Teams/Jira/Confluence: `{"provider": "<p>", "author": "...",
///                              "body": "...", "shapeId": "..."}`
///
/// Returns the response body text on a 2xx response. On any failure returns
/// a stable sentinel string the frontend matches exactly:
///   • `"webhook-url-missing"`  — `url` is empty/whitespace (no network call)
///   • `"webhook-post-failed"`  — request build/send error OR non-2xx status
///
/// No key gating is needed here (unlike `stock_search_photos`): the webhook
/// URL itself is the credential, and an empty URL is the "not configured"
/// state. The frontend never calls this with an empty URL from the menu's
/// "Send" button (it shows the configure state instead), but the guard is
/// kept for direct invoke safety.
#[tauri::command]
pub fn post_webhook(url: String, payload: String) -> Result<String, String> {
    let trimmed = url.trim();
    if trimmed.is_empty() {
        return Err("webhook-url-missing".to_string());
    }

    let client = blocking_client()?;

    let resp = client
        .post(trimmed)
        .header("Content-Type", "application/json")
        .body(payload)
        .send()
        .map_err(|_| "webhook-post-failed".to_string())?;

    let status = resp.status();
    let text = resp.text().unwrap_or_default();
    if !status.is_success() {
        return Err("webhook-post-failed".to_string());
    }

    // `Value` is pulled in only to keep the `json!`/`Value` ergonomics
    // consistent with the rest of this crate; we return the raw body text
    // (Slack/Teams typically return "ok" or a small JSON envelope the
    // frontend ignores — it only checks Ok vs Err sentinel).
    let _ = Value::Null;

    Ok(text)
}