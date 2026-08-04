// Penpot Desktop — Feature 1 font commands (optional offline download).
//
// Google Fonts load ONLINE by default via the proxy's `/internal/gfonts/*`
// routes (proxy.rs), which fetch from fonts.googleapis.com / gstatic.com over
// TLS and transparently cache font files under the app-data fonts cache. This
// command lets a user optionally PRE-CACHE a whole family — the CSS2 response
// plus every variant font file plus the menu-preview TTF — into that same
// cache, so the family then works fully OFFLINE. The proxy serves the cached
// files at the same URLs the SPA already uses, so the frontend needs no
// offline/online branching.

use tauri::AppHandle;

use crate::commands::fonts_cache_dir;

/// MUST stay identical to `proxy::slugify` so the cached CSS filename matches
/// the one the proxy's `/internal/gfonts/css` route looks for.
fn slugify(s: &str) -> String {
    s.chars()
        .map(|c| if c.is_ascii_alphanumeric() { c.to_ascii_lowercase() } else { '-' })
        .collect::<String>()
        .trim_matches('-')
        .to_string()
}

/// A shared blocking HTTP client for offline-download fetches. The command
/// runs on a Tauri async-runtime thread, but `reqwest::blocking` is safe here
/// because the command is a plain (non-async) `#[tauri::command]` — Tauri runs
/// sync commands on a worker thread, not on the async runtime.
fn blocking_client() -> &'static reqwest::blocking::Client {
    use std::sync::OnceLock;
    static CLI: OnceLock<reqwest::blocking::Client> = OnceLock::new();
    CLI.get_or_init(|| {
        reqwest::blocking::Client::builder()
            .timeout(std::time::Duration::from_secs(60))
            .user_agent("PenpotDesktop/1.0 (fonts-download)")
            .build()
            .expect("fonts blocking client build")
    })
}

/// Extract `https://fonts.gstatic.com/s/…` URLs from a googleapis CSS2
/// response without pulling the `regex` crate. The frontend rewrites these to
/// the proxy route, so caching the bytes at `<rest>` lets the proxy serve them
/// offline.
fn extract_gstatic_urls(css: &str) -> Vec<String> {
    const NEEDLE: &str = "https://fonts.gstatic.com/s/";
    let mut out = Vec::new();
    let bytes = css.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        if let Some(rel) = css[i..].find(NEEDLE) {
            let start = i + rel;
            let after = start + NEEDLE.len();
            let mut end = after;
            while end < bytes.len() {
                let c = bytes[end];
                if c == b')' || c == b' ' || c == b'\t' || c == b'\n'
                    || c == b'\r' || c == b'"' || c == b'\'' {
                    break;
                }
                end += 1;
            }
            let url = format!("{NEEDLE}{}", &css[after..end]);
            if !out.contains(&url) {
                out.push(url);
            }
            i = end;
        } else {
            break;
        }
    }
    out
}

/// Pre-cache a Google Font family for offline use.
///
/// `query` is the EXACT css2 query the SPA uses to load the family
/// (`family=ABeeZee:regular,italic&display=block`), so the cached CSS is
/// written to the filename the proxy's `/internal/gfonts/css` route looks for
/// — the family then loads offline with no SPA changes. `menu_url` is the
/// baked menu-preview TTF URL (from the gfonts JSON) so the font picker's
/// in-its-own-typeface preview also works offline.
#[tauri::command]
pub fn fonts_download_family(
    app: AppHandle,
    query: String,
    menu_url: Option<String>,
) -> Result<String, String> {
    let cache_dir = fonts_cache_dir(&app)?;
    let client = blocking_client();

    // 1. Fetch + cache the CSS2 response.
    let css_url = format!("https://fonts.googleapis.com/css2?{query}");
    let css = client
        .get(&css_url)
        .send()
        .map_err(|e| format!("fetch css failed: {e}"))?
        .text()
        .map_err(|e| format!("read css failed: {e}"))?;
    let slug = slugify(&format!("css-{query}"));
    let css_dir = cache_dir.join("css");
    std::fs::create_dir_all(&css_dir)
        .map_err(|e| format!("create css cache dir: {e}"))?;
    std::fs::write(css_dir.join(format!("{slug}.css")), &css)
        .map_err(|e| format!("write css cache: {e}"))?;

    // 2. Fetch + cache every font file referenced by the CSS.
    let mut font_count = 0u32;
    for url in extract_gstatic_urls(&css) {
        let rest = match url.strip_prefix("https://fonts.gstatic.com/s/") {
            Some(r) => r,
            None => continue,
        };
        // Path-traversal guard — never let a crafted URL escape the cache dir.
        if rest.split(|c| c == '/' || c == '\\').any(|seg| seg == "..") {
            continue;
        }
        let dest = cache_dir.join("font").join(rest);
        if dest.exists() {
            font_count += 1;
            continue;
        }
        if let Some(parent) = dest.parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        match client.get(&url).send() {
            Ok(resp) if resp.status().is_success() => match resp.bytes() {
                Ok(b) => {
                    let _ = std::fs::write(&dest, b.to_vec());
                    font_count += 1;
                }
                Err(e) => eprintln!("[fonts] read {url} failed: {e}"),
            },
            Ok(resp) => eprintln!("[fonts] {url} returned {}", resp.status()),
            Err(e) => eprintln!("[fonts] fetch {url} failed: {e}"),
        }
    }

    // 3. Cache the menu preview TTF (picker "font name in its own typeface").
    if let Some(menu) = menu_url.as_deref() {
        if let Some(rest) = menu.strip_prefix("https://fonts.gstatic.com/s/") {
            if !rest.split(|c| c == '/' || c == '\\').any(|seg| seg == "..") {
                let dest = cache_dir.join("font").join(rest);
                if !dest.exists() {
                    if let Some(parent) = dest.parent() {
                        let _ = std::fs::create_dir_all(parent);
                    }
                    if let Ok(resp) = client.get(menu).send() {
                        if resp.status().is_success() {
                            if let Ok(b) = resp.bytes() {
                                let _ = std::fs::write(&dest, b.to_vec());
                            }
                        }
                    }
                }
            }
        }
    }

    Ok(format!("cached {font_count} font file(s) for offline use"))
}