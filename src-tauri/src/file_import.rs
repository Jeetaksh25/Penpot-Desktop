// Penpot Desktop — P1.22 Import .sketch and .fig files (ALL_APPS_PARITY).
//
// Two `#[tauri::command]`s back the workspace "Import…" entry:
//
//   • `import_sketch(path)` — a `.sketch` file is a ZIP whose entries are
//     JSON documents (`document.json`, `pages/<uuid>.json`, …) plus binary
//     image refs under `images/<id>.png`. We open the ZIP with the `zip`
//     crate (already in Cargo.toml with the `deflate` feature), parse
//     `document.json` for the page list, parse each page JSON for its layer
//     tree, and base64-encode every image ref. The result is a normalized
//     JSON tree the CLJS layer (`data.workspace.file-import`) walks into a
//     Penpot DesignSpec via `convert-sketch->spec`:
//
//       { "pages": [{ "name": "Page 1", "layers": [<raw sketch layer>, …] }, …],
//         "images": { "<id>": "<base64-png>", … } }
//
//   • `import_figma(file_key, figma_token)` — calls the Figma REST API
//     `GET https://api.figma.com/v1/files/<file_key>` with the
//     `X-Figma-Token: <token>` header and returns the raw Figma document
//     JSON. The token is user-supplied (held in browser localStorage under
//     `ovion.figma-token`, mirroring the Pexels key pattern in
//     `stock_assets.rs`/`stock_assets.cljs`). An empty token returns the
//     stable sentinel `Err("figma-token-missing")`; HTTP 401/403 returns
//     `Err("figma-token-invalid")` — both matched by the frontend to render
//     the right empty state (no call is made when the key is missing).
//
// Both commands use reqwest **blocking** (the `blocking` feature is already
// enabled in Cargo.toml) and a 30 s timeout, mirroring `stock_assets.rs`
// and `cms_import::fetch_json`. Tauri v2 runs each non-async
// `#[tauri::command]` on the tokio blocking pool, so blocking reqwest + a
// blocking ZIP read are safe here and never stall the UI thread.
//
// Byte-identical-when-inactive: import is a user action (File menu / import
// dialog). No import = no file is opened and no network call is made; this
// module is purely additive and only runs when the frontend invokes it.

use std::fs::File;
use std::io::Read;
use std::path::Path;
use std::time::Duration;

use serde_json::{json, Value};
use zip::ZipArchive;

/// 30 s — mirrors `stock_assets::REQUEST_TIMEOUT` and `cms_import`'s
/// `http_client()`. Only guards against a hung network / huge file.
const REQUEST_TIMEOUT: Duration = Duration::from_secs(30);

// ── base64 (tiny self-contained encoder) ─────────────────────────────────────
//
// No `base64` crate is in Cargo.toml and we cannot add deps under the
// no-build constraint, so we ship a minimal standard-alphabet encoder. The
// image refs we encode are Sketch PNGs (arbitrary bytes); this is the
// canonical RFC 4648 §4 table with `=` padding. Output size is modest
// (Sketch images are usually small per-layer assets).

const B64_TABLE: &[u8; 64] =
    b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

fn base64_encode(bytes: &[u8]) -> String {
    let mut out = String::with_capacity((bytes.len() + 2) / 3 * 4);
    let mut i = 0;
    while i + 3 <= bytes.len() {
        let b0 = bytes[i] as u32;
        let b1 = bytes[i + 1] as u32;
        let b2 = bytes[i + 2] as u32;
        let n = (b0 << 16) | (b1 << 8) | b2;
        out.push(B64_TABLE[((n >> 18) & 0x3f) as usize] as char);
        out.push(B64_TABLE[((n >> 12) & 0x3f) as usize] as char);
        out.push(B64_TABLE[((n >> 6) & 0x3f) as usize] as char);
        out.push(B64_TABLE[(n & 0x3f) as usize] as char);
        i += 3;
    }
    let rem = bytes.len() - i;
    if rem == 1 {
        let n = (bytes[i] as u32) << 16;
        out.push(B64_TABLE[((n >> 18) & 0x3f) as usize] as char);
        out.push(B64_TABLE[((n >> 12) & 0x3f) as usize] as char);
        out.push('=');
        out.push('=');
    } else if rem == 2 {
        let n = ((bytes[i] as u32) << 16) | ((bytes[i + 1] as u32) << 8);
        out.push(B64_TABLE[((n >> 18) & 0x3f) as usize] as char);
        out.push(B64_TABLE[((n >> 12) & 0x3f) as usize] as char);
        out.push(B64_TABLE[((n >> 6) & 0x3f) as usize] as char);
        out.push('=');
    }
    out
}

// ── Sketch ZIP parse ─────────────────────────────────────────────────────────

/// Read a single ZIP entry by name into a `String`. Returns `None` if the
/// entry is absent (Sketch files may omit pages/images); returns `Err` on a
/// read/UTF-8 failure so the caller can surface a human-readable error.
fn read_zip_text(archive: &mut ZipArchive<File>, name: &str) -> Result<Option<String>, String> {
    let mut entry = match archive.by_name(name) {
        Ok(e) => e,
        Err(zip::result::ZipError::FileNotFound) => return Ok(None),
        Err(e) => return Err(format!("could not read {name} from sketch zip: {e}")),
    };
    let mut buf = Vec::new();
    entry
        .read_to_end(&mut buf)
        .map_err(|e| format!("could not decompress {name}: {e}"))?;
    String::from_utf8(buf)
        .map(Some)
        .map_err(|e| format!("{name} is not valid UTF-8 JSON: {e}"))
}

/// Read a binary ZIP entry by name into a `Vec<u8>`. Returns `None` when the
/// entry is absent. Used for the `images/<id>.png` refs.
fn read_zip_bytes(archive: &mut ZipArchive<File>, name: &str) -> Result<Option<Vec<u8>>, String> {
    let mut entry = match archive.by_name(name) {
        Ok(e) => e,
        Err(zip::result::ZipError::FileNotFound) => return Ok(None),
        Err(e) => return Err(format!("could not read {name} from sketch zip: {e}")),
    };
    let mut buf = Vec::new();
    entry
        .read_to_end(&mut buf)
        .map_err(|e| format!("could not decompress {name}: {e}"))?;
    Ok(Some(buf))
}

/// Extract the page id list from `document.json`. Sketch stores the page
/// index under either `"_pages"` (modern) or `"pages"` (older). Each entry
/// is `{"id": "<uuid>", "name": "Page 1", …}`. Returns `[]` when neither
/// key is present so the caller degrades gracefully (no pages → empty
/// import → frontend shows "no layers found").
fn sketch_pages(document: &Value) -> Vec<(String, String)> {
    let key = if document.get("_pages").is_some() {
        "_pages"
    } else {
        "pages"
    };
    document
        .get(key)
        .and_then(|v| v.as_array())
        .map(|arr| {
            arr.iter()
                .filter_map(|p| {
                    let id = p.get("id").and_then(|v| v.as_str())?;
                    let name = p
                        .get("name")
                        .and_then(|v| v.as_str())
                        .unwrap_or("Page")
                        .to_string();
                    Some((id.to_string(), name))
                })
                .collect()
        })
        .unwrap_or_default()
}

/// `import_sketch` — open a `.sketch` ZIP, extract the page JSONs + image
/// refs, and return a normalized JSON tree for the CLJS converter.
///
/// Shape of the returned `Value`:
/// ```json
/// { "pages": [{ "name": "Page 1", "layers": [<raw sketch layer>, …] }, …],
///   "images": { "<id>": "<base64-png>", … } }
/// ```
/// `layers` is the raw Sketch layer array from each `pages/<id>.json`
/// (`page.layers`); the CLJS `convert-sketch->spec` walks it. Images are
/// keyed by the bare id (the `images/<id>.png` filename without directory
/// or extension), matching the `image.ref` value Sketch layers carry.
///
/// Errors: a missing `document.json` is fatal (`Err`); a missing page file
/// or image is skipped (defensive — one bad entry never aborts the whole
/// import). Non-UTF-8 JSON is fatal (the file is corrupt).
#[tauri::command]
pub fn import_sketch(path: String) -> Result<Value, String> {
    let p = Path::new(&path);
    if !p.exists() {
        return Err(format!("sketch file not found: {path}"));
    }
    let file = File::open(p).map_err(|e| format!("could not open {path}: {e}"))?;
    let mut archive = ZipArchive::new(file)
        .map_err(|e| format!("could not read sketch zip: {e}"))?;

    // 1. document.json — the page index.
    let doc_text = match read_zip_text(&mut archive, "document.json")? {
        Some(t) => t,
        None => return Err("sketch zip is missing document.json".to_string()),
    };
    let document: Value = serde_json::from_str(&doc_text)
        .map_err(|e| format!("could not parse document.json: {e}"))?;

    // 2. Each page JSON — `pages/<id>.json`. Skip pages that fail to parse
    //    so one corrupt page doesn't sink the whole import.
    let mut pages: Vec<Value> = Vec::new();
    for (id, name) in sketch_pages(&document) {
        let entry_name = format!("pages/{id}.json");
        let page_text = match read_zip_text(&mut archive, &entry_name) {
            Ok(Some(t)) => t,
            Ok(None) => continue, // page file absent — skip
            Err(e) => {
                eprintln!("[file_import] {entry_name}: {e}");
                continue;
            }
        };
        let page_json: Value = match serde_json::from_str(&page_text) {
            Ok(v) => v,
            Err(e) => {
                eprintln!("[file_import] {entry_name} parse error: {e}");
                continue;
            }
        };
        let layers = page_json
            .get("layers")
            .cloned()
            .unwrap_or(Value::Array(vec![]));
        pages.push(json!({ "name": name, "layers": layers }));
    }

    // 3. Image refs — every `images/<id>.png` entry. Keyed by bare id so
    //    the CLJS converter can look up `image.ref` directly.
    let names: Vec<String> = (0..archive.len())
        .filter_map(|i| {
            archive
                .by_index(i)
                .ok()
                .and_then(|e| e.name().strip_prefix("images/").map(|s| s.to_string()))
        })
        .collect();
    let mut images = serde_json::Map::new();
    for n in &names {
        let full = format!("images/{n}");
        let id = n
            .strip_suffix(".png")
            .or_else(|| n.strip_suffix(".jpg"))
            .or_else(|| n.strip_suffix(".jpeg"))
            .unwrap_or(n)
            .to_string();
        match read_zip_bytes(&mut archive, &full) {
            Ok(Some(bytes)) => {
                images.insert(id, Value::String(base64_encode(&bytes)));
            }
            Ok(None) => {}
            Err(e) => eprintln!("[file_import] {full}: {e}"),
        }
    }

    Ok(json!({ "pages": pages, "images": Value::Object(images) }))
}

// ── Figma REST API ───────────────────────────────────────────────────────────

/// Build a blocking reqwest client. Mirrors `stock_assets::blocking_client`.
fn blocking_client() -> Result<reqwest::blocking::Client, String> {
    reqwest::blocking::Client::builder()
        .timeout(REQUEST_TIMEOUT)
        .user_agent("OvionDesktop/1.0 (file-import)")
        .build()
        .map_err(|e| format!("file-import client build failed: {e}"))
}

/// `import_figma` — fetch a Figma file's document JSON via the REST API.
///
/// `GET https://api.figma.com/v1/files/<file_key>` with the
/// `X-Figma-Token: <token>` header. Returns the raw Figma document JSON
/// (the `document` node tree the CLJS `convert-figma->spec` walks).
///
/// Sentinel errors (matched by the frontend exactly):
///   • `figma-token-missing` — `figma_token` was empty/blank; no call made.
///   • `figma-token-invalid` — Figma returned HTTP 401 or 403 (bad/expired
///     token); surfaced as the "invalid key" empty state.
/// Any other failure returns a human-readable string.
#[tauri::command]
pub fn import_figma(file_key: String, figma_token: String) -> Result<Value, String> {
    let key = figma_token.trim();
    if key.is_empty() {
        return Err("figma-token-missing".to_string());
    }
    let fk = file_key.trim();
    if fk.is_empty() {
        return Err("figma-file-key-missing".to_string());
    }

    let client = blocking_client()?;
    let url = format!("https://api.figma.com/v1/files/{}", fk);

    let resp = client
        .get(&url)
        .header("X-Figma-Token", key)
        .send()
        .map_err(|e| format!("figma request failed: {e}"))?;
    let status = resp.status();
    let text = resp.text().unwrap_or_default();
    if !status.is_success() {
        let snippet: String = text.chars().take(300).collect();
        if status.as_u16() == 401 || status.as_u16() == 403 {
            return Err("figma-token-invalid".to_string());
        }
        return Err(format!("figma returned HTTP {status}: {snippet}"));
    }

    let parsed: Value = serde_json::from_str(&text)
        .map_err(|e| format!("could not parse figma JSON: {e}"))?;
    Ok(parsed)
}