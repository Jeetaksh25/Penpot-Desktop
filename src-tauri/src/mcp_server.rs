// Penpot Desktop — MCP server (Phase 2 / AI Depth) — REAL tool round-trip.
//
// A minimal but REAL JSON-RPC 2.0 server framed over a TCP listener, spawned
// on the tokio runtime. It exposes the 11 MCP tool names the Ovion AI agent
// calls against the live canvas. Tool bodies are NO LONGER stubs: a tools/call
// generates a unique u64 call id, emits a Tauri event `mcp-tool-call` with
// payload `{id, name, arguments}` to the running frontend via `app.emit`, then
// awaits a `tokio::sync::oneshot` receiver keyed by that id (30s timeout). The
// frontend finishes the tool by invoking the `llm_mcp_tool_result` Tauri command
// (defined in llm.rs), which calls `resolve_pending(id, result)`. On resolve the
// server returns a spec-shaped MCP tools/call success: an object with key
// `content` = an array of one object `{type:"text", text: <json string of the
// result>}` and key `isError` = a bool (true when `result` is an object whose
// `ok` field === false). On timeout it returns JSON-RPC error code -32000.
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

use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Mutex;
use std::time::Duration;

use serde_json::{json, Value};
use tauri::{AppHandle, Emitter};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::TcpListener;
use tokio::sync::oneshot;
use tokio::task::JoinHandle;

/// The 11 MCP tools the Ovion AI agent can call against the live canvas. The
/// names are fixed here so `tools/list` and `tools/call` dispatch match across
/// start/status, and so the frontend's `mcp-tool-call` listener can validate
/// the requested tool name.
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

/// Monotonic call id for the next tools/call round-trip.
static NEXT_ID: AtomicU64 = AtomicU64::new(1);

/// Pending tool-call waiters: id -> oneshot sender. Initialized lazily on first
/// use. `Mutex<Option<HashMap<u64, oneshot::Sender<Value>>>>` is `Sync` because
/// `oneshot::Sender<Value>` is `Send` and `Mutex<T: Send>` is `Sync` — fine for
/// a static.
static PENDING: Mutex<Option<HashMap<u64, oneshot::Sender<Value>>>> = Mutex::new(None);

/// Register a pending tool call and return the receiver that resolves when the
/// frontend invokes `llm_mcp_tool_result(id, result)`.
pub fn register_pending(id: u64) -> oneshot::Receiver<Value> {
    let (tx, rx) = oneshot::channel::<Value>();
    let mut g = match PENDING.lock() {
        Ok(g) => g,
        Err(_) => return rx,
    };
    if g.is_none() {
        *g = Some(HashMap::new());
    }
    if let Some(map) = g.as_mut() {
        map.insert(id, tx);
    }
    rx
}

/// Resolve a pending tool call by id. Returns true if a waiter was found and
/// the result was delivered. Called from the `llm_mcp_tool_result` Tauri
/// command in llm.rs.
pub fn resolve_pending(id: u64, result: Value) -> bool {
    let mut g = match PENDING.lock() {
        Ok(g) => g,
        Err(_) => return false,
    };
    let map = match g.as_mut() {
        Some(m) => m,
        None => return false,
    };
    match map.remove(&id) {
        Some(tx) => tx.send(result).is_ok(),
        None => false,
    }
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

/// Build the MCP tools/call success result body from the frontend's tool
/// result. `isError` is true when the result is an object whose `ok` field is
/// exactly `false` (the CLJS convention for tool failure). The `text` field is
/// a JSON string of the result so any JSON-shaped value round-trips through the
/// MCP `content[].text` channel.
fn tool_result_body(result: &Value) -> Value {
    let is_error = matches!(result.get("ok"), Some(v) if v == &Value::Bool(false));
    let text = serde_json::to_string(result).unwrap_or_else(|_| "null".into());
    json!({
        "content": [ { "type": "text", "text": text } ],
        "isError": is_error
    })
}

/// One-line descriptions for each of the 11 tools, keyed by tool name.
fn tool_description(name: &str) -> &'static str {
    match name {
        "get_document_info" => "Return metadata about the current document (name, id, page count).",
        "get_layer_tree" => "Return the full layer tree of the current page as nested nodes.",
        "get_selection" => "Return the ids, names, and bounds of the currently selected shapes.",
        "get_screenshot" => "Return a PNG screenshot of the current page (or selection) as base64.",
        "get_tokens" => "Return the active design tokens (colors, typography, spacing, radii).",
        "get_components" => "Return the components available in the current document.",
        "get_libraries" => "Return the shared libraries linked to the current document.",
        "run_code" => "Execute a named canvas script with arguments and return its result.",
        "apply_action" => "Apply a named canvas action (mutation) with arguments and return its result.",
        "design_to_code" => "Export the selection or page to framework code (e.g. react, vue, html).",
        "code_to_design" => "Import framework code into the canvas as new shapes or an updated selection.",
        _ => "Ovion canvas tool.",
    }
}

/// Build the inputSchema object for a tool.
fn tool_input_schema(name: &str) -> Value {
    match name {
        "run_code" => json!({
            "type": "object",
            "properties": {
                "name": { "type": "string" },
                "arguments": { "type": "object" }
            },
            "required": ["name"]
        }),
        "apply_action" => json!({
            "type": "object",
            "properties": {
                "name": { "type": "string" },
                "arguments": { "type": "object" }
            },
            "required": ["name"]
        }),
        "design_to_code" => json!({
            "type": "object",
            "properties": {
                "framework": { "type": "string" },
                "scope": { "type": "string", "enum": ["selection", "page"] }
            }
        }),
        "code_to_design" => json!({
            "type": "object",
            "properties": {
                "code": { "type": "string" },
                "framework": { "type": "string" },
                "target": { "type": "string", "enum": ["new-board", "update-selection"] }
            },
            "required": ["code"]
        }),
        // The 7 no-arg tools.
        _ => json!({ "type": "object", "properties": {} }),
    }
}

/// Build the tools/list array of tool descriptors.
fn tool_list() -> Vec<Value> {
    TOOL_NAMES
        .iter()
        .map(|n| {
            json!({
                "name": n,
                "description": tool_description(n),
                "inputSchema": tool_input_schema(n)
            })
        })
        .collect()
}

/// Dispatch one JSON-RPC request to a response Value. `tools/list` returns the
/// tool descriptors; `tools/call` validates the tool name, emits the
/// `mcp-tool-call` event to the frontend, and awaits the oneshot result;
/// `initialize` returns minimal MCP capabilities; anything else returns -32601.
async fn dispatch(app: &AppHandle, req: &Value) -> Value {
    let id = req.get("id").cloned().unwrap_or(Value::Null);
    let method = req.get("method").and_then(|m| m.as_str()).unwrap_or("");

    match method {
        "initialize" => rpc_result(
            id,
            json!({
                "protocolVersion": "2024-11-05",
                "capabilities": { "tools": {} },
                "serverInfo": { "name": "ovion-desktop-mcp", "version": "0.2.0" }
            }),
        ),
        "tools/list" => rpc_result(id, json!({ "tools": tool_list() })),
        "tools/call" => {
            let name = req
                .get("params")
                .and_then(|p| p.get("name"))
                .and_then(|n| n.as_str())
                .unwrap_or("");
            if !TOOL_NAMES.contains(&name) {
                return rpc_error(id, -32601, &format!("unknown tool: {name}"));
            }
            let arguments = req
                .get("params")
                .and_then(|p| p.get("arguments"))
                .cloned()
                .unwrap_or_else(|| json!({}));
            let call_id = NEXT_ID.fetch_add(1, Ordering::Relaxed);
            let rx = register_pending(call_id);
            let payload = json!({ "id": call_id, "name": name, "arguments": arguments });
            if app.emit("mcp-tool-call", payload).is_err() {
                // No frontend listener — drop the pending waiter and fail fast.
                let _ = resolve_pending(call_id, Value::Null);
                return rpc_error(id, -32000, "no frontend listener for mcp-tool-call");
            }
            match tokio::time::timeout(Duration::from_secs(30), rx).await {
                Ok(Ok(result)) => rpc_result(id, tool_result_body(&result)),
                // Sender dropped without sending (frontend vanished) — treat as
                // a tool error.
                Ok(Err(_)) => rpc_error(id, -32000, "tool call cancelled by frontend"),
                Err(_) => {
                    // Timed out — clean up the pending waiter so it cannot
                    // resolve after we have already responded.
                    let _ = resolve_pending(call_id, Value::Null);
                    rpc_error(id, -32000, "tool execution timed out")
                }
            }
        }
        // Notifications (id absent) get no response per JSON-RPC spec.
        _ if id.is_null() => Value::Null,
        _ => rpc_error(id, -32601, "method not found"),
    }
}

/// Handle one accepted TCP connection: read NDJSON lines, dispatch each, write
/// the response line. A parse failure on a single line yields a JSON-RPC parse
/// error response rather than dropping the connection. The AppHandle is cloned
/// per connection so each connection can emit events to the frontend.
async fn handle_connection(app: AppHandle, mut stream: tokio::net::TcpStream) {
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
        let resp = dispatch(&app, &req).await;
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
        let g = MCP.lock().map_err(|e| format!("mcp lock poisoned: {e}"))?;
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

    // The app handle is moved into the accept loop so each tools/call can emit
    // the `mcp-tool-call` event to the running frontend and await the
    // `llm_mcp_tool_result` resolution.
    let handle = tokio::spawn(async move {
        loop {
            // Accept errors (e.g. transient EMFILE) are logged and skipped so a
            // single bad accept never kills the server.
            match listener.accept().await {
                Ok((stream, _peer)) => {
                    tokio::spawn(handle_connection(app.clone(), stream));
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