// Penpot Desktop — Tauri command foundation (Foundation F1).
//
// This is the FIRST module to register `#[tauri::command]`s on the builder.
// The desktop app previously had no `.invoke_handler` at all — the frontend
// talked to the JVM backend exclusively over the same-origin HTTP/WS proxy.
// Each feature that needs shell-side work owns its own module (`fonts.rs`,
// `llm.rs`, …); this module holds the shared cross-cutting commands and
// helpers they all reuse.
//
// Convention (decided in F1): one Rust module per feature. `lib.rs` registers
// every feature's command list in a single `generate_handler![…]`.

use std::path::PathBuf;
use tauri::AppHandle;

/// The per-user config/data directory shared by all shell features.
///
/// Lives under the OS app-data dir (e.g. `%APPDATA%\Ovion Desktop` on
/// Windows, `~/.config/Ovion Desktop` on Linux, `~/Library/Application
/// Support/Ovion Desktop` on macOS) — NOT under the install dir — so it
/// survives upgrades and never bloats the installer. The LLM provider config
/// (F4) and the on-demand offline font cache (Feature 1) both live here.
///
/// Returns `Err` with a human-readable message if the OS reports no app-data
/// dir (exceptional — only on headless/embedded systems without a home).
pub fn user_data_dir(app: &AppHandle) -> Result<PathBuf, String> {
    let _ = app; // reserved for future per-app scoping; today purely OS-based.
    let base = dirs::data_dir()
        .ok_or_else(|| "could not resolve the OS app-data directory".to_string())?;
    Ok(base.join("Ovion Desktop"))
}

/// The on-demand offline font cache directory (Feature 1).
///
/// `<app-data>/fonts/gfonts/<slug>/<variant>.woff2`. Created on demand by the
/// download commands; served by the proxy's `/internal/gfonts/font/*` route
/// when present (cache hit), falling through to Google's CDN otherwise.
pub fn fonts_cache_dir(app: &AppHandle) -> Result<PathBuf, String> {
    Ok(user_data_dir(app)?.join("fonts").join("gfonts"))
}

/// The LLM provider config file path (Foundation F4): `<app-data>/llm.json`.
pub fn llm_config_path(app: &AppHandle) -> Result<PathBuf, String> {
    Ok(user_data_dir(app)?.join("llm.json"))
}

/// Trivial end-to-end bridge check. The frontend invokes this to confirm the
/// Tauri command channel is wired before depending on it for real features.
#[tauri::command]
pub fn ping() -> String {
    "pong".to_string()
}