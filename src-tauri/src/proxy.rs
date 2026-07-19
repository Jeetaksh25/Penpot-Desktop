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

use std::io::{BufRead, BufReader, Write};
use std::net::{TcpListener, TcpStream};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread;
use std::time::Duration;

// ── CORS ────────────────────────────────────────────────────────────────────

/// CORS headers added to every proxied response so the Tauri WebView2
/// (origin https://tauri.localhost) can make cross-origin fetch() calls
/// to http://localhost:1420 where this proxy listens.
const CORS_HEADERS: &str = "\
    Access-Control-Allow-Origin: *\r\n\
    Access-Control-Allow-Methods: GET, POST, PUT, DELETE, PATCH, OPTIONS\r\n\
    Access-Control-Allow-Headers: content-type, authorization, cookie, x-client, x-frontend-version\r\n\
    Access-Control-Allow-Credentials: true\r\n";

// ── Proxy routes ────────────────────────────────────────────────────────────

const PROXY_PREFIXES: &[&str] = &["/api/", "/internal/", "/ws/"];
const BACKEND_HOST: &str = "127.0.0.1";
const BACKEND_PORT: u16 = 3449;

// ── Auto-login ──────────────────────────────────────────────────────────────

const LOCAL_EMAIL: &str = "penpot@localdesktop.com";
const LOCAL_PASSWORD: &str = "penpot-local";
const LOCAL_FULLNAME: &str = "Penpot Desktop";
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
    (".wasm", "application/wasm"),
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

fn read_body(reader: &mut dyn BufRead, headers: &[String]) -> Vec<u8> {
    let mut len: usize = 0;
    for h in headers {
        if h.to_lowercase().starts_with("content-length:") {
            if let Ok(v) = h.split(':').nth(1).map(|s| s.trim().parse::<usize>()).unwrap_or(Ok(0)) {
                len = v;
            }
            break;
        }
    }
    if len == 0 {
        return Vec::new();
    }
    let mut body = vec![0u8; len];
    let _ = reader.read_exact(&mut body);
    body
}

fn send_response(stream: &mut TcpStream, status: &str, content_type: &str, body: &[u8]) {
    let response = format!(
        "HTTP/1.1 {}\r\n\
         Content-Type: {}\r\n\
         Content-Length: {}\r\n\
         Cache-Control: no-store\r\n\
         Connection: close\r\n\
         {}\r\n",
        status, content_type, body.len(), CORS_HEADERS
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
    let response_body = read_body(&mut reader, &response_headers);
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

fn handle_client(mut stream: TcpStream, public_dir: PathBuf) {
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
            send_response(&mut stream, "400 Bad Request", "text/plain", b"Bad request");
            return;
        }
    };

    let headers = read_headers(&mut reader);

    // ── CORS preflight ────────────────────────────────────────────────────
    if method == "OPTIONS" {
        let response = format!(
            "HTTP/1.1 204 No Content\r\n\
             Content-Length: 0\r\n\
             Connection: close\r\n\
             {}\r\n",
            CORS_HEADERS
        );
        let _ = stream.write_all(response.as_bytes());
        let _ = stream.flush();
        return;
    }

    // ── WebSocket ─────────────────────────────────────────────────────────
    let is_websocket = headers
        .iter()
        .any(|h| h.to_lowercase().contains("upgrade:") && h.to_lowercase().contains("websocket"));
    if is_websocket {
        send_response(
            &mut stream,
            "501 Not Implemented",
            "text/plain",
            b"WebSocket proxy not available",
        );
        return;
    }

    // ── Logout interception ───────────────────────────────────────────────
    // The auto-login injects a session cookie on every request. If the
    // frontend's logout request reached the backend it would destroy the
    // session and bounce the user back to the login screen. Instead we
    // intercept it and return an empty success.
    // Strip query parameters before matching so cache-busting params don't
    // bypass the intercept.
    let clean_path = path.split('?').next().unwrap_or(path);
    if (clean_path == "/api/main/methods/logout" || clean_path == "/api/rpc/command/logout")
        && method != "OPTIONS"
    {
        send_response(&mut stream, "200 OK", "application/transit+json", b"[\"^ \"]");
        return;
    }

    // ── API proxy ─────────────────────────────────────────────────────────
    if PROXY_PREFIXES.iter().any(|p| path.starts_with(p)) {
        let body = read_body(&mut reader, &headers);
        proxy_request(&mut stream, method, path, &headers, &body);
        return;
    }

    // ── Static files (fallback) ───────────────────────────────────────────
    let file_path = resolve_path(path, &public_dir);
    if let Ok(data) = std::fs::read(&file_path) {
        let path_str = file_path.to_string_lossy();
        let ct = mime_type(&path_str);
        let response = format!(
            "HTTP/1.1 200 OK\r\n\
             Content-Type: {}\r\n\
             Content-Length: {}\r\n\
             Cache-Control: no-store\r\n\
             Connection: close\r\n\
             {}\r\n",
            ct, data.len(), CORS_HEADERS
        );
        let _ = stream.write_all(response.as_bytes());
        let _ = stream.write_all(&data);
        let _ = stream.flush();
    } else {
        // SPA fallback: serve index.html.
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
                    data.len(),
                    CORS_HEADERS
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
                );
            }
        }
    }
}

fn resolve_path(path: &str, public_dir: &PathBuf) -> PathBuf {
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
            );
            return;
        }
    }
    let _ = backend_stream.flush();

    // Read the backend response.
    let mut backend_reader = BufReader::new(&mut backend_stream);
    let mut response_line = String::new();
    if backend_reader.read_line(&mut response_line).unwrap_or(0) == 0 {
        send_response(stream, "502 Bad Gateway", "text/plain", b"Empty backend response");
        return;
    }

    let response_headers = read_headers(&mut backend_reader);
    let response_body = read_body(&mut backend_reader, &response_headers);

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
    response.push_str(CORS_HEADERS);
    response.push_str(&format!("Content-Length: {}\r\n", response_body.len()));
    response.push_str("Connection: close\r\n\r\n");

    let _ = stream.write_all(response.as_bytes());
    if !response_body.is_empty() {
        let _ = stream.write_all(&response_body);
    }
    let _ = stream.flush();
}

// ── Public API ──────────────────────────────────────────────────────────────

/// Start the proxy server in a background thread.
///
/// When `auto_login` is `true` (production), the proxy will try to
/// authenticate a fixed local account with the backend and inject the
/// session cookie on every proxied request. This gives the user a
/// seamless single-user offline experience with no login screen.
pub fn start(public_dir: PathBuf, port: u16, auto_login: bool) {
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

    // ── Auto-login ────────────────────────────────────────────────────────
    if auto_login {
        if let Some(cookie) = establish_session() {
            if let Ok(mut guard) = AUTO_LOGIN_COOKIE.lock() {
                *guard = Some(cookie);
            }
        }
        AUTO_LOGIN_DONE.store(true, Ordering::SeqCst);
    }

    eprintln!("[penpot-proxy] Listening on http://localhost:{}", port);
    let public_dir = Arc::new(public_dir);

    for stream in listener.incoming() {
        match stream {
            Ok(stream) => {
                let dir = public_dir.clone();
                let _ = stream.set_read_timeout(Some(Duration::from_secs(30)));
                let _ = stream.set_write_timeout(Some(Duration::from_secs(30)));
                thread::spawn(move || {
                    handle_client(stream, (*dir).clone());
                });
            }
            Err(e) => {
                eprintln!("[penpot-proxy] Connection error: {}", e);
            }
        }
    }
}
