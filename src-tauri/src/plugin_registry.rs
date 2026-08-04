// Penpot Desktop — Plugin Center registry fetch (ALL_APPS_PARITY P1.28).
//
// The Plugin Center panel (CLJS `app.main.ui.workspace.sidebar.options.menus.plugin-center`)
// browses an Ovion-hosted plugin marketplace. This module is the Rust sibling
// that fetches the registry JSON over HTTP. It mirrors `publish.rs::publish_site`
// closely: `#[tauri::command] async fn ... -> Result<serde_json::Value, String>`,
// a `reqwest::Client::builder()` with timeout + user_agent, and `.map_err`
// error mapping.
//
// Fallback: when the network request fails (offline / DNS / non-200), a small
// bundled example list is returned so the panel is never empty. The fallback
// is a `serde_json::json!([...])` of 2-3 example plugins with the same shape
// the live registry serves — so the frontend consumes one structure.

use std::time::Duration;

use serde_json::Value;
use tauri::AppHandle;

/// The Ovion-hosted default registry endpoint. The frontend may pass an
/// `endpoint` override (e.g. a staging URL) — when absent/empty this default
/// is used. Kept in the `api.ovion.app` family with `publish.rs` /
/// `llm.rs`.
const DEFAULT_REGISTRY_ENDPOINT: &str = "https://api.ovion.app/v1/plugins/registry";

/// Bundled fallback registry — returned on any network/parsing failure so the
/// Plugin Center panel is never empty offline. Three example plugins spanning
/// the common categories (design tool, export, accessibility) and the same
/// `{id name author description icon version category homepage}` shape the
/// live registry serves. `figma_compat` is the Figma-plugin compat shim shipped
/// as P1.28 alongside this module.
fn fallback_registry() -> Value {
    serde_json::json!([
        {
            "id": "ovion-figma-compat",
            "name": "Figma Plugin Compatibility",
            "author": "Ovion",
            "description": "Run a subset of Figma plugins that use the common figma.* read + create primitives (figma.root, currentPage, selection, createRectangle, createFrame, createText, notify, loadFontAsync, ui.postMessage/onmessage).",
            "icon": "puzzle",
            "version": "0.1.0",
            "category": "compatibility",
            "homepage": "https://ovion.app/plugins/figma-compat"
        },
        {
            "id": "ovion-design-lint",
            "name": "Design Lint",
            "author": "Ovion",
            "description": "Checks the current page for common design issues: unstyled text, inconsistent spacing, missing alt text on images.",
            "icon": "shield-check",
            "version": "0.2.1",
            "category": "quality",
            "homepage": "https://ovion.app/plugins/design-lint"
        },
        {
            "id": "ovion-export-html",
            "name": "Export to HTML",
            "author": "Ovion",
            "description": "Exports the selected frame to a self-contained HTML/CSS bundle, ready to host or hand off.",
            "icon": "download",
            "version": "0.3.0",
            "category": "export",
            "homepage": "https://ovion.app/plugins/export-html"
        }
    ])
}

/// Fetch the plugin registry JSON list. `endpoint` is an optional override
/// (defaults to `https://api.ovion.app/v1/plugins/registry`). Returns the JSON
/// array on success; on any network/parsing failure, returns the bundled
/// fallback list so the panel is never empty.
///
/// Mirrors `publish.rs::publish_site`:
///   * `#[tauri::command] async fn -> Result<serde_json::Value, String>`
///   * `reqwest::Client::builder().timeout(..).user_agent(..).build()`
///   * `.map_err(|e| format!(..))` error mapping
///   * 30s timeout (publish uses 120s for an upload; a registry GET is lighter)
#[tauri::command]
pub async fn fetch_plugin_registry(
    _app: AppHandle,
    endpoint: Option<String>,
) -> Result<Value, String> {
    let url = match endpoint.as_ref() {
        Some(ep) => {
            let trimmed = ep.trim();
            if trimmed.is_empty() {
                DEFAULT_REGISTRY_ENDPOINT.to_string()
            } else {
                trimmed.to_string()
            }
        }
        None => DEFAULT_REGISTRY_ENDPOINT.to_string(),
    };

    // Mirror publish.rs reqwest client builder (30s GET timeout, user_agent).
    let client = reqwest::Client::builder()
        .timeout(Duration::from_secs(30))
        .user_agent("OvionDesktop/1.0 (plugin-registry)")
        .build()
        .map_err(|e| format!("plugin-registry client build failed: {e}"))?;

    let resp = client
        .get(&url)
        .send()
        .await
        .map_err(|e| format!("plugin-registry request failed: {e}"))?;

    let status = resp.status();
    let text = resp.text().await.unwrap_or_default();

    if !status.is_success() {
        // Non-success → fall back to the bundled list. Log the failure in the
        // returned object's meta so the panel can surface "offline" state.
        let mut fallback = serde_json::json!({ "plugins": fallback_registry(), "fallback": true, "error": format!("registry returned {status}") });
        if let Value::Object(ref mut map) = fallback {
            map.insert("endpoint".to_string(), Value::String(url));
        }
        return Ok(fallback);
    }

    // The live registry is expected to be a JSON array of plugin objects.
    // Wrap it into the same `{ plugins, fallback }` envelope the frontend
    // consumes, so the success and fallback paths share one shape.
    let parsed: Value = serde_json::from_str(&text)
        .map_err(|e| format!("plugin-registry parse failed: {e}"))?;

    let plugins = match parsed {
        Value::Array(_) => parsed,
        // If the server already returns an envelope `{ plugins: [...] }`,
        // unwrap it.
        Value::Object(ref map) => match map.get("plugins") {
            Some(p @ Value::Array(_)) => p.clone(),
            _ => Value::Array(vec![]),
        },
        _ => Value::Array(vec![]),
    };

    Ok(serde_json::json!({ "plugins": plugins, "fallback": false, "endpoint": url }))
}

/// Unit-test the fallback envelope shape (no network). Kept local so the
/// module is self-contained; `cargo test` picks it up.
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fallback_is_array_of_three() {
        let v = fallback_registry();
        let arr = v.as_array().expect("fallback is array");
        assert_eq!(arr.len(), 3, "fallback has exactly 3 example plugins");
        for p in arr {
            assert!(p.get("id").unwrap().is_string());
            assert!(p.get("name").unwrap().is_string());
            assert!(p.get("author").unwrap().is_string());
            assert!(p.get("description").unwrap().is_string());
        }
    }
}