// Penpot Desktop — P0.19 Sync external React component libraries (Storybook).
//
// One `#[tauri::command]` backs the "Storybook" sync section of the assets
// panel:
//
//   • `storybook_fetch` — fetches a Storybook stories index (`index.json` for
//     Storybook 7+, or `stories.json` for older builds) from a user-supplied
//     base URL and returns the raw JSON. The frontend parses the index (see
//     `data/workspace/storybook.cljs` `parse-stories`) and registers each
//     story as a code-component entry via the P0.14 host.
//
// Uses reqwest **blocking** (the `blocking` feature is already enabled in
// Cargo.toml) and a 30 s timeout, mirroring `stock_assets.rs`. Tauri v2 runs
// each non-async `#[tauri::command]` on the tokio blocking pool, so a
// blocking reqwest call is safe and does not stall the UI thread.
//
// Sentinels (matched exactly by the frontend):
//   • empty URL  → `Err("storybook-url-missing")`  (no network call)
//   • any fetch/HTTP/parse failure → `Err("storybook-fetch-failed")`
//
// `byte-identical-when-inactive`: this command is only invoked when the user
// clicks "Sync Storybook" in the assets panel. No sync = no network = no
// change to the file.

use std::time::Duration;

use serde_json::{json, Value};

// 30 s — Storybook hosts are usually internal CI/CD servers; this only
// guards against a totally hung network. Matches `stock_assets.rs`.
const REQUEST_TIMEOUT: Duration = Duration::from_secs(30);

/// Build a blocking reqwest client. Mirrors `stock_assets.rs`'s
/// `blocking_client()` helper.
fn blocking_client() -> Result<reqwest::blocking::Client, String> {
    reqwest::blocking::Client::builder()
        .timeout(REQUEST_TIMEOUT)
        .user_agent("OvionDesktop/1.0 (storybook-sync)")
        .build()
        .map_err(|e| format!("storybook client build failed: {e}"))
}

/// Resolve a user-supplied base URL to the stories index URL.
///
/// Storybook 7+ serves `index.json` at the Storybook root; older builds
/// serve `stories.json`. The user gives us a base URL (e.g.
/// `https://storybook.internal` or `https://host/subpath`). We:
///   • if the URL already points at a `.json` file, use it verbatim;
///   • otherwise append `/index.json` (ensuring exactly one slash).
fn resolve_index_url(base: &str) -> String {
    let trimmed = base.trim();
    let lower = trimmed.to_ascii_lowercase();
    if lower.ends_with(".json") {
        // User gave an explicit stories JSON URL — honor it verbatim.
        return trimmed.to_string();
    }
    // Append `/index.json`. Strip any trailing '/' so we don't get `//`.
    let without_trailing = trimmed.trim_end_matches('/');
    format!("{without_trailing}/index.json")
}

/// `storybook_fetch` — fetch the Storybook stories index from a base URL.
///
/// Accepts a base URL (e.g. `https://storybook.internal` or
/// `https://host/subpath` or an explicit `…/index.json` / `…/stories.json`
/// URL). Returns the raw parsed JSON `Value` (the stories index payload).
///
/// On empty URL returns the stable sentinel `"storybook-url-missing"` (no
/// network call). On any HTTP / parse / network failure returns the stable
/// sentinel `"storybook-fetch-failed"` so the frontend can render a single
/// graceful error state without inspecting the message. (The full cause is
/// not surfaced to keep the error contract simple and match the
/// `pexels-key-invalid` convention in `stock_assets.rs`.)
#[tauri::command]
pub fn storybook_fetch(url: String) -> Result<Value, String> {
    let base = url.trim();
    if base.is_empty() {
        // Stable sentinel — the frontend matches this exact string.
        return Err("storybook-url-missing".to_string());
    }

    let index_url = resolve_index_url(base);
    let client = blocking_client()?;

    let resp = match client.get(&index_url).send() {
        Ok(r) => r,
        Err(_) => {
            // Network failure (DNS, connection refused, timeout, …). Stable
            // sentinel; the cause is not surfaced to keep the error contract
            // simple (matches the `pexels-key-invalid` convention).
            return Err("storybook-fetch-failed".to_string());
        }
    };
    let status = resp.status();
    let text = resp.text().unwrap_or_default();
    if !status.is_success() {
        // Non-2xx — most likely a wrong base URL or auth-gated Storybook.
        return Err("storybook-fetch-failed".to_string());
    }

    let parsed: Value = match serde_json::from_str(&text) {
        Ok(v) => v,
        Err(_) => {
            // The endpoint returned non-JSON (e.g. an HTML login page).
            return Err("storybook-fetch-failed".to_string());
        }
    };

    // Wrap with a `url` echo so the frontend can show "synced from <url>"
    // without re-deriving the resolved index URL. Purely cosmetic.
    Ok(json!({ "index": parsed, "sourceUrl": index_url }))
}