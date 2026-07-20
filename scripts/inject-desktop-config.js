import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { execSync } from "node:child_process";

const __dirname = path.dirname(fileURLToPath(import.meta.url));

// Kill anything listening on `port` so a stale process from a previous run
// does not hold the port (used for the 1420 frontend dev server and the 3449
// backend, which the new launch needs).
async function killListenersOnPort(port) {
  if (process.platform !== "win32") return;
  const suffix = `:${port}`;
  try {
    const out = execSync(`netstat -ano -p tcp`, {
      stdio: ["ignore", "pipe", "ignore"],
    })
      .toString()
      .split(/\r?\n/);
    const pids = new Set();
    for (const line of out) {
      // Windows netstat -ano columns: Proto, Local, Foreign, State, PID.
      const toks = line.trim().split(/\s+/);
      if (toks.length < 5 || toks[0] !== "TCP") continue;
      if (!toks[1].endsWith(suffix)) continue;
      const pid = toks[toks.length - 1];
      if (pid && pid !== "0") pids.add(pid);
    }
    for (const pid of pids) {
      try {
        execSync(`taskkill /PID ${pid} /F`, { stdio: "ignore" });
        console.log(`Freed port ${port}: killed PID ${pid}`);
      } catch {}
    }
  } catch {}
}

// Kill a stale desktop app from a previous launch so cargo can overwrite
// penpot-desktop.exe (Windows refuses to remove an in-use exe: "Access is denied").
async function killStaleApp(imageName) {
  if (process.platform !== "win32") return;
  try {
    execSync(`taskkill /IM ${imageName} /F`, { stdio: "ignore" });
    console.log(`Killed stale ${imageName}`);
  } catch {}
}

const indexPath = path.resolve(
  __dirname,
  "../penpot-source/frontend/resources/public/index.html",
);

const backendPort = process.env.PENPOT_DESKTOP_BACKEND_PORT || "3449";
const backendHost = process.env.PENPOT_DESKTOP_BACKEND_HOST || "localhost";
// The frontend talks to itself (the same-origin proxy on the frontend port),
// which reverse-proxies /api, /internal and /ws to the backend. Same origin =>
// no CORS preflight, no cross-origin websocket — like production Penpot.
const frontendPort = process.env.PENPOT_DESKTOP_FRONTEND_PORT || "1420";
const publicUri = `http://${backendHost}:${frontendPort}`;

// Flags that make sense for a single-user offline desktop app.
// Demo users are enabled so the login screen can create a local profile.
// Render-wasm flags are left at their Penpot defaults (enabled) now that the
// real Emscripten WASM renderer is built.
const flags = [
  "disable-secure-session-cookies",
  "disable-telemetry",
  "enable-login",
  "enable-registration",
  "enable-demo-users",
  "enable-backend-worker",
].join(" ");

const injectScript = `
  <script>
    globalThis.penpotPublicURI = "${publicUri}";
    globalThis.penpotFlags = "${flags}";
    globalThis.penpotIsSaas = false;
  </script>
`;

// Webview diagnostic: forwards window.onerror, unhandledrejection, console.error
// and document.styleSheets status to the proxy's /__desktop_log endpoint so the
// lines show up in the dev terminal — the user does not need to open DevTools.
// Injected right before </head> (idempotent via a marker comment).
const diagMarker = "<!--penpot-desktop-diag-->";
const diagScript = `
  ${diagMarker}
  <script>
  (function () {
    function send(obj) {
      try { fetch("/__desktop_log", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(obj) }).catch(function () {}); } catch (e) {}
    }
    function s(a) { try { return typeof a === "string" ? a : JSON.stringify(a); } catch (x) { return String(a); } }
    window.addEventListener("error", function (e) {
      send({ kind: "error", text: (e.message || "") + " @ " + (e.filename || "") + ":" + e.lineno + ":" + e.colno + (e.error && e.error.stack ? ("\\n" + e.error.stack) : "") });
    });
    window.addEventListener("unhandledrejection", function (e) { var r = e.reason; send({ kind: "rejection", text: (r && (r.message || (r.toString && r.toString()))) || String(r) }); });
    var _ce = console.error;
    console.error = function () { send({ kind: "console.error", text: Array.prototype.slice.call(arguments).map(s).join(" ") }); return _ce.apply(console, arguments); };
    function reportSheets() {
      var out = [];
      for (var i = 0; i < document.styleSheets.length; i++) {
        var sh = document.styleSheets[i]; var info = { href: sh.href, disabled: sh.disabled, rules: null };
        try { info.rules = sh.cssRules.length; } catch (ex) { info.rules = "blocked:" + ex.name; }
        out.push(info);
      }
      send({ kind: "styleSheets", text: "count=" + document.styleSheets.length + " " + JSON.stringify(out) });
    }
    window.addEventListener("load", function () { setTimeout(reportSheets, 1500); setTimeout(reportSheets, 4000); });
  })();
  </script>
`;

const configPath = path.resolve(
  __dirname,
  "../penpot-source/frontend/resources/public/js/config.js",
);

const configJsContent = `
// Desktop-specific Penpot runtime configuration.
var penpotPublicURI = "${publicUri}";
var penpotFlags = "${flags}";
var penpotIsSaas = false;
var penpotOIDCName = null;
var penpotTermsOfServiceURI = null;
var penpotPrivacyPolicyURI = null;
var penpotPluginsListURI = null;
var penpotPluginsWhitelist = [];
var penpotTemplatesURI = null;
var penpotUploadChunkSize = ${25 * 1024 * 1024};
var externalFeatureFlag = null;
var externalSessionId = null;
var externalContextInfo = null;
var initializeExternalConfigInfo = null;
`;

async function main() {
  await killStaleApp("penpot-desktop.exe");
  await killListenersOnPort(1420);
  await killListenersOnPort(3449);
  // Clear any leftover Postgres / Redis from a previous run that was killed
  // ungracefully (e.g. Ctrl+C in the terminal, which does not trigger Tauri's
  // window-Destroyed shutdown). A stale postmaster holding the data-dir lock
  // is the one thing that makes the next `pg_ctl start` hang for 60s+.
  await killListenersOnPort(5432);
  await killListenersOnPort(6379);
  let html = await fs.readFile(indexPath, "utf-8");

  // Pin a <base href="/"> so relative assets (css/main.css, ./js/*.js, the
  // importmap) always resolve to the origin root. app.main.router navigates
  // between route bundles via `set! location.href <path>` (a FULL navigation,
  // not pushState), which reloads index.html at a deep path like /auth/login;
  // without this, css/main.css would resolve to /auth/css/main.css and 404
  // (the proxy then returns index.html as text/css -> completely broken UI).
  if (!html.includes('<base href="/"')) {
    html = html.replace(/<head[^>]*>/i, (m) => `${m}\n    <base href="/" />`);
  }

  if (!html.includes("globalThis.penpotPublicURI")) {
    // Insert immediately after the generated version globals module.
    const marker = "globalThis.penpotBuildDate = \"";
    const idx = html.indexOf(marker);
    if (idx === -1) {
      console.error("Could not find version globals in index.html");
      process.exit(1);
    }

    // Find the closing </script> of that module.
    const scriptEnd = html.indexOf("</script>", idx);
    if (scriptEnd === -1) {
      console.error("Could not find end of version globals script");
      process.exit(1);
    }

    const insertAt = scriptEnd + "</script>".length;
    html = html.slice(0, insertAt) + injectScript + html.slice(insertAt);

    console.log(`Injected desktop config into index.html pointing to ${publicUri}`);
  } else {
    // Update existing injection in place so flag changes are always applied.
    html = html.replace(
      /globalThis\.penpotPublicURI\s*=\s*"[^"]*";/,
      `globalThis.penpotPublicURI = "${publicUri}";`,
    );
    html = html.replace(
      /globalThis\.penpotFlags\s*=\s*"[^"]*";/,
      `globalThis.penpotFlags = "${flags}";`,
    );
    html = html.replace(
      /globalThis\.penpotIsSaas\s*=\s*[^;]+;/,
      `globalThis.penpotIsSaas = false;`,
    );
    console.log(`Updated desktop config in index.html pointing to ${publicUri}`);
  }

  // Inject (idempotently) the webview diagnostic script right before </head>.
  if (!html.includes(diagMarker)) {
    const headEnd = html.indexOf("</head>");
    if (headEnd !== -1) {
      html = html.slice(0, headEnd) + diagScript + "\n  " + html.slice(headEnd);
      console.log("Injected webview diagnostic script into index.html");
    }
  }

  await fs.writeFile(indexPath, html, "utf-8");

  await fs.writeFile(configPath, configJsContent.trim() + "\n", "utf-8");
  console.log(`Wrote ${configPath}`);

  // Write a branded loading page next to index.html. The Rust app opens this
  // page immediately on launch (before the backend boots) and updates
  // #boot-status / navigates to index.html once the backend is ready, so the
  // app is never an invisible background process.
  const loadingPath = path.join(path.dirname(indexPath), "loading.html");
  // Inline the real Penpot logo (white-on-dark) instead of a "P" placeholder,
  // so the loading screen shows the actual brand mark. Read at inject time so
  // it stays in sync with data/assets/penpot-light.svg.
  const logoSvg = await fs
    .readFile(path.resolve(__dirname, "../data/assets/penpot-light.svg"), "utf-8")
    .catch(() => "");
  const loadingHtml = `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1" />
<title>Penpot Desktop</title>
<style>
  html, body { margin: 0; height: 100%; background: #1d1f26; color: #e6e7ee;
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
  #wrap { display: flex; flex-direction: column; align-items: center; justify-content: center;
    height: 100%; gap: 28px; }
  #logo { display: flex; align-items: center; justify-content: center; }
  #logo svg { height: 84px; width: auto; display: block; }
  #title { font-size: 20px; font-weight: 600; letter-spacing: .3px; }
  #boot-status { font-size: 14px; color: #9b9ca8; min-height: 1.2em; text-align: center; }
  .spinner { width: 28px; height: 28px; border: 3px solid #2e3140; border-top-color: #5145ff;
    border-radius: 50%; animation: spin 1s linear infinite; }
  @keyframes spin { to { transform: rotate(360deg); } }
</style>
</head>
<body>
  <div id="wrap">
    <div id="logo">${logoSvg}</div>
    <div id="title">Penpot Desktop</div>
    <div class="spinner"></div>
    <div id="boot-status">Starting Penpot Desktop…</div>
  </div>
</body>
</html>
`;
  await fs.writeFile(loadingPath, loadingHtml, "utf-8");
  console.log(`Wrote ${loadingPath}`);

  // Ensure a render worker stub exists until the real Emscripten WASM build is available.
  const renderWorkerDir = path.resolve(
    __dirname,
    "../penpot-source/frontend/resources/public/js/worker",
  );
  const renderWorkerPath = path.join(renderWorkerDir, "render.js");
  try {
    await fs.access(renderWorkerPath);
  } catch {
    await fs.mkdir(renderWorkerDir, { recursive: true });
    await fs.writeFile(
      renderWorkerPath,
      `// Stub render worker for desktop builds without Emscripten WASM.\n` +
        `// The SVG renderer is used instead, so this worker receives no messages.\n` +
        `self.addEventListener("message", function (event) {\n` +
        `  self.postMessage({ error: "render-wasm not built", payload: event.data });\n` +
        `});\n`,
      "utf-8",
    );
    console.log(`Wrote stub ${renderWorkerPath}`);
  }
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
