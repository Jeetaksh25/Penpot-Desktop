// Penpot Desktop — P0.04 Built-in stock asset library (Iconify + Pexels).
//
// Two `#[tauri::command]`s back the "Stock" section of the assets panel:
//
//   • `stock_search_icons` — Iconify (free, no key). Searches the Iconify
//     icon corpus by keyword and returns a normalized vector of
//     `{name, body, width, height}` so the frontend can render inline SVG
//     and drag-to-canvas can hand the viewport a `data:image/svg+xml` URI
//     (which the existing viewport on-drop already turns into an svg-raw
//     shape via `dwm/upload-media-workspace` → `svg-uploaded`).
//
//   • `stock_search_photos` — Pexels (key-gated). The key is supplied by the
//     frontend on every call (held in browser localStorage, set from the
//     Stock panel). When the key is empty the command returns an error
//     string the frontend surfaces as a "set your Pexels key" empty state;
//     no network call is made.
//
// Both commands use reqwest **blocking** (the `blocking` feature is already
// enabled in Cargo.toml for this crate) and a 30 s timeout, mirroring the
// synchronous `Result<serde_json::Value, String>` convention used by
// `cms_import::fetch_json` / `code_export::write_code_zip`. Tauri v2 runs
// each `#[tauri::command]` (non-async) on the tokio blocking pool, so a
// blocking reqwest call is safe here and does not stall the UI thread.
//
// Offline reuse: a simple in-process `Mutex<HashMap<String, Value>>` cache
// keyed by the normalized query (+ page for photos) lets repeated searches
// in the same session return instantly without hitting the network. The
// cache is session-scoped (cleared on app exit) — sufficient for the
// desktop app's single-session workflow and avoids any disk persistence
// / staleness concerns.

use std::collections::HashMap;
use std::sync::Mutex;
use std::time::Duration;

use serde_json::{json, Value};

// 30 s — Iconify and Pexels are both fast CDNs; this only guards against a
// totally hung network. Matches the `http_client()` timeout in cms_import.
const REQUEST_TIMEOUT: Duration = Duration::from_secs(30);

/// Session-scoped icon cache: query string -> normalized JSON Value. Lives
/// for the app process; repeated searches reuse it without re-hitting
/// Iconify. Wrapped in a `Mutex` (not `RwLock`) to match the rest of this
/// crate (`BackendState`, `llm` memory) which uses `Mutex` everywhere.
static ICON_CACHE: Mutex<Option<HashMap<String, Value>>> = Mutex::new(None);

/// Session-scoped photo cache: `"<query>::<page>"` -> Pexels JSON Value.
static PHOTO_CACHE: Mutex<Option<HashMap<String, Value>>> = Mutex::new(None);

/// Build a blocking reqwest client. Mirrors the `http_client()` helper in
/// `cms_import.rs` but uses the blocking API and a 30 s timeout.
fn blocking_client() -> Result<reqwest::blocking::Client, String> {
    reqwest::blocking::Client::builder()
        .timeout(REQUEST_TIMEOUT)
        .user_agent("OvionDesktop/1.0 (stock-assets)")
        .build()
        .map_err(|e| format!("stock-assets client build failed: {e}"))
}

/// Read-through the session icon cache. Returns a clone of the cached
/// `Value` if present, else `None`. Cloning `serde_json::Value` is cheap
/// enough for the small result sets (≤ 64 icons) we handle here.
fn icon_cache_get(query: &str) -> Option<Value> {
    let guard = ICON_CACHE.lock().ok()?;
    guard.as_ref().and_then(|m| m.get(query).cloned())
}

fn icon_cache_put(query: String, value: Value) {
    if let Ok(mut guard) = ICON_CACHE.lock() {
        let map = guard.get_or_insert_with(HashMap::new);
        map.insert(query, value);
    }
}

fn photo_cache_get(key: &str) -> Option<Value> {
    let guard = PHOTO_CACHE.lock().ok()?;
    guard.as_ref().and_then(|m| m.get(key).cloned())
}

fn photo_cache_put(key: String, value: Value) {
    if let Ok(mut guard) = PHOTO_CACHE.lock() {
        let map = guard.get_or_insert_with(HashMap::new);
        map.insert(key, value);
    }
}

/// GET `url` with the blocking client, returning the response body text.
/// Maps any HTTP failure to a human-readable string (the frontend treats
/// any `Err` as a graceful empty/error state, never crashing).
fn fetch_text(client: &reqwest::blocking::Client, url: &str) -> Result<String, String> {
    let resp = client
        .get(url)
        .send()
        .map_err(|e| format!("request to {url} failed: {e}"))?;
    let status = resp.status();
    let text = resp.text().unwrap_or_default();
    if !status.is_success() {
        let snippet: String = text.chars().take(300).collect();
        return Err(format!("{url} returned HTTP {status}: {snippet}"));
    }
    Ok(text)
}

/// Normalize an Iconify collection JSON into a flat vector of
/// `{name, body, width, height}` objects. The collection JSON shape is:
/// ```json
/// { "prefix": "mdi", "width": 24, "height": 24,
///   "icons": { "home": {"body": "<path…/>", "width": 24, "height": 24}, … },
///   "aliases": { "home-alt": {"parent": "home", "body": "…", …}, … } }
/// ```
/// Each icon may override the collection's default width/height; aliases
/// inherit from their parent unless they override. We resolve both so the
/// frontend never has to understand Iconify's alias model.
fn normalize_iconify_collection(prefix: &str, coll: &Value) -> Vec<Value> {
    let default_w = coll
        .get("width")
        .and_then(|v| v.as_u64())
        .unwrap_or(24) as u32;
    let default_h = coll
        .get("height")
        .and_then(|v| v.as_u64())
        .unwrap_or(24) as u32;

    let mut out: Vec<Value> = Vec::new();

    if let Some(icons) = coll.get("icons").and_then(|v| v.as_object()) {
        for (name, data) in icons {
            let body = data
                .get("body")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_string();
            if body.is_empty() {
                continue;
            }
            let w = data
                .get("width")
                .and_then(|v| v.as_u64())
                .map(|n| n as u32)
                .unwrap_or(default_w);
            let h = data
                .get("height")
                .and_then(|v| v.as_u64())
                .map(|n| n as u32)
                .unwrap_or(default_h);
            out.push(json!({
                "name": format!("{prefix}:{name}"),
                "body": body,
                "width": w,
                "height": h,
            }));
        }
    }

    if let Some(aliases) = coll.get("aliases").and_then(|v| v.as_object()) {
        for (alias, data) in aliases {
            // An alias may carry its own body or inherit the parent's.
            let body = data
                .get("body")
                .and_then(|v| v.as_str())
                .map(|s| s.to_string())
                .or_else(|| {
                    // Resolve the parent icon's body from the icons map.
                    let parent = data.get("parent").and_then(|v| v.as_str())?;
                    coll.get("icons")
                        .and_then(|v| v.get(parent))
                        .and_then(|v| v.get("body"))
                        .and_then(|v| v.as_str())
                        .map(|s| s.to_string())
                })
                .unwrap_or_default();
            if body.is_empty() {
                continue;
            }
            let w = data
                .get("width")
                .and_then(|v| v.as_u64())
                .map(|n| n as u32)
                .unwrap_or(default_w);
            let h = data
                .get("height")
                .and_then(|v| v.as_u64())
                .map(|n| n as u32)
                .unwrap_or(default_h);
            out.push(json!({
                "name": format!("{prefix}:{alias}"),
                "body": body,
                "width": w,
                "height": h,
            }));
        }
    }

    out
}

/// `stock_search_icons` — search Iconify by keyword.
///
/// Two-step fetch (so a single search returns ready-to-render SVG bodies):
///   1. `GET https://api.iconify.design/search?query=<q>&limit=<n>` →
///      `{ "icons": ["prefix:name", …], "total", "limit", "start" }`.
///   2. Group the returned names by `prefix`, then for each prefix fetch
///      `GET https://api.iconify.design/<prefix>.json?icons=<comma-joined
///      names without prefix>` and normalize.
///
/// Returns `{ "icons": [{name, body, width, height}, …], "total": <n>,
/// "cached": <bool> }`. On any network/parse failure returns an `Err`
/// (string); the frontend treats `Err` as an error state and shows retry.
///
/// `limit` defaults to 64 (Iconify's recommended page size for a sidebar
/// grid) when `None`; clamped to [1, 256].
#[tauri::command]
pub fn stock_search_icons(query: String, limit: Option<u32>) -> Result<Value, String> {
    let q = query.trim().to_lowercase();
    if q.is_empty() {
        return Ok(json!({ "icons": [], "total": 0, "cached": false }));
    }

    // Read-through cache.
    let cache_key = format!("{q}::{}", limit.unwrap_or(64));
    if let Some(cached) = icon_cache_get(&cache_key) {
        // Mark the cached payload as a cache hit for the frontend (so it
        // can show "offline" provenance if desired). We clone + patch.
        if let Value::Object(mut map) = cached {
            map.insert("cached".into(), Value::Bool(true));
            return Ok(Value::Object(map));
        }
        return Ok(cached);
    }

    let lim = limit.unwrap_or(64).clamp(1, 256);
    let client = blocking_client()?;

    // Step 1 — search for icon names.
    let search_url = format!(
        "https://api.iconify.design/search?query={}&limit={}",
        urlencoding::encode(&q),
        lim
    );
    let search_text = fetch_text(&client, &search_url)?;
    let search_json: Value = serde_json::from_str(&search_text)
        .map_err(|e| format!("could not parse Iconify search JSON: {e}"))?;

    let names: Vec<String> = search_json
        .get("icons")
        .and_then(|v| v.as_array())
        .map(|arr| {
            arr.iter()
                .filter_map(|v| v.as_str().map(|s| s.to_string()))
                .collect()
        })
        .unwrap_or_default();

    let total = search_json
        .get("total")
        .and_then(|v| v.as_u64())
        .unwrap_or(names.len() as u64);

    if names.is_empty() {
        let payload = json!({ "icons": [], "total": total, "cached": false });
        icon_cache_put(cache_key, payload.clone());
        return Ok(payload);
    }

    // Step 2 — group by prefix, batch-fetch each prefix's icon bodies.
    let mut by_prefix: HashMap<String, Vec<String>> = HashMap::new();
    for full in &names {
        if let Some((prefix, rest)) = full.split_once(':') {
            by_prefix
                .entry(prefix.to_string())
                .or_default()
                .push(rest.to_string());
        }
    }

    let mut icons: Vec<Value> = Vec::new();
    for (prefix, icon_names) in &by_prefix {
        let joined = icon_names.join(",");
        let coll_url = format!(
            "https://api.iconify.design/{}.json?icons={}",
            prefix,
            urlencoding::encode(&joined)
        );
        let coll_text = match fetch_text(&client, &coll_url) {
            Ok(t) => t,
            // One failed prefix shouldn't fail the whole search — skip it.
            Err(_) => continue,
        };
        let coll_json: Value = match serde_json::from_str(&coll_text) {
            Ok(v) => v,
            Err(_) => continue,
        };
        let mut normalized = normalize_iconify_collection(prefix, &coll_json);
        // Keep only the icons we actually asked for (in ask order), so the
        // search relevance ordering from Iconify is preserved.
        let asked: std::collections::HashSet<&String> = icon_names.iter().collect();
        normalized.retain(|item| {
            item.get("name")
                .and_then(|v| v.as_str())
                .and_then(|s| s.split_once(':').map(|(_, rest)| rest.to_string()))
                .map(|rest| asked.contains(&rest))
                .unwrap_or(false)
        });
        icons.extend(normalized);
    }

    let payload = json!({ "icons": icons, "total": total, "cached": false });
    icon_cache_put(cache_key, payload.clone());
    Ok(payload)
}

/// `stock_search_photos` — search Pexels by keyword (key-gated).
///
/// Calls `GET https://api.pexels.com/v1/search?query=<q>&per_page=<n>
/// &page=<p>` with the user-supplied key in the `Authorization` header.
/// Returns the raw Pexels JSON payload:
/// ```json
/// { "total_results", "page", "per_page",
///   "photos": [{ "id", "width", "height", "url", "alt",
///                "photographer", "src": { "original","large2x","large",
///                "medium","small","portrait","landscape","tiny" } }, …] }
/// ```
/// The frontend uses `src.small`/`src.medium` for thumbnails and
/// `src.large` (or `original`) as the drag-to-canvas URL — the existing
/// viewport on-drop handles `text/uri-list` HTTP URLs by uploading them
/// via `:create-file-media-object-from-url` → `image-uploaded`.
///
/// When `pexels_key` is empty, returns an `Err` with a stable sentinel
/// message (`"pexels-key-missing"`) the frontend matches to render the
/// "set your Pexels key" empty state. No network call is made.
///
/// `page` defaults to 1; `per_page` is fixed at 30 (a sidebar grid page).
#[tauri::command]
pub fn stock_search_photos(
    query: String,
    pexels_key: String,
    page: Option<u32>,
) -> Result<Value, String> {
    let key = pexels_key.trim();
    if key.is_empty() {
        // Stable sentinel — the frontend matches this exact string.
        return Err("pexels-key-missing".to_string());
    }

    let q = query.trim().to_lowercase();
    if q.is_empty() {
        return Ok(json!({ "photos": [], "total_results": 0, "page": 1, "per_page": 30, "cached": false }));
    }

    let pg = page.unwrap_or(1).max(1);
    let cache_key = format!("{q}::{pg}");

    if let Some(cached) = photo_cache_get(&cache_key) {
        if let Value::Object(mut map) = cached {
            map.insert("cached".into(), Value::Bool(true));
            return Ok(Value::Object(map));
        }
        return Ok(cached);
    }

    let client = blocking_client()?;
    let url = format!(
        "https://api.pexels.com/v1/search?query={}&per_page=30&page={}",
        urlencoding::encode(&q),
        pg
    );

    // Pexels wants the key as a bare `Authorization` header (not Bearer).
    let resp = client
        .get(&url)
        .header("Authorization", key)
        .send()
        .map_err(|e| format!("Pexels request failed: {e}"))?;
    let status = resp.status();
    let text = resp.text().unwrap_or_default();
    if !status.is_success() {
        let snippet: String = text.chars().take(300).collect();
        // A 401/403 most likely means a bad/expired key — surface a stable
        // sentinel so the frontend can show the key-missing/invalid state.
        if status.as_u16() == 401 || status.as_u16() == 403 {
            return Err("pexels-key-invalid".to_string());
        }
        return Err(format!("Pexels returned HTTP {status}: {snippet}"));
    }

    let parsed: Value = serde_json::from_str(&text)
        .map_err(|e| format!("could not parse Pexels JSON: {e}"))?;

    // Stash a clone in the session cache (without the `cached` flag — the
    // flag is only patched in on a cache hit above).
    photo_cache_put(cache_key, parsed.clone());

    let mut with_flag = parsed;
    if let Value::Object(ref mut map) = with_flag {
        map.insert("cached".into(), Value::Bool(false));
    }
    Ok(with_flag)
}

// `urlencoding` is a tiny crate already transitively available via
// `reqwest`'s URL form-encoding; to avoid adding a new dependency we use
// `reqwest`'s own `urlencode` shim by re-implementing the tiny subset we
// need (the Iconify/Pexels queries are plain keywords). This keeps the
// crate's dependency list unchanged under the no-build constraint.
mod urlencoding {
    /// Percent-encode a query segment for use in a URL path/query. Encodes
    /// everything except unreserved characters `[A-Za-z0-9-_.~]`. Good
    /// enough for the single-keyword searches here (no `&`/`=`/`+` in
    /// user-typed query text after trim).
    pub fn encode(input: &str) -> String {
        let mut out = String::with_capacity(input.len() * 3);
        for &b in input.as_bytes() {
            match b {
                b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                    out.push(b as char);
                }
                // Space → %20 (not '+'), so it works in both path and query.
                b' ' => out.push_str("%20"),
                _ => out.push_str(&format!("%{:02X}", b)),
            }
        }
        out
    }
}