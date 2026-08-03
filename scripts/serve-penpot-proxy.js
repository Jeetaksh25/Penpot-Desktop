// Dependency-free same-origin dev server for the Penpot desktop frontend.
//
// Serves the built SPA (penpot-source/frontend/resources/public) on :1420 and
// reverse-proxies /api, /internal and the /ws websocket to the JVM backend on
// :3449. Because the page and all its calls share the http://localhost:1420
// origin, the browser never issues a CORS preflight — same as a production
// Penpot deployment behind one nginx origin.
//
// OFFLINE AUTO-LOGIN: this is a single-user offline desktop app, so the proxy
// transparently authenticates a fixed local account ("penpot@localdesktop.com")
// at startup and injects the resulting `auth-token` session cookie onto every
// proxied HTTP request and the WS upgrade. The frontend therefore never sees
// a 401 and never renders the login screen. Registration happens once (first
// boot); every later boot just re-logs in. No jar / frontend rebuild needed.

import http from "node:http";
import net from "node:net";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(__dirname, "..");

const PORT = parseInt(process.env.PENPOT_DESKTOP_FRONTEND_PORT || "1420", 10);
const BACKEND_HOST = process.env.PENPOT_DESKTOP_BACKEND_HOST || "127.0.0.1";
const BACKEND_PORT = parseInt(process.env.PENPOT_DESKTOP_BACKEND_PORT || "3449", 10);
const PUBLIC_DIR = path.join(root, "penpot-source/frontend/resources/public");

// Fixed local account for the offline single-user app.
const LOCAL_EMAIL = "penpot@localdesktop.com";
const LOCAL_PASSWORD = "penpot-local";
const LOCAL_FULLNAME = "Ovion Desktop";
const SESSION_COOKIE_NAME = "auth-token";

// Also proxy /assets/ so that file thumbnails and asset images hosted by the
// backend (e.g. /assets/by-id/...) are fetched with the correct Content-Type
// from the backend, instead of getting served as index.html by the SPA fallback.
const PROXY_PREFIXES = ["/api/", "/internal/", "/ws/", "/assets/"];

const MIME = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".mjs": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".map": "application/json; charset=utf-8",
  ".svg": "image/svg+xml",
  ".png": "image/png",
  ".jpg": "image/jpeg",
  ".jpeg": "image/jpeg",
  ".gif": "image/gif",
  ".ico": "image/x-icon",
  ".woff": "font/woff",
  ".woff2": "font/woff2",
  ".ttf": "font/ttf",
  ".otf": "font/otf",
  ".wasm": "application/wasm",
  ".txt": "text/plain; charset=utf-8",
};

function contentType(file) {
  return MIME[path.extname(file).toLowerCase()] || "application/octet-stream";
}

// no-store: the Tauri WebView2 (Edge/Chromium) cache is sticky across dev runs,
// so a stale broken main.css from an earlier buggy run would keep rendering
// unstyled even after the file is fixed. no-store forces a fresh fetch every
// time, ruling the cache out as a cause of "CSS completely broken".
const CACHE = "no-store";

function sendFile(res, file) {
  fs.stat(file, (err, st) => {
    if (err || !st.isFile()) return sendIndex(res);
    res.writeHead(200, {
      "content-type": contentType(file),
      "cache-control": CACHE,
      "content-length": st.size,
    });
    fs.createReadStream(file).pipe(res);
  });
}

function sendIndex(res) {
  const index = path.join(PUBLIC_DIR, "index.html");
  fs.stat(index, (err, st) => {
    if (err) {
      res.writeHead(404, { "content-type": "text/plain" });
      return res.end("index.html not found — run the frontend build first.");
    }
    res.writeHead(200, {
      "content-type": "text/html; charset=utf-8",
      "cache-control": CACHE,
      "content-length": st.size,
    });
    fs.createReadStream(index).pipe(res);
  });
}

function serveStatic(res, pathname) {
  // Resolve under PUBLIC_DIR and refuse anything that escapes it.
  const file = path.resolve(PUBLIC_DIR, "." + decodeURIComponent(pathname));
  const within = file === PUBLIC_DIR || file.startsWith(PUBLIC_DIR + path.sep);
  if (!within) return sendIndex(res);
  fs.stat(file, (err, st) => {
    if (err || !st.isFile()) {
      // Penpot uses hash routing, so the document is always at "/"; there are
      // no server-side SPA deep links to fall back to. For a missing asset
      // (anything with a file extension) return a real 404 — serving index.html
      // as text/html for a missing font/css/image masks the breakage and can
      // corrupt the resource (e.g. a 404'd .woff2 parsed as HTML). Only an
      // extensionless path (a would-be route) falls back to index.html.
      if (path.extname(pathname)) {
        res.writeHead(404, { "content-type": "text/plain; charset=utf-8" });
        return res.end(`404 not found: ${pathname}`);
      }
      return sendIndex(res);
    }
    res.writeHead(200, {
      "content-type": contentType(file),
      "cache-control": CACHE,
      "content-length": st.size,
    });
    fs.createReadStream(file).pipe(res);
  });
}

// ---- Offline auto-login -------------------------------------------------

// transit+json encoder for a flat string->string map. A map encodes as
// ["^ ", k1, v1, k2, v2, ...] where "^ " is the map-as-array tag. Map keys are
// Clojure keywords, which transit+json writes with a "~:" prefix (e.g. the
// keyword :email -> "~:email"); the backend's malli schemas key on keywords,
// so plain string keys would be rejected as missing. Good enough for the
// three flat RPC payloads we send (login / prepare-register / register).
function transitMap(obj) {
  const parts = ['"^ "'];
  for (const [k, v] of Object.entries(obj)) {
    parts.push(JSON.stringify(`~:${k}`), JSON.stringify(String(v)));
  }
  return "[" + parts.join(",") + "]";
}

// Call a backend RPC method. Returns { status, setCookie: string[], body: string }.
function rpcCall(method, params) {
  return new Promise((resolve, reject) => {
    const body = params ? transitMap(params) : '["^ "]';
    const req = http.request(
      {
        host: BACKEND_HOST,
        port: BACKEND_PORT,
        method: "POST",
        path: `/api/main/methods/${method}`,
        headers: {
          "content-type": "application/transit+json",
          accept: "application/transit+json",
          "x-client": "penpot-desktop",
          "x-frontend-version": "develop",
          "content-length": Buffer.byteLength(body),
        },
      },
      (res) => {
        const chunks = [];
        res.on("data", (c) => chunks.push(c));
        res.on("end", () =>
          resolve({
            status: res.statusCode || 0,
            setCookie: res.headers["set-cookie"] || [],
            body: Buffer.concat(chunks).toString("utf8"),
          }),
        );
      },
    );
    req.on("error", reject);
    req.end(body);
  });
}

function extractCookie(setCookie, name) {
  const prefix = `${name}=`;
  for (const c of setCookie) {
    const idx = c.indexOf(prefix);
    if (idx === -1) continue;
    const end = c.indexOf(";", idx);
    return c.slice(idx + prefix.length, end === -1 ? c.length : end);
  }
  return null;
}

// The backend responds with transit; for prepare-register we just need the
// `:token` value, which is a plain JWE (base64url + dots, no quotes). The key
// is encoded as "~:token" in transit+json.
function extractToken(body) {
  const m = body.match(/"~:token"\s*,\s*"([^"]+)"/);
  return m ? m[1] : null;
}

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

// Wait until the backend HTTP port accepts connections.
async function waitForBackend() {
  for (;;) {
    const ok = await new Promise((resolve) => {
      const s = net.connect(BACKEND_PORT, BACKEND_HOST, () => {
        s.destroy();
        resolve(true);
      });
      s.on("error", () => resolve(false));
    });
    if (ok) return;
    await sleep(500);
  }
}

// Authenticate the fixed local account. First boot registers it; every later
// boot just logs in. Returns the `auth-token` cookie value or null on failure.
async function ensureSession() {
  for (let attempt = 0; attempt < 5; attempt++) {
    try {
      const login = await rpcCall("login-with-password", {
        email: LOCAL_EMAIL,
        password: LOCAL_PASSWORD,
      });
      const cookie = extractCookie(login.setCookie, SESSION_COOKIE_NAME);
      if (login.status === 200 && cookie) {
        console.log("[auto-login] session established for", LOCAL_EMAIL);
        return cookie;
      }
      // No profile yet (or wrong creds) — register a fresh local account.
      const prep = await rpcCall("prepare-register-profile", {
        fullname: LOCAL_FULLNAME,
        email: LOCAL_EMAIL,
        password: LOCAL_PASSWORD,
      });
      const token = extractToken(prep.body);
      if (!token) {
        // prepare-register failed (maybe "email already exists"); retry login.
        await sleep(800);
        continue;
      }
      const reg = await rpcCall("register-profile", { token });
      const regCookie = extractCookie(reg.setCookie, SESSION_COOKIE_NAME);
      if (reg.status === 200 && regCookie) {
        console.log("[auto-login] registered + session established for", LOCAL_EMAIL);
        return regCookie;
      }
    } catch (e) {
      // backend not ready / network blip — wait and retry.
    }
    await sleep(1000);
  }
  console.error("[auto-login] could not establish a session; the login screen may appear.");
  return null;
}

let sessionCookie = null;
const ready = (async () => {
  await waitForBackend();
  sessionCookie = await ensureSession();
})();

// ---- Proxy --------------------------------------------------------------

function proxyHttp(req, res) {
  // Offline single-user: never let the frontend log out (it would delete our
  // injected server-side session and bounce back to the login screen).
  const u = new URL(req.url, `http://localhost:${PORT}`);
  if (u.pathname === "/api/main/methods/logout" || u.pathname === "/api/rpc/command/logout") {
    res.writeHead(200, { "content-type": "application/transit+json" });
    return res.end('["^ "]');
  }

  ready.then(() => {
    const headers = { ...req.headers, host: `${BACKEND_HOST}:${BACKEND_PORT}` };
    if (sessionCookie) headers["cookie"] = `${SESSION_COOKIE_NAME}=${sessionCookie}`;
    const proxyReq = http.request(
      { host: BACKEND_HOST, port: BACKEND_PORT, method: req.method, path: req.url, headers },
      (proxyRes) => {
        res.writeHead(proxyRes.statusCode, proxyRes.headers);
        proxyRes.pipe(res); // streaming: works for normal and SSE responses
      },
    );
    proxyReq.on("error", (e) => {
      if (!res.headersSent) res.writeHead(502, { "content-type": "text/plain" });
      res.end(`Backend proxy error: ${e.message}`);
    });
    req.pipe(proxyReq);
  });
}

function proxyUpgrade(req, socket) {
  ready.then(() => {
    const target = net.connect(BACKEND_PORT, BACKEND_HOST, () => {
      let raw = `${req.method} ${req.url} HTTP/1.1\r\n`;
      for (const [k, v] of Object.entries(req.headers)) {
        const lk = k.toLowerCase();
        if (lk === "host") raw += `host: ${BACKEND_HOST}:${BACKEND_PORT}\r\n`;
        else if (lk === "cookie") raw += `cookie: ${v}; ${SESSION_COOKIE_NAME}=${sessionCookie || ""}\r\n`;
        else raw += `${k}: ${v}\r\n`;
      }
      if (!req.headers["cookie"] && sessionCookie) {
        raw += `cookie: ${SESSION_COOKIE_NAME}=${sessionCookie}\r\n`;
      }
      raw += "\r\n";
      target.write(raw);
      target.pipe(socket);
      socket.pipe(target);
    });
    target.on("error", () => socket.destroy());
    socket.on("error", () => target.destroy());
  });
}

// Log every response so a broken-UI run shows exactly which assets the webview
// requested and at what status/content-type — the root-cause diagnostic for
// "CSS completely broken" (is main.css requested? 404? wrong MIME? a font/image
// 404 that the SPA fallback answers with index.html?).
function logRequest(req, res) {
  res.on("finish", () => {
    const ct = res.getHeader("content-type") || "-";
    const len = res.getHeader("content-length") || "-";
    console.log(`[${req.method} ${req.url}] -> ${res.statusCode} ${ct} ${len}`);
  });
}

const server = http.createServer((req, res) => {
  logRequest(req, res);
  const u = new URL(req.url, `http://localhost:${PORT}`);

  // Webview diagnostic channel: the page POSTs its window.onerror /
  // unhandledrejection / console.error messages and its document.styleSheets
  // status here, and we surface them on stdout. This is how we "see" the
  // Tauri webview's console without the user opening DevTools — the lines
  // appear in the same terminal they already paste.
  if (req.method === "POST" && u.pathname === "/__desktop_log") {
    const chunks = [];
    req.on("data", (c) => chunks.push(c));
    req.on("end", () => {
      try {
        const msg = JSON.parse(Buffer.concat(chunks).toString("utf8"));
        console.log(`[webview] ${msg.kind || "log"}: ${typeof msg.text === "string" ? msg.text : JSON.stringify(msg.text)}`);
      } catch {
        console.log(`[webview] ${Buffer.concat(chunks).toString("utf8")}`);
      }
      res.writeHead(200);
      res.end("ok");
    });
    return;
  }

  if (PROXY_PREFIXES.some((p) => u.pathname.startsWith(p))) return proxyHttp(req, res);
  serveStatic(res, u.pathname);
});

server.on("upgrade", (req, socket) => {
  const u = new URL(req.url, `http://localhost:${PORT}`);
  if (u.pathname.startsWith("/ws/")) return proxyUpgrade(req, socket);
  socket.destroy();
});

// Don't let Node's default timeouts cut long-lived SSE / websocket-ish streams.
server.requestTimeout = 0;
server.headersTimeout = 0;
server.timeout = 0;
server.keepAliveTimeout = 0;

server.listen(PORT, () => {
  console.log(`Ovion frontend (same-origin proxy) on http://localhost:${PORT}`);
  console.log(`  static:  ${PUBLIC_DIR}`);
  console.log(`  proxy -> ${BACKEND_HOST}:${BACKEND_PORT}  (/api, /internal, /ws)`);
  console.log(`  auto-login -> ${LOCAL_EMAIL} (offline single-user)`);
});