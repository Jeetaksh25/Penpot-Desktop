// Penpot Desktop — same-origin proxy server (Rust).
//
// Replaces the Node.js serve-penpot-proxy.js in production builds.
// Listens on port 1420, reverse-proxies /api/, /internal/ and /ws/ to
// the JVM backend on port 3449.
//
// === Auto-login (production only) ===
// The proxy transparently authenticates a fixed local account
// ("penpot@localdesktop.com") so the user never sees a login screen.
// On first boot the account is registered; every later boot just re-logs in.
// The auth-token cookie is injected into every proxied HTTP request.
// Logout is intercepted and returns a no-op success response so the
// frontend's "log out" action doesn't delete the server-side session.

use std::io::{self, BufRead, BufReader, Write};
use std::net::{TcpListener, TcpStream, Shutdown};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::sync::OnceLock;
use std::thread;
use std::time::Duration;

// ── CORS ────────────────────────────────────────────────────────────────────

/// Build CORS headers that echo the request's `Origin`. The SPA is served by
/// Tauri's embedded asset server at `http://tauri.localhost` but makes its API
/// calls — with `credentials: "include"` — to this proxy at `http://localhost:1420`,
/// i.e. a cross-origin, credentialed request. The CORS spec forbids
/// `Access-Control-Allow-Origin: *` together with `Access-Control-Allow-Credentials:
/// true`; the browser rejects the response and the fetch fails, which is exactly
/// what produced the "Something wrong has happened" toast + blank screen in the
/// packaged build. (Dev is same-origin on :1420, so CORS never applied there and
/// dev kept working.) Echoing the request Origin satisfies the credentialed-
/// request rule. When there is no Origin header (same-origin / non-browser) we
/// fall back to `*`, which is valid because no credentialed cross-origin request
/// is involved.
fn cors_headers(origin: Option<&str>) -> String {
    let allow = origin.unwrap_or("*");
    format!(
        "Access-Control-Allow-Origin: {}\r\n\
         Access-Control-Allow-Methods: GET, POST, PUT, DELETE, PATCH, OPTIONS\r\n\
         Access-Control-Allow-Headers: content-type, authorization, cookie, x-client, x-frontend-version\r\n\
         Access-Control-Allow-Credentials: true\r\n",
        allow
    )
}

/// Case-insensitive lookup of a request header's value. Returns a slice into
/// the original header string, so the lifetime ties to `headers`.
fn header<'a>(headers: &'a [String], name: &str) -> Option<&'a str> {
    let prefix = format!("{}:", name.to_lowercase());
    for h in headers {
        if h.to_lowercase().starts_with(&prefix) {
            return h.splitn(2, ':').nth(1).map(|v| v.trim());
        }
    }
    None
}

// ── Proxy routes ────────────────────────────────────────────────────────────

// /assets/ is proxied (not served as a static file) so backend-hosted asset
// images (/assets/by-id/...) reach the JVM with the right Content-Type —
// matching the Node dev proxy. Without it the SPA-fallback would 404 them.
const PROXY_PREFIXES: &[&str] = &["/api/", "/internal/", "/ws/", "/assets/"];
const BACKEND_HOST: &str = "127.0.0.1";
const BACKEND_PORT: u16 = 3449;

// ── Auto-login ──────────────────────────────────────────────────────────────

const LOCAL_EMAIL: &str = "penpot@localdesktop.com";
const LOCAL_PASSWORD: &str = "penpot-local";
const LOCAL_FULLNAME: &str = "Ovion Desktop";
const SESSION_COOKIE_NAME: &str = "auth-token";

/// Global session cookie set by auto-login. Every proxied request gets this
/// injected so the backend sees an authenticated session.
static AUTO_LOGIN_COOKIE: Mutex<Option<String>> = Mutex::new(None);
use std::sync::Mutex;

/// True once auto-login has been attempted (success or failure).
static AUTO_LOGIN_DONE: AtomicBool = AtomicBool::new(false);

// ── MIME types (static file fallback) ───────────────────────────────────────

const MIME_TYPES: &[(&str, &str)] = &[
    (".html", "text/html; charset=utf-8"),
    (".js", "text/javascript; charset=utf-8"),
    (".mjs", "text/javascript; charset=utf-8"),
    (".css", "text/css; charset=utf-8"),
    (".json", "application/json; charset=utf-8"),
    (".map", "application/json; charset=utf-8"),
    (".svg", "image/svg+xml"),
    (".png", "image/png"),
    (".jpg", "image/jpeg"),
    (".gif", "image/gif"),
    (".wasm", "application/wasm"),
    (".woff", "font/woff"),
    (".woff2", "font/woff2"),
    (".ttf", "font/ttf"),
];

fn mime_type(path: &str) -> &str {
    let ext = path.rsplit('.').next().map(|e| format!(".{}", e)).unwrap_or_default();
    for (k, v) in MIME_TYPES {
        if *k == ext {
            return v;
        }
    }
    "application/octet-stream"
}

// ── Google Fonts proxy (Feature 1) ──────────────────────────────────────────
//
// See the intercept in `handle_client`. The frontend's `process-gfont-css`
// rewrites `https://fonts.gstatic.com/s` in the CSS to our `/internal/gfonts
// /font` route, so the browser asks us for the font files; we fetch them from
// gstatic (TLS) and cache them on disk. CSS is served live from googleapis
// (online) unless the optional offline download pre-cached it.

/// A shared blocking HTTP client for Google Fonts fetches. Built once (TLS
/// init is non-trivial) and reused across proxy threads. The desktop proxy
/// runs on plain std threads, so it MUST use the blocking client — the async
/// client panics if driven outside its own runtime.
fn gfonts_client() -> &'static reqwest::blocking::Client {
    static CLI: OnceLock<reqwest::blocking::Client> = OnceLock::new();
    CLI.get_or_init(|| {
        reqwest::blocking::Client::builder()
            .timeout(Duration::from_secs(30))
            .user_agent("PenpotDesktop/1.0 (gfonts-proxy)")
            .build()
            .expect("gfonts blocking client build")
    })
}

/// Lowercase-alnum slug for cache filenames (query strings, family names).
fn slugify(s: &str) -> String {
    s.chars()
        .map(|c| if c.is_ascii_alphanumeric() { c.to_ascii_lowercase() } else { '-' })
        .collect::<String>()
        .trim_matches('-')
        .to_string()
}

fn gfonts_fetch(
    client: &reqwest::blocking::Client,
    url: &str,
) -> Result<Vec<u8>, String> {
    let resp = client
        .get(url)
        .send()
        .map_err(|e| format!("gfonts fetch {url} failed: {e}"))?;
    let status = resp.status();
    let body = resp
        .bytes()
        .map_err(|e| format!("gfonts read {url} failed: {e}"))?
        .to_vec();
    if !status.is_success() {
        return Err(format!("gfonts {url} returned {status}"));
    }
    Ok(body)
}

/// `/internal/gfonts/css?family=…` → `https://fonts.googleapis.com/css2?family=…`.
/// Serves a pre-cached CSS (offline download) if present, else fetches live.
fn handle_gfonts_css(
    stream: &mut TcpStream,
    path: &str,
    cache_dir: &Path,
    client: &reqwest::blocking::Client,
    cors: &str,
) {
    let query = path.split_once('?').map(|(_, q)| q).unwrap_or("");
    let slug = slugify(&format!("css-{query}"));
    let cached = cache_dir.join("css").join(format!("{slug}.css"));
    if let Ok(data) = std::fs::read(&cached) {
        send_response(stream, "200 OK", "text/css; charset=utf-8", &data, cors);
        return;
    }
    let url = format!("https://fonts.googleapis.com/css2?{query}");
    match gfonts_fetch(client, &url) {
        Ok(body) => {
            let _ = std::fs::create_dir_all(cache_dir.join("css"));
            let _ = std::fs::write(&cached, &body);
            send_response(stream, "200 OK", "text/css; charset=utf-8", &body, cors);
        }
        Err(e) => {
            eprintln!("[penpot-proxy] gfonts css: {e}");
            send_response(stream, "502 Bad Gateway", "text/plain", e.as_bytes(), cors);
        }
    }
}

/// `/internal/gfonts/font/<rest>` → `https://fonts.gstatic.com/s/<rest>`.
/// Serves from the disk cache when present (offline), else fetches + caches.
fn handle_gfonts_font(
    stream: &mut TcpStream,
    rest: &str,
    cache_dir: &Path,
    client: &reqwest::blocking::Client,
    cors: &str,
) {
    // Path-traversal guard: a crafted `rest` must not escape the cache dir.
    if rest.split(|c| c == '/' || c == '\\').any(|seg| seg == "..") || rest.is_empty() {
        send_response(stream, "400 Bad Request", "text/plain", b"bad font path", cors);
        return;
    }
    let cached = cache_dir.join("font").join(rest);
    if let Ok(data) = std::fs::read(&cached) {
        send_response(stream, "200 OK", mime_type(rest), &data, cors);
        return;
    }
    let url = format!("https://fonts.gstatic.com/s/{rest}");
    match gfonts_fetch(client, &url) {
        Ok(body) => {
            if let Some(parent) = cached.parent() {
                let _ = std::fs::create_dir_all(parent);
            }
            let _ = std::fs::write(&cached, &body);
            send_response(stream, "200 OK", mime_type(rest), &body, cors);
        }
        Err(e) => {
            eprintln!("[penpot-proxy] gfonts font: {e}");
            send_response(stream, "502 Bad Gateway", "text/plain", e.as_bytes(), cors);
        }
    }
}

// ── HTTP helpers ────────────────────────────────────────────────────────────

fn parse_request_line(line: &str) -> Option<(&str, &str)> {
    let mut parts = line.split_whitespace();
    Some((parts.next()?, parts.next()?))
}

fn read_headers(reader: &mut dyn BufRead) -> Vec<String> {
    let mut headers = Vec::new();
    loop {
        let mut line = String::new();
        if reader.read_line(&mut line).unwrap_or(0) == 0 {
            break;
        }
        let trimmed = line.trim_end_matches("\r\n").trim_end_matches('\n');
        if trimmed.is_empty() {
            break;
        }
        headers.push(trimmed.to_string());
    }
    headers
}

// Read an HTTP message body. `response` distinguishes a backend *response*
// from a client *request* body — see the EOF note below.
fn read_body(reader: &mut dyn BufRead, headers: &[String], response: bool) -> Vec<u8> {
    // The Penpot backend emits its transit responses in one of two ways
    // depending on the request's Connection header:
    //   • keep-alive  -> `Transfer-Encoding: chunked`, no Content-Length
    //   • close       -> NO Transfer-Encoding, NO Content-Length; the body is
    //                    delimited by the server closing the socket.
    // Both `rpc_call` and `proxy_request` send `Connection: close`, so the
    // backend takes the second path. A reader that only handled Content-Length
    // (and, after the chunked fix, chunked) still returned an empty body here
    // — the real blank-screen / 401 root cause: the `prepare-register-profile`
    // token lived in a close-delimited body the proxy never read, so
    // `extract_token` saw "" and the auto-login session was never established,
    // and `proxy_request` forwarded every /api/ response with Content-Length 0,
    // blanking the SPA. Dev's Node proxy reads to EOF natively, which is why
    // dev always worked.
    let mut chunked = false;
    let mut len: usize = 0;
    for h in headers {
        let lower = h.to_lowercase();
        if lower.starts_with("transfer-encoding:") {
            if lower.contains("chunked") {
                chunked = true;
            }
        } else if lower.starts_with("content-length:") {
            if let Ok(v) = h.split(':').nth(1).map(|s| s.trim().parse::<usize>()).unwrap_or(Ok(0)) {
                len = v;
            }
        }
    }
    if chunked {
        return read_chunked(reader);
    }
    if len > 0 {
        let mut body = vec![0u8; len];
        let _ = reader.read_exact(&mut body);
        return body;
    }
    // No Content-Length and not chunked. For responses, the body runs to EOF:
    // we always send `Connection: close` to the backend, so it closes the
    // socket after the body and `read_to_end` terminates. Only for responses —
    // a request with no body on a keep-alive connection must NOT read to EOF,
    // or it would block waiting for the client to close.
    if response {
        let mut out = Vec::new();
        let _ = reader.read_to_end(&mut out);
        return out;
    }
    Vec::new()
}

/// Decode an HTTP/1.1 chunked transfer-encoded body. Each chunk is
/// `<hex-size>[;ext]\r\n<data>\r\n`; a terminating `0\r\n` chunk ends the body,
/// followed by optional trailers and a final blank line.
fn read_chunked(reader: &mut dyn BufRead) -> Vec<u8> {
    let mut out = Vec::new();
    loop {
        let mut size_line = String::new();
        if reader.read_line(&mut size_line).unwrap_or(0) == 0 {
            break;
        }
        let size_hex = size_line.trim().split(';').next().unwrap_or("").trim();
        let size = match usize::from_str_radix(size_hex, 16) {
            Ok(s) => s,
            Err(_) => break,
        };
        if size == 0 {
            // Drain any trailers up to the terminating blank line.
            loop {
                let mut trailer = String::new();
                if reader.read_line(&mut trailer).unwrap_or(0) == 0 {
                    break;
                }
                if trailer == "\r\n" || trailer == "\n" || trailer.is_empty() {
                    break;
                }
            }
            break;
        }
        let mut chunk = vec![0u8; size];
        if reader.read_exact(&mut chunk).is_err() {
            break;
        }
        out.extend_from_slice(&chunk);
        // Consume the trailing CRLF after the chunk data.
        let mut crlf = [0u8; 2];
        let _ = reader.read_exact(&mut crlf);
    }
    out
}

fn send_response(stream: &mut TcpStream, status: &str, content_type: &str, body: &[u8], cors: &str) {
    let response = format!(
        "HTTP/1.1 {}\r\n\
         Content-Type: {}\r\n\
         Content-Length: {}\r\n\
         Cache-Control: no-store\r\n\
         Connection: close\r\n\
         {}\r\n",
        status, content_type, body.len(), cors
    );
    let _ = stream.write_all(response.as_bytes());
    if !body.is_empty() {
        let _ = stream.write_all(body);
    }
    let _ = stream.flush();
}

// ── Transit+JSON encoding ──────────────────────────────────────────────────
// The Penpot backend uses transit+json for its RPC protocol. A flat string→string
// map encodes as ["^ ", "~:key1", "val1", "~:key2", "val2", ...].
// The "~:" prefix marks Clojure keywords as map keys.

fn transit_map(obj: &[(&str, &str)]) -> String {
    let mut parts = vec!["\"^ \"".to_string()];
    for (k, v) in obj {
        parts.push(format!("\"~:{}\"", k));
        // Properly JSON-escape the value.
        let escaped = serde_json::to_string(v).unwrap_or_else(|_| format!("\"{}\"", v));
        parts.push(escaped);
    }
    format!("[{}]", parts.join(","))
}

fn extract_cookie(set_cookie_headers: &[String], name: &str) -> Option<String> {
    let prefix = format!("{}=", name);
    for c in set_cookie_headers {
        if let Some(start) = c.find(&prefix) {
            let value_start = start + prefix.len();
            let end = c[value_start..].find(';').map(|i| value_start + i).unwrap_or(c.len());
            return Some(c[value_start..end].to_string());
        }
    }
    None
}

fn extract_token(body: &str) -> Option<String> {
    // transit+json: "~:token","<JWE>"
    let needle = "\"~:token\",\"";
    let start = body.find(needle)?;
    let value_start = start + needle.len();
    let end = body[value_start..].find('\"')?;
    Some(body[value_start..value_start + end].to_string())
}

/// Make a direct HTTP POST (RPC call) to the JVM backend.
fn rpc_call(method: &str, params: &[(&str, &str)]) -> Result<(u16, Vec<String>, String), String> {
    let body = transit_map(params);
    let request = format!(
        "POST /api/main/methods/{} HTTP/1.1\r\n\
         Host: {}:{}\r\n\
         Content-Type: application/transit+json\r\n\
         Accept: application/transit+json\r\n\
         X-Client: penpot-desktop\r\n\
         X-Frontend-Version: develop\r\n\
         Content-Length: {}\r\n\
         Connection: close\r\n\r\n{}",
        method, BACKEND_HOST, BACKEND_PORT, body.len(), body
    );

    let mut stream =
        TcpStream::connect((BACKEND_HOST, BACKEND_PORT)).map_err(|e| format!("connect: {}", e))?;
    stream
        .write_all(request.as_bytes())
        .map_err(|e| format!("write: {}", e))?;

    let mut reader = BufReader::new(&mut stream);
    let mut status_line = String::new();
    reader
        .read_line(&mut status_line)
        .map_err(|e| format!("read status: {}", e))?;

    let status_code = status_line
        .split_whitespace()
        .nth(1)
        .and_then(|s| s.parse::<u16>().ok())
        .unwrap_or(0);

    let response_headers = read_headers(&mut reader);
    let response_body = read_body(&mut reader, &response_headers, true);
    let body_str = String::from_utf8_lossy(&response_body).to_string();

    Ok((status_code, response_headers, body_str))
}

/// Try to log in with the fixed local account. If that fails (no profile yet),
/// register a new account and log in. Returns the `auth-token` cookie value.
fn establish_session() -> Option<String> {
    for attempt in 1..=5 {
        // Attempt 1: login-with-password.
        let (status, headers, _body) = match rpc_call(
            "login-with-password",
            &[("email", LOCAL_EMAIL), ("password", LOCAL_PASSWORD)],
        ) {
            Ok(r) => r,
            Err(e) => {
                eprintln!(
                    "[penpot-proxy] login attempt {}/5 failed: {}",
                    attempt, e
                );
                thread::sleep(Duration::from_secs(1));
                continue;
            }
        };

        if status == 200 {
            if let Some(cookie) = extract_cookie(&headers, SESSION_COOKIE_NAME) {
                eprintln!(
                    "[penpot-proxy] session established for {}",
                    LOCAL_EMAIL
                );
                return Some(cookie);
            }
        }

        // No profile yet — register.
        let prep = match rpc_call(
            "prepare-register-profile",
            &[
                ("fullname", LOCAL_FULLNAME),
                ("email", LOCAL_EMAIL),
                ("password", LOCAL_PASSWORD),
            ],
        ) {
            Ok((_, _, body)) => body,
            Err(e) => {
                eprintln!(
                    "[penpot-proxy] prepare-register attempt {}/5 failed: {}",
                    attempt, e
                );
                thread::sleep(Duration::from_millis(800));
                continue;
            }
        };

        let token = match extract_token(&prep) {
            Some(t) => t,
            None => {
                eprintln!(
                    "[penpot-proxy] could not extract registration token from response"
                );
                thread::sleep(Duration::from_millis(800));
                continue;
            }
        };

        let (reg_status, reg_headers, _) =
            match rpc_call("register-profile", &[("token", &token)]) {
                Ok(r) => r,
                Err(e) => {
                    eprintln!(
                        "[penpot-proxy] register attempt {}/5 failed: {}",
                        attempt, e
                    );
                    thread::sleep(Duration::from_secs(1));
                    continue;
                }
            };

        if reg_status == 200 {
            if let Some(cookie) = extract_cookie(&reg_headers, SESSION_COOKIE_NAME) {
                eprintln!(
                    "[penpot-proxy] registered + session established for {}",
                    LOCAL_EMAIL
                );
                return Some(cookie);
            }
        }

        thread::sleep(Duration::from_secs(1));
    }

    eprintln!("[penpot-proxy] could NOT establish auto-login session after 5 attempts");
    None
}

// ── Connection handler ──────────────────────────────────────────────────────

fn handle_client(mut stream: TcpStream, public_dir: PathBuf, storage_dir: PathBuf, fonts_cache_dir: PathBuf) {
    let mut reader = BufReader::new(&mut stream);

    // Read the request line.
    let mut request_line = String::new();
    if reader.read_line(&mut request_line).unwrap_or(0) == 0 {
        return;
    }

    let request_line = request_line.trim();
    let (method, path) = match parse_request_line(request_line) {
        Some(p) => p,
        None => {
            send_response(
                &mut stream,
                "400 Bad Request",
                "text/plain",
                b"Bad request",
                &cors_headers(None),
            );
            return;
        }
    };

    let headers = read_headers(&mut reader);
    let cors = cors_headers(header(&headers, "origin"));

    // ── CORS preflight ────────────────────────────────────────────────────
    // Echo the client's `Access-Control-Request-Headers` verbatim. The
    // Penpot SPA (served from http://tauri.localhost in the packaged build)
    // makes credentialed cross-origin API calls to this proxy on :1420 and
    // sends several custom headers — accept, x-external-session-id,
    // x-session-id, x-event-origin, x-client, x-frontend-version, content-type.
    // A fixed allow-list inevitably misses one and the browser blocks the
    // preflight, so every API call fails with net::ERR_FAILED → blank screen
    // + "Something wrong has happened". Echoing whatever was requested admits
    // them all. (Dev is same-origin on :1420, so no preflight is ever sent —
    // this only matters in the packaged build.)
    if method == "OPTIONS" {
        let allow_headers = header(&headers, "access-control-request-headers")
            .map(|h| h.to_string())
            .unwrap_or_else(|| {
                "content-type, authorization, cookie, accept, x-client, x-frontend-version, x-session-id, x-external-session-id, x-event-origin".to_string()
            });
        let origin = header(&headers, "origin").unwrap_or("*");
        let response = format!(
            "HTTP/1.1 204 No Content\r\n\
             Content-Length: 0\r\n\
             Access-Control-Allow-Origin: {origin}\r\n\
             Access-Control-Allow-Methods: GET, POST, PUT, DELETE, PATCH, OPTIONS\r\n\
             Access-Control-Allow-Headers: {allow_headers}\r\n\
             Access-Control-Allow-Credentials: true\r\n\
             Access-Control-Max-Age: 86400\r\n\
             Connection: close\r\n\r\n"
        );
        let _ = stream.write_all(response.as_bytes());
        let _ = stream.flush();
        return;
    }

    // ── WebSocket ─────────────────────────────────────────────────────────
    // Proxy the upgrade to the JVM backend (raw byte relay after the 101
    // handshake). The SPA opens ws://localhost:1420/ws/notifications for its
    // Sente realtime channel; the previous 501 here left that channel dead in
    // the packaged build (dev's Node proxy already relays WS). The webview
    // origin (http://tauri.localhost) is allowed on the backend via
    // PENPOT_ALLOWED_ORIGINS in lib.rs.
    let is_websocket = headers
        .iter()
        .any(|h| h.to_lowercase().contains("upgrade:") && h.to_lowercase().contains("websocket"));
    if is_websocket {
        drop(reader); // release the borrow on `stream` so we can move it
        handle_websocket_upgrade(stream, method, path, &headers);
        return;
    }

    // Strip query parameters before matching routes below.
    let clean_path = path.split('?').next().unwrap_or(path);

    // ── Webview diagnostic channel ────────────────────────────────────────
    // The page POSTs its window.onerror / unhandledrejection / console.error
    // messages here (script injected by inject-desktop-config.js). Surface them
    // on stderr so production webview errors are visible when the app is run
    // from a terminal — the installed app has no DevTools. Mirrors the Node
    // dev proxy; without this the POST fell through to the SPA fallback.
    if method == "POST" && clean_path == "/__desktop_log" {
        let body = read_body(&mut reader, &headers, false);
        eprintln!("[webview] {}", String::from_utf8_lossy(&body));
        send_response(&mut stream, "200 OK", "text/plain", b"ok", &cors);
        return;
    }

    // ── Logout interception ───────────────────────────────────────────────
    // The auto-login injects a session cookie on every request. If the
    // frontend's logout request reached the backend it would destroy the
    // session and bounce the user back to the login screen. Instead we
    // intercept it and return an empty success.
    if (clean_path == "/api/main/methods/logout" || clean_path == "/api/rpc/command/logout")
        && method != "OPTIONS"
    {
        send_response(&mut stream, "200 OK", "application/transit+json", b"[\"^ \"]", &cors);
        return;
    }

    // ── Google Fonts (Feature 1) ──────────────────────────────────────────
    // Intercept `/internal/gfonts/*` BEFORE the `/internal/` prefix is proxied
    // to the JVM backend — the upstream Penpot backend has NO gfonts routes
    // (they're nginx-only in hosted Penpot), so without this the font CSS/font
    // requests 404 at the backend and Google Fonts never load. We fetch from
    // fonts.googleapis.com / fonts.gstatic.com over TLS here, and cache font
    // files + (for offline-downloaded families) the CSS under the app-data
    // fonts cache so already-used fonts work offline and the optional
    // `fonts_download_family` command can pre-warm the cache for full offline.
    if method == "GET" && clean_path == "/internal/gfonts/css" {
        drop(reader); // release the borrow on `stream` before we hand it on
        handle_gfonts_css(&mut stream, path, &fonts_cache_dir, gfonts_client(), &cors);
        return;
    }
    if method == "GET" && clean_path.starts_with("/internal/gfonts/font/") {
        let rest = &clean_path["/internal/gfonts/font/".len()..];
        drop(reader);
        handle_gfonts_font(&mut stream, rest, &fonts_cache_dir, gfonts_client(), &cors);
        return;
    }

    // ── API proxy ─────────────────────────────────────────────────────────
    if PROXY_PREFIXES.iter().any(|p| path.starts_with(p)) {
        let body = read_body(&mut reader, &headers, false);
        proxy_request(&mut stream, method, path, &headers, &body, &cors, &storage_dir);
        return;
    }

    // ── Static files (fallback) ───────────────────────────────────────────
    if clean_path == "/favicon.ico" {
        let fav_png = public_dir.join("images/favicon.png");
        let fav_ico = public_dir.join("favicon.ico");
        if let Ok(data) = std::fs::read(&fav_ico).or_else(|_| std::fs::read(&fav_png)) {
            let response = format!(
                "HTTP/1.1 200 OK\r\n\
                 Content-Type: image/x-icon\r\n\
                 Content-Length: {}\r\n\
                 Cache-Control: max-age=86400\r\n\
                 Connection: close\r\n\
                 {}\r\n",
                data.len(), cors
            );
            let _ = stream.write_all(response.as_bytes());
            let _ = stream.write_all(&data);
            let _ = stream.flush();
            return;
        } else {
            send_response(&mut stream, "204 No Content", "image/x-icon", b"", &cors);
            return;
        }
    }

    let file_path = resolve_path(path, &public_dir);
    if let Ok(data) = std::fs::read(&file_path) {
        let path_str = file_path.to_string_lossy();
        let ct = mime_type(&path_str);
        // The JS/CSS/font bundles are cache-busted via `?version=…`, so a long
        // max-age is safe and avoids re-fetching ~140M of JS on every launch.
        let response = format!(
            "HTTP/1.1 200 OK\r\n\
             Content-Type: {}\r\n\
             Content-Length: {}\r\n\
             Cache-Control: max-age=3600, must-revalidate\r\n\
             Connection: close\r\n\
             {}\r\n",
            ct, data.len(), cors
        );
        let _ = stream.write_all(response.as_bytes());
        let _ = stream.write_all(&data);
        let _ = stream.flush();
    } else {
        // If request path contains a file extension (e.g. .ico, .png, .js, .css), return 404
        // instead of HTML fallback to prevent MIME type mismatch errors in the browser.
        let has_ext = clean_path.rfind('.').map_or(false, |i| {
            i > clean_path.rfind('/').unwrap_or(0) && i < clean_path.len() - 1
        });
        if has_ext {
            send_response(&mut stream, "404 Not Found", "text/plain", b"File not found", &cors);
            return;
        }

        // SPA fallback: serve index.html for client-side routing.
        let index_path = public_dir.join("index.html");
        match std::fs::read(&index_path) {
            Ok(data) => {
                let response = format!(
                    "HTTP/1.1 200 OK\r\n\
                     Content-Type: text/html; charset=utf-8\r\n\
                     Content-Length: {}\r\n\
                     Cache-Control: no-store\r\n\
                     Connection: close\r\n\
                     {}\r\n",
                    data.len(), cors
                );
                let _ = stream.write_all(response.as_bytes());
                let _ = stream.write_all(&data);
                let _ = stream.flush();
            }
            Err(_) => {
                send_response(
                    &mut stream,
                    "404 Not Found",
                    "text/plain",
                    b"index.html not found",
                    &cors,
                );
            }
        }
    }
}

fn resolve_path(path: &str, public_dir: &PathBuf) -> PathBuf {
    // Strip the query string (`/js/main.js?version=…`) before mapping to disk,
    // otherwise the `?version=…` becomes a literal filename component, the file
    // is not found, and the SPA fallback serves index.html as text/javascript.
    let path = path.split('?').next().unwrap_or(path);
    if path == "/" || path.is_empty() {
        return public_dir.join("index.html");
    }
    let decoded = url_decode_path(path.trim_start_matches('/'));
    let candidate = public_dir.join(&decoded);
    if candidate.starts_with(public_dir) {
        candidate
    } else {
        public_dir.join("index.html")
    }
}

fn url_decode_path(path: &str) -> String {
    let mut result = String::new();
    let mut chars = path.chars();
    while let Some(c) = chars.next() {
        if c == '%' {
            let hex: String = chars.by_ref().take(2).collect();
            if let Ok(byte) = u8::from_str_radix(&hex, 16) {
                result.push(byte as char);
            } else {
                result.push('%');
                result.push_str(&hex);
            }
        } else {
            result.push(c);
        }
    }
    result
}

// ── Proxy request forwarding ────────────────────────────────────────────────

fn proxy_request(
    stream: &mut TcpStream,
    method: &str,
    path: &str,
    headers: &[String],
    body: &[u8],
    cors: &str,
    storage_dir: &Path,
) {
    let backend = format!("{}:{}", BACKEND_HOST, BACKEND_PORT);
    let mut backend_stream = match TcpStream::connect(&backend) {
        Ok(s) => s,
        Err(e) => {
            send_response(
                stream,
                "502 Bad Gateway",
                "text/plain",
                &format!("Backend unreachable: {}", e).into_bytes(),
                cors,
            );
            return;
        }
    };

    // Build the forwarded request — strip and rewrite headers.
    let mut request = format!("{} {} HTTP/1.1\r\n", method, path);
    let mut has_host = false;
    for h in headers {
        let lower = h.to_lowercase();
        if lower.starts_with("host:") {
            request.push_str(&format!("Host: {}\r\n", backend));
            has_host = true;
        } else if lower.starts_with("connection:")
            || lower.starts_with("transfer-encoding:")
            || lower.starts_with("content-length:")
            || lower.starts_with("origin:")
            || lower.starts_with("cookie:")
        {
            // Skipped — we inject our own values below.
        } else {
            request.push_str(h);
            request.push_str("\r\n");
        }
    }
    if !has_host {
        request.push_str(&format!("Host: {}\r\n", backend));
    }

    // Inject fixed headers.
    request.push_str("Connection: close\r\n");
    request.push_str("X-Client: penpot-desktop\r\n");
    request.push_str(&format!("Content-Length: {}\r\n", body.len()));

    // Inject auto-login session cookie if available.
    if let Ok(guard) = AUTO_LOGIN_COOKIE.lock() {
        if let Some(cookie) = guard.as_ref() {
            request.push_str(&format!("Cookie: {}={}\r\n", SESSION_COOKIE_NAME, cookie));
        }
    }

    request.push_str("\r\n");

    if let Err(e) = backend_stream.write_all(request.as_bytes()) {
        send_response(
            stream,
            "502 Bad Gateway",
            "text/plain",
            &format!("Backend write error: {}", e).into_bytes(),
            cors,
        );
        return;
    }
    if !body.is_empty() {
        if let Err(e) = backend_stream.write_all(body) {
            send_response(
                stream,
                "502 Bad Gateway",
                "text/plain",
                &format!("Backend write error: {}", e).into_bytes(),
                cors,
            );
            return;
        }
    }
    let _ = backend_stream.flush();

    // Read the backend response.
    let mut backend_reader = BufReader::new(&mut backend_stream);
    let mut response_line = String::new();
    if backend_reader.read_line(&mut response_line).unwrap_or(0) == 0 {
        send_response(
            stream,
            "502 Bad Gateway",
            "text/plain",
            b"Empty backend response",
            cors,
        );
        return;
    }

    let response_headers = read_headers(&mut backend_reader);
    let response_body = read_body(&mut backend_reader, &response_headers, true);

    // ── Honor NGINX-style x-accel-redirect for fs-served assets ──────────
    // `serve-object-from-fs` (backend http/assets.clj) returns 204 with
    // `x-accel-redirect: /internal/assets/<rel>` (+ content-type, cache-control),
    // expecting the reverse proxy to read the file from the fs storage dir and
    // serve it. Without this, /assets/by-id/*, /assets/by-file-media-id/* and
    // their /thumbnail variants all return 204 → broken images everywhere
    // (uploaded media, profile photos, frame thumbnails). `<rel>` is the sharded
    // id path (bb/aa/<rest>) under PENPOT_OBJECTS_STORAGE_FS_DIRECTORY.
    if let Some(xar) = header(&response_headers, "x-accel-redirect") {
        // Strip the `/internal/assets/` prefix (PENPOT_ASSETS_PATH) to get <rel>.
        let rel = match xar.find("/internal/assets/") {
            Some(i) => &xar[i + "/internal/assets/".len()..],
            None => xar.trim_start_matches('/'),
        };
        // Path-traversal guard: reject any `..` segment so a crafted rel can't
        // escape the storage dir.
        let safe = !rel.split(|c| c == '/' || c == '\\').any(|seg| seg == "..");
        if safe {
            let file = storage_dir.join(rel);
            if let Ok(data) = std::fs::read(&file) {
                let ct = header(&response_headers, "content-type")
                    .unwrap_or("application/octet-stream");
                let cc = header(&response_headers, "cache-control")
                    .map(|c| format!("Cache-Control: {}\r\n", c))
                    .unwrap_or_default();
                let response = format!(
                    "HTTP/1.1 200 OK\r\n\
                     Content-Type: {}\r\n\
                     Content-Length: {}\r\n\
                     {}\
                     Connection: close\r\n\
                     {}\r\n",
                    ct, data.len(), cc, cors
                );
                let _ = stream.write_all(response.as_bytes());
                let _ = stream.write_all(&data);
                let _ = stream.flush();
                return;
            }
        }
        // File missing on disk or unsafe rel → 404 rather than forwarding the
        // empty 204 (which the <img> would render as a broken image).
        send_response(stream, "404 Not Found", "text/plain", b"asset not found", cors);
        return;
    }

    // Reconstruct the response, stripping backend CORS and injecting ours.
    let mut response = response_line.clone();
    for h in &response_headers {
        let lower = h.to_lowercase();
        if !lower.starts_with("transfer-encoding:")
            && !lower.starts_with("content-length:")
            && !lower.starts_with("access-control-")
        {
            response.push_str(h);
            response.push_str("\r\n");
        }
    }
    response.push_str(cors);
    response.push_str(&format!("Content-Length: {}\r\n", response_body.len()));
    response.push_str("Connection: close\r\n\r\n");

    let _ = stream.write_all(response.as_bytes());
    if !response_body.is_empty() {
        let _ = stream.write_all(&response_body);
    }
    let _ = stream.flush();
}

// ── Public API ──────────────────────────────────────────────────────────────

/// Proxy a WebSocket upgrade to the JVM backend. After forwarding the upgrade
/// request and the backend's 101 response, the connection is a raw byte relay
/// in both directions — WebSocket frames pass through opaquely, so no framing
/// logic is needed. The auto-login session cookie is injected on the upgrade,
/// matching how `proxy_request` injects it on HTTP requests.
fn handle_websocket_upgrade(
    mut client: TcpStream,
    method: &str,
    path: &str,
    headers: &[String],
) {
    let mut backend = match TcpStream::connect((BACKEND_HOST, BACKEND_PORT)) {
        Ok(s) => s,
        Err(e) => {
            let cors = cors_headers(header(headers, "origin"));
            send_response(
                &mut client,
                "502 Bad Gateway",
                "text/plain",
                &format!("Backend unreachable: {}", e).into_bytes(),
                &cors,
            );
            return;
        }
    };

    // The listener sets a 30s read/write timeout on every accepted stream so a
    // stuck HTTP request can't hold a thread forever. A WebSocket is long-lived
    // and legitimately idle for >30s between frames, so that timeout would kill
    // the Sente channel mid-session. Clear it on both ends before the raw relay.
    let _ = client.set_read_timeout(None);
    let _ = client.set_write_timeout(None);
    let _ = backend.set_read_timeout(None);
    let _ = backend.set_write_timeout(None);

    // Forward the original upgrade request. Rewrite Host, drop the client's
    // Cookie (we inject the auto-login session), and pass everything else —
    // Upgrade / Connection / Sec-WebSocket-* / Origin — through unchanged so
    // the backend's handshake succeeds.
    let mut req = format!("{} {} HTTP/1.1\r\n", method, path);
    let mut has_host = false;
    for h in headers {
        let lower = h.to_lowercase();
        if lower.starts_with("host:") {
            has_host = true;
            req.push_str(&format!("Host: {}:{}\r\n", BACKEND_HOST, BACKEND_PORT));
        } else if lower.starts_with("cookie:") {
            // replaced by the injected session cookie below
        } else {
            req.push_str(h);
            req.push_str("\r\n");
        }
    }
    if !has_host {
        req.push_str(&format!("Host: {}:{}\r\n", BACKEND_HOST, BACKEND_PORT));
    }
    if let Ok(guard) = AUTO_LOGIN_COOKIE.lock() {
        if let Some(cookie) = guard.as_ref() {
            req.push_str(&format!("Cookie: {}={}\r\n", SESSION_COOKIE_NAME, cookie));
        }
    }
    req.push_str("\r\n");

    if backend.write_all(req.as_bytes()).is_err() {
        return;
    }
    let _ = backend.flush();

    // Read the backend's 101 handshake (status line + headers) and forward it
    // verbatim to the client. Stop at the blank line that ends the headers.
    let mut br = BufReader::new(&mut backend);
    let mut line = String::new();
    loop {
        line.clear();
        if br.read_line(&mut line).unwrap_or(0) == 0 {
            return;
        }
        if client.write_all(line.as_bytes()).is_err() {
            return;
        }
        if line == "\r\n" || line == "\n" {
            break;
        }
    }
    // The BufReader may have pulled the first WS frame past the headers; flush
    // any such buffered bytes to the client before switching to a raw relay.
    let leftover = br.buffer().to_vec();
    if !leftover.is_empty() {
        let _ = client.write_all(&leftover);
    }
    let _ = client.flush();
    drop(br); // release the borrow on `backend`

    // Bidirectional raw relay. Each direction copies until its read side closes;
    // shutting down the other end unblocks the peer's copy.
    let mut client_rd = match client.try_clone() {
        Ok(c) => c,
        Err(_) => return,
    };
    let mut backend_rd = match backend.try_clone() {
        Ok(c) => c,
        Err(_) => return,
    };
    let relay = thread::spawn(move || {
        let _ = io::copy(&mut client_rd, &mut backend);
        let _ = backend.shutdown(Shutdown::Both);
    });
    let _ = io::copy(&mut backend_rd, &mut client);
    let _ = client.shutdown(Shutdown::Both);
    let _ = relay.join();
}

/// Start the proxy server: bind `port` (synchronously, so the listener is
/// accepting before this returns — the Tauri window loads
/// `http://localhost:<port>/loading.html` immediately after), then run the
/// accept loop in a background thread.
///
/// Static files are served right away (the loading page is self-contained and
/// needs no backend). Auto-login is NOT done here — it requires the JVM backend
/// to be up, which happens later in `boot_backend`. Call `enable_auto_login()`
/// once the backend is ready; until then proxied `/api/` requests simply carry
/// no session cookie (and the SPA is not loaded yet, so none are made).
pub fn start(public_dir: PathBuf, storage_dir: PathBuf, fonts_cache_dir: PathBuf, port: u16) {
    let addr = format!("0.0.0.0:{}", port);

    // Retry binding a few times (stale port from previous run).
    let max_attempts = 4;
    let mut listener = None;
    for attempt in 1..=max_attempts {
        match TcpListener::bind(&addr) {
            Ok(l) => {
                listener = Some(l);
                if attempt > 1 {
                    eprintln!("[penpot-proxy] Bound after {} retries.", attempt - 1);
                }
                break;
            }
            Err(e) => {
                if attempt < max_attempts {
                    eprintln!(
                        "[penpot-proxy] Bind attempt {}/{} failed: {}",
                        attempt, max_attempts, e
                    );
                    thread::sleep(Duration::from_millis(500));
                } else {
                    eprintln!(
                        "[penpot-proxy] Failed to bind to {} after {} attempts: {}",
                        addr, max_attempts, e
                    );
                    return;
                }
            }
        }
    }
    let listener = listener.unwrap();

    eprintln!("[penpot-proxy] Listening on http://localhost:{}", port);
    let public_dir = Arc::new(public_dir);
    let storage_dir = Arc::new(storage_dir);
    let fonts_cache_dir = Arc::new(fonts_cache_dir);

    thread::spawn(move || {
        for stream in listener.incoming() {
            match stream {
                Ok(stream) => {
                    let dir = public_dir.clone();
                    let sdir = storage_dir.clone();
                    let fcdir = fonts_cache_dir.clone();
                    let _ = stream.set_read_timeout(Some(Duration::from_secs(30)));
                    let _ = stream.set_write_timeout(Some(Duration::from_secs(30)));
                    thread::spawn(move || {
                        handle_client(stream, (*dir).clone(), (*sdir).clone(), (*fcdir).clone());
                    });
                }
                Err(e) => {
                    eprintln!("[penpot-proxy] Connection error: {}", e);
                }
            }
        }
    });
}

/// Establish the auto-login session against the (now-ready) JVM backend and
/// arm the global cookie so every subsequent proxied request is authenticated.
/// Called from `boot_backend` after the backend port is open. Gives the user a
/// seamless single-user offline experience with no login screen.
pub fn enable_auto_login() {
    if let Some(cookie) = establish_session() {
        if let Ok(mut guard) = AUTO_LOGIN_COOKIE.lock() {
            *guard = Some(cookie);
        }
    }
    AUTO_LOGIN_DONE.store(true, Ordering::SeqCst);
}
