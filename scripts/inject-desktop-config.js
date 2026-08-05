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
// ovion-desktop.exe (Windows refuses to remove an in-use exe: "Access is denied").
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
// Render-wasm is disabled on the FRONTEND to match the backend's PENPOT_FLAGS
// (lib.rs). CI ships a stub js/worker/render.js (no Emscripten build); if the
// frontend re-enables :feature-render-wasm (app.main.features/setup-wasm-features
// does so unless `disable-render-switch` + `disable-feature-render-wasm` are
// set), that stub receives worker messages and echoes raw JS objects back ->
// wm/decode -> JSON.parse("[object Object]") -> a continuous "Something wrong
// has happened" toast. The SVG renderer is used instead.
const flags = [
  "disable-secure-session-cookies",
  "disable-telemetry",
  "enable-login",
  "enable-registration",
  "enable-demo-users",
  "enable-backend-worker",
  "disable-feature-render-wasm",
  "disable-render-switch",
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
  await killStaleApp("ovion-desktop.exe");
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

  // Fullscreen boot splash: copy the Loading_Screen.png artwork into the
  // served public dir so BOTH proxies (the Node dev proxy and the Rust
  // release proxy — they serve the same resources/public folder) can load it
  // as the fullscreen background of the loading page. Re-run on every inject
  // so the splash stays in sync with Logo/Loading_Screen.png.
  const splashSrc = path.resolve(__dirname, "../Logo/Loading_Screen.png");
  const splashDest = path.join(path.dirname(indexPath), "loading-splash.png");
  try {
    await fs.copyFile(splashSrc, splashDest);
    console.log(`Copied splash ${splashSrc} -> ${splashDest}`);
  } catch (err) {
    console.warn(
      `Could not copy splash image (${err.message}); the loading page will fall back to its cream background.`,
    );
  }

  // The loading page IS the brand artwork: Loading_Screen.png covers the
  // whole borderless window (drag region so it can be moved). The only text
  // on it is a lowercase "loading" label + rotating loader pinned to the
  // bottom-right corner. #boot-status is kept in the DOM (the Rust boot
  // thread writes progress/failures into it via eval) but stays hidden unless
  // boot actually FAILS, so failures still surface without cluttering the
  // happy-path screen.
  const loadingHtml = `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1" />
<title>Ovion Desktop</title>
<style>
  html, body { margin: 0; height: 100%; overflow: hidden;
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
  /* Fullscreen splash artwork. Cream background is the pre-image fallback. */
  #wrap { position: fixed; inset: 0;
    background-color: #f6efe4;
    background-image: url("/loading-splash.png");
    background-size: cover;
    background-position: center;
    background-repeat: no-repeat;
    display: flex; align-items: flex-end; justify-content: flex-end; }
  /* Bottom-right: the only text — "loading" next to a rotating loader. */
  #boot-indicator { display: flex; align-items: center; gap: 10px;
    margin: 0 28px 26px 0; padding: 10px 16px; border-radius: 999px;
    background: rgba(255, 251, 245, 0.55);
    -webkit-backdrop-filter: blur(8px); backdrop-filter: blur(8px);
    color: #4a342a; user-select: none; }
  .spinner { width: 16px; height: 16px; border: 2px solid rgba(74, 52, 42, 0.22);
    border-top-color: #e2544a; border-radius: 50%; animation: spin 0.9s linear infinite; }
  #boot-label { font-size: 13px; font-weight: 500; letter-spacing: 0.05em; }
  @keyframes spin { to { transform: rotate(360deg); } }
  /* Boot failures still surface (hidden during the happy path). */
  #boot-status { position: fixed; right: 28px; bottom: 74px; max-width: 340px;
    padding: 8px 12px; border-radius: 10px; display: none;
    background: rgba(255, 243, 240, 0.92); color: #b3261e;
    font-size: 12px; line-height: 1.45; }
</style>
</head>
<body>
  <div id="wrap" data-tauri-drag-region="true">
    <div id="boot-indicator">
      <div class="spinner"></div>
      <span id="boot-label">loading</span>
    </div>
  </div>
  <div id="boot-status"></div>
  <script>
    // The Rust boot thread writes progress ("Starting local database…") and
    // failures into #boot-status via eval. Show it only for failures so the
    // loading screen keeps just the "loading" label in the happy path.
    (function () {
      var el = document.getElementById("boot-status");
      if (!el) return;
      function check() {
        if (/failed|did not become ready|could not|error/i.test(el.textContent || ""))
          el.style.display = "block";
      }
      new MutationObserver(check).observe(el, { childList: true, subtree: true, characterData: true });
      check();
    })();
  </script>
</body>
</html>
`;
  await fs.writeFile(loadingPath, loadingHtml, "utf-8");
  console.log(`Wrote ${loadingPath}`);


  // Ensure a render.js exists so the worker's importScripts('./render.js')
  // (shadow-cljs.edn :prepend-js) does not 404. The real render.js is an
  // Emscripten module produced by ensure-wasm-artifacts.js; this only writes
  // a fallback if that artifact is somehow absent. The fallback is a TRUE
  // no-op (no message listener, no postMessage, no globals): with render-wasm
  // disabled via penpotFlags the worker uses the SVG renderer and never
  // touches WasmModule. (A previous stub echoed raw JS objects back on every
  // worker message -> host transit-decode -> JSON.parse("[object Object]")
  // -> a continuous "Something wrong has happened" toast. The no-op avoids it.)
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
      `// No-op render.js stub for desktop builds without Emscripten WASM.\n` +
        `// The worker imports this via importScripts; with render-wasm disabled\n` +
        `// (penpotFlags) the SVG renderer is used and WasmModule is never used.\n` +
        `// Intentionally no message listener, no postMessage, no globals.\n`,
      "utf-8",
    );
    console.log(`Wrote no-op stub ${renderWorkerPath}`);
  }
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
