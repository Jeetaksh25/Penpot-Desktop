// Penpot Desktop — MCP server skeleton (Phase 2 / AI Depth).
//
// A minimal but REAL JSON-RPC 2.0 server framed over a TCP listener, spawned
// on the tokio runtime. It exposes the 11 MCP tool names the Ovion AI agent
// will eventually call against the live canvas. The tool bodies are TODO stubs
// that return a JSON-RPC error `{"code":-32601,"message":"not yet implemented"}`;
// the surrounding structure (JSON-RPC framing, tool dispatch map, lifecycle) is
// real and compile-ready.
//
// Lifecycle (driven by the `llm_mcp_*` Tauri commands in llm.rs):
//   • start(app, port) — bind 127.0.0.1:port (port 0 = auto-pick), spawn the
//     accept loop as a tokio task, remember the JoinHandle + actual bound port.
//   • stop()           — abort the task and clear the slot.
//   • status()         — { running, port, tools }.
//
// No new crate dependency: tokio (already in Cargo.toml with "full"), serde,
// serde_json, tauri, and std only.
//
// Framing: newline-delimited JSON (NDJSON) — each line is one JSON-RPC request
// and each response is one JSON-RPC object followed by `\n`. This is a real,
// widely-used JSON-RPC-over-TCP framing; it is deliberately simple so a future
// swap to LSP-style Content-Length headers (the MCP stdio spec) is localized
// here.

use std::sync::Mutex;

use serde_json::{json, Value};
use tauri::AppHandle;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::TcpListener;
use tokio::task::JoinHandle;

/// The 11 MCP tools the Ovion AI agent can call against the live canvas. The
/// bodies are stubs; the names are fixed here so `tools/list` and `tools/call`
/// dispatch match across start/status.
const TOOL_NAMES: &[&str] = &[
    "get_document_info",
    "get_layer_tree",
    "get_selection",
    "get_screenshot",
    "get_tokens",
    "get_components",
    "get_libraries",
    "run_code",
    "apply_action",
    "design_to_code",
    "code_to_design",
];

/// The running accept-loop task + the port it actually bound. Guarded by a
/// std::sync::Mutex so `stop()` (called from a Tauri command) can take it
/// without an async context.
static MCP: Mutex<McpState> = Mutex::new(McpState {
    handle: None,
    port: 0,
});

struct McpState {
    handle: Option<JoinHandle<()>>,
    port: u16,
}

/// Build a JSON-RPC 2.0 error object.
fn rpc_error(id: Value, code: i64, message: &str) -> Value {
    json!({
        "jsonrpc": "2.0",
        "id": id,
        "error": { "code": code, "message": message }
    })
}

/// Build a JSON-RPC 2.0 success result.
fn rpc_result(id: Value, result: Value) -> Value {
    json!({
        "jsonrpc": "2.0",
        "id": id,
        "result": result
    })
}

/// Dispatch one JSON-RPC request to a response Value. `tools/list` returns the
/// tool descriptors; `tools/call` validates the tool name and returns the
/// not-yet-implemented error; `initialize` returns minimal MCP capabilities;
/// anything else returns -32601.
fn dispatch(req: &Value) -> Value {
    let id = req.get("id").cloned().unwrap_or(Value::Null);
    let method = req.get("method").and_then(|m| m.as_str()).unwrap_or("");

    match method {
        "initialize" => rpc_result(
            id,
            json!({
                "protocolVersion": "2024-11-05",
                "capabilities": { "tools": {} },
                "serverInfo": { "name": "ovion-desktop-mcp", "version": "0.1.0" }
            }),
        ),
        "tools/list" => rpc_result(
            id,
            json!({
                "tools": TOOL_NAMES.iter().map(|n| json!({
                    "name": n,
                    "description": "not yet implemented"
                })).collect::<Vec<_>>()
            }),
        ),
        "tools/call" => {
            let name = req
                .get("params")
                .and_then(|p| p.get("name"))
                .and_then(|n| n.as_str())
                .unwrap_or("");
            if TOOL_NAMES.contains(&name) {
                rpc_error(id, -32601, "not yet implemented")
            } else {
                rpc_error(id, -32601, &format!("unknown tool: {name}"))
            }
        }
        // Notifications (id absent) get no response per JSON-RPC spec.
        _ if id.is_null() => Value::Null,
        _ => rpc_error(id, -32601, "method not found"),
    }
}

/// Handle one accepted TCP connection: read NDJSON lines, dispatch each, write
/// the response line. A parse failure on a single line yields a JSON-RPC parse
/// error response rather than dropping the connection.
async fn handle_connection(mut stream: tokio::net::TcpStream) {
    let (reader, mut writer) = stream.split();
    let mut lines = BufReader::new(reader).lines();
    while let Ok(Some(line)) = lines.next_line().await {
        let trimmed = line.trim();
        if trimmed.is_empty() {
            continue;
        }
        let req: Value = match serde_json::from_str(trimmed) {
            Ok(v) => v,
            Err(_) => {
                let resp = rpc_error(Value::Null, -32700, "parse error");
                let mut out = serde_json::to_string(&resp).unwrap_or_default();
                out.push('\n');
                let _ = writer.write_all(out.as_bytes()).await;
                continue;
            }
        };
        let resp = dispatch(&req);
        if resp.is_null() {
            // Notification — no response.
            continue;
        }
        let mut out = serde_json::to_string(&resp).unwrap_or_default();
        out.push('\n');
        if writer.write_all(out.as_bytes()).await.is_err() {
            break;
        }
    }
}

/// Start the MCP server on `port` (0 = auto-pick). Returns once the listener is
/// bound and the accept loop is spawned; the loop runs as a detached tokio
/// task. Calling start while already running is an error.
pub async fn start(app: AppHandle, port: u16) -> Result<(), String> {
    {
        let mut g = MCP.lock().map_err(|e| format!("mcp lock poisoned: {e}"))?;
        if g.handle.is_some() {
            return Err("MCP server already running".into());
        }
    }

    let addr = format!("127.0.0.1:{port}");
    let listener = TcpListener::bind(&addr)
        .await
        .map_err(|e| format!("could not bind MCP listener on {addr}: {e}"))?;
    let bound_port = listener
        .local_addr()
        .map(|a| a.port())
        .map_err(|e| format!("could not read bound port: {e}"))?;

    // The app handle is moved into the task so tool bodies (once implemented)
    // can query the live canvas via Tauri events/commands. Currently unused but
    // kept in scope to avoid `unused variable` warnings and to document intent.
    let _app = app;

    let handle = tokio::spawn(async move {
        loop {
            // Accept errors (e.g. transient EMFILE) are logged and skipped so a
            // single bad accept never kills the server.
            match listener.accept().await {
                Ok((stream, _peer)) => {
                    tokio::spawn(handle_connection(stream));
                }
                Err(e) => {
                    eprintln!("[mcp] accept error: {e}");
                    // Yield to avoid a tight loop on a persistent error.
                    tokio::task::yield_now().await;
                }
            }
        }
    });

    {
        let mut g = MCP.lock().map_err(|e| format!("mcp lock poisoned: {e}"))?;
        g.handle = Some(handle);
        g.port = bound_port;
    }
    Ok(())
}

/// Stop the running MCP server, if any. Idempotent — no-op when not running.
pub fn stop() -> Result<(), String> {
    let mut g = MCP.lock().map_err(|e| format!("mcp lock poisoned: {e}"))?;
    if let Some(h) = g.handle.take() {
        h.abort();
    }
    g.port = 0;
    Ok(())
}

/// Report the current server status: `{ running, port, tools }`.
pub fn status() -> Result<Value, String> {
    let g = MCP.lock().map_err(|e| format!("mcp lock poisoned: {e}"))?;
    Ok(json!({
        "running": g.handle.is_some(),
        "port": g.port,
        "tools": TOOL_NAMES.iter().map(|s| s.to_string()).collect::<Vec<_>>()
    }))
}