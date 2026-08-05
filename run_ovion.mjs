#!/usr/bin/env node
/**
 * run_ovion.mjs — one-command local test runner for Ovion Desktop.
 *
 * Everything you need to test the app on this machine BEFORE building the
 * installer for GitHub Actions:
 *
 *   node run_ovion.mjs                    Full app test:
 *                                         icons -> backend jar check ->
 *                                         frontend build (only if missing) ->
 *                                         launch the app (`npm run tauri dev`)
 *                                         The app boots Postgres + Redis + the
 *                                         JVM backend itself and shows progress
 *                                         on the splash screen.
 *
 *   node run_ovion.mjs --backend-only     Stand up Postgres + Redis + the JVM
 *                                         backend + the frontend proxy
 *                                         (auto-login) and keep them running —
 *                                         for API/backend work without the
 *                                         window. Cleanly shuts down what it
 *                                         started on exit.
 *
 *   node run_ovion.mjs --build-frontend   Force the full frontend build
 *                                         (wasm artifacts, templates, cljs
 *                                         release). Default: build only when
 *                                         the JS bundle is missing.
 *   node run_ovion.mjs --no-build-frontend  Never build the frontend.
 *   node run_ovion.mjs --skip-icons       Do not regenerate src-tauri/icons.
 *   node run_ovion.mjs --stop             Kill whatever listens on the app
 *                                         ports (1420 proxy, 3449 backend,
 *                                         5432 postgres, 6379 redis).
 *   node run_ovion.mjs --help
 *
 * Windows notes: the Rust/Tauri build needs the MSVC toolchain, so the script
 * auto-locates VsDevCmd.bat (via vswhere, then known install paths; override
 * with OVION_VS_DEVCMD) and initializes it before `npm run tauri dev`, and it
 * strips Git's /usr/bin from PATH (its link.exe shadows the MSVC linker).
 */

import { spawn, spawnSync } from "node:child_process";
import net from "node:net";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = path.dirname(fileURLToPath(import.meta.url));
const isWin = process.platform === "win32";
const ComSpec = process.env.ComSpec || "cmd.exe";

// ── Paths ────────────────────────────────────────────────────────────────────
const FRONTEND_DIR = path.join(ROOT, "penpot-source", "frontend");
const PUBLIC_DIR = path.join(FRONTEND_DIR, "resources", "public");
const FRONTEND_BUNDLE = path.join(PUBLIC_DIR, "js", "shared.js");
const BACKEND_JAR = path.join(ROOT, "penpot-source", "backend", "target", "penpot.jar");
const PG_BIN = path.join(ROOT, "tools", "postgres", "bin");
const REDIS_BIN = path.join(ROOT, "tools", "redis", "Redis-8.8.0-Windows-x64-msys2", "redis-server.exe");
const DATA_DIR = path.join(ROOT, "data");
const PG_DATA = path.join(DATA_DIR, "postgres");
const REDIS_DATA = path.join(DATA_DIR, "redis");
const PG_LOG = path.join(DATA_DIR, "postgres.log");

const PORTS = { proxy: 1420, backend: 3449, postgres: 5432, redis: 6379 };

// ── CLI ──────────────────────────────────────────────────────────────────────
const argv = process.argv.slice(2);
const has = (f) => argv.includes(f);
const MODE_BACKEND = has("--backend-only");
const BUILD_FRONTEND = has("--build-frontend") ? true : has("--no-build-frontend") ? false : "auto";
const SKIP_ICONS = has("--skip-icons");
const DO_STOP = has("--stop");

function usage() {
  console.log(`
${bold("run_ovion.mjs")} — local test runner for Ovion Desktop

USAGE
  node run_ovion.mjs [flags]

MODES
  (default)   Full app test: icons -> backend jar check -> frontend build
              (only if the JS bundle is missing) -> launch the app via
              \`npm run tauri dev\`. The app boots Postgres + Redis + the JVM
              backend itself (progress shows on the splash screen).
  --backend-only
              Start Postgres + Redis + JVM backend + frontend proxy and keep
              them running. For exercising the backend/API without the window.

FLAGS
  --build-frontend     Force the full frontend build (wasm artifacts,
                       templates, ClojureScript release). Slow but complete.
  --no-build-frontend  Never build the frontend, even if the bundle is missing.
  --skip-icons         Do not regenerate src-tauri/icons.
  --stop               Kill whatever listens on :1420 / :3449 / :5432 / :6379
                       (services + proxy from a previous run).
  --help               Show this help.

ENV
  OVION_VS_DEVCMD  Path to VsDevCmd.bat when auto-detection fails.
`);
}

// ── Pretty logging ───────────────────────────────────────────────────────────
const useColor = process.stdout.isTTY && !process.env.NO_COLOR;
const paint = (code, s) => (useColor ? code + s + "\x1b[0m" : s);
const bold = (s) => paint("\x1b[1m", s);
const dim = (s) => paint("\x1b[2m", s);
const cyan = (s) => paint("\x1b[36m", s);
const green = (s) => paint("\x1b[32m", s);
const yellow = (s) => paint("\x1b[33m", s);
const red = (s) => paint("\x1b[31m", s);

const log = {
  step: (s) => console.log(`\n${bold(cyan("◆ "))}${bold(s)}`),
  info: (s) => console.log(`  ${cyan("›")} ${s}`),
  ok: (s) => console.log(`  ${green("✓")} ${s}`),
  warn: (s) => console.log(`  ${yellow("⚠")} ${s}`),
  err: (s) => console.error(`  ${red("✗")} ${s}`),
};

if (has("--help") || has("-h")) {
  usage();
  process.exit(0);
}

// ── Small helpers ────────────────────────────────────────────────────────────
const exists = (p) => fs.existsSync(p);
const winExe = (exe) =>
  isWin && !/\.(exe|bat|cmd|ps1)$/i.test(exe) ? `${exe}.cmd` : exe;

function runSync(cmd, args, opts = {}) {
  return spawnSync(cmd, args, { encoding: "utf8", shell: isWin, windowsHide: true, ...opts });
}

function toolOk(cmd, args) {
  try {
    return runSync(cmd, args, { stdio: "ignore" }).status === 0;
  } catch {
    return false;
  }
}

function waitPort(port, timeoutMs, label) {
  const start = Date.now();
  return new Promise((resolve, reject) => {
    let done = false;
    const tick = () => {
      if (done) return;
      const s = net.connect({ host: "127.0.0.1", port }, () => {
        s.destroy();
        done = true;
        resolve();
      });
      s.on("error", () => {
        s.destroy();
        if (done) return;
        if (Date.now() - start > timeoutMs) {
          done = true;
          reject(new Error(`${label || `port ${port}`} did not become ready in ${Math.round(timeoutMs / 1000)}s`));
        } else {
          setTimeout(tick, 400);
        }
      });
    };
    tick();
  });
}

function portPids(port, onlyListening = false) {
  // Windows netstat -ano columns: Proto, Local, Foreign, State, PID.
  if (!isWin) return [];
  const out = runSync("netstat", ["-ano", "-p", "tcp"], { stdio: ["ignore", "pipe", "ignore"] });
  const pids = new Set();
  for (const line of (out.stdout || "").split(/\r?\n/)) {
    const toks = line.trim().split(/\s+/);
    if (toks.length >= 5 && toks[0] === "TCP" && toks[1].endsWith(`:${port}`)) {
      if (onlyListening && toks[3] !== "LISTENING") continue;
      const pid = toks[toks.length - 1];
      if (pid && pid !== "0") pids.add(pid);
    }
  }
  return [...pids];
}

function killPids(pids) {
  for (const pid of pids) {
    try {
      spawnSync("taskkill", ["/PID", String(pid), "/F", "/T"], { stdio: "ignore" });
    } catch {}
  }
}

// ── VS Build Tools detection (Windows) ───────────────────────────────────────
function findVsDevCmd() {
  if (process.env.OVION_VS_DEVCMD) return process.env.OVION_VS_DEVCMD;

  const candidates = [];
  const vswhere = "C:\\Program Files (x86)\\Microsoft Visual Studio\\Installer\\vswhere.exe";
  if (exists(vswhere)) {
    const r = runSync(vswhere, [
      "-latest", "-products", "*",
      "-requires", "Microsoft.VisualStudio.Component.VC.Tools.x86.x64",
      "-property", "installationPath",
    ]);
    if (r.status === 0 && r.stdout.trim()) {
      candidates.push(path.join(r.stdout.trim(), "Common7", "Tools", "VsDevCmd.bat"));
    }
  }
  candidates.push(
    "D:\\Microsoft Visual Studio\\18\\Community\\Common7\\Tools\\VsDevCmd.bat",
    "C:\\Program Files (x86)\\Microsoft Visual Studio\\18\\BuildTools\\Common7\\Tools\\VsDevCmd.bat",
    "C:\\Program Files\\Microsoft Visual Studio\\2022\\BuildTools\\Common7\\Tools\\VsDevCmd.bat",
    "C:\\Program Files\\Microsoft Visual Studio\\2022\\Community\\Common7\\Tools\\VsDevCmd.bat",
  );
  return candidates.find(exists) || null;
}

function cleanPathForMsBuild() {
  // Git's usr/bin ships a link.exe that shadows the MSVC linker.
  const keep = [];
  if (process.env.USERPROFILE) {
    const cargoBin = path.join(process.env.USERPROFILE, ".cargo", "bin");
    if (exists(cargoBin)) keep.push(cargoBin);
  }
  for (const p of (process.env.PATH || "").split(path.delimiter)) {
    if (!/git[\\/]usr[\\/]bin$/i.test(p) && !keep.includes(p)) keep.push(p);
  }
  return keep.join(path.delimiter);
}

// ── Environment checks ───────────────────────────────────────────────────────
function checkPrereqs({ needFrontendBuild, needRust }) {
  log.step("Checking prerequisites");
  let ok = true;

  if (process.env.USERPROFILE) {
    const cargoBin = path.join(process.env.USERPROFILE, ".cargo", "bin");
    if (exists(cargoBin) && !process.env.PATH.includes(cargoBin)) {
      process.env.PATH = `${cargoBin}${path.delimiter}${process.env.PATH}`;
    }
  }

  const report = (name, good, hint) => {
    if (good) log.ok(name);
    else {
      log.err(`${name} not found${hint ? ` — ${hint}` : ""}`);
      ok = false;
    }
  };

  report("Node", true, "");
  report("npm", toolOk(winExe("npm"), ["-v"]), "install Node.js (includes npm)");
  report("Java", toolOk("java", ["-version"]), "install a JRE/JDK (Java 17+) — the backend runs on it");
  if (needRust) {
    report("Cargo/Rust", toolOk("cargo", ["--version"]), "install Rust via rustup (MSVC toolchain)");
  }

  if (needFrontendBuild) {
    report("pnpm", toolOk(winExe("pnpm"), ["-v"]), "run `npm i -g pnpm`");
    const depsCljDir = process.env.USERPROFILE ? path.join(process.env.USERPROFILE, "deps.clj") : null;
    const hasDepsClj = (depsCljDir && exists(depsCljDir) && fs.readdirSync(depsCljDir).some((f) => /^clojure(\.exe|\.bat)?$/i.test(f)));
    report("clojure (deps.clj)", hasDepsClj || toolOk("clojure", ["-Sdescribe"]), "install deps.clj (https://github.com/borkdude/deps.clj)");
  }

  if (isWin && !MODE_BACKEND) {
    const vs = findVsDevCmd();
    if (vs) log.ok(`Visual Studio Build Tools (${path.basename(path.dirname(path.dirname(path.dirname(vs))))})`);
    else log.warn("Visual Studio Build Tools not auto-detected — the Rust build may fail. Set OVION_VS_DEVCMD to VsDevCmd.bat.");
  }

  if (!ok) {
    log.err("Missing prerequisites. Fix the items above and re-run.");
    process.exit(1);
  }
}

// ── Steps ────────────────────────────────────────────────────────────────────
async function generateIcons() {
  log.step("Generating app icons");
  await runInherit(process.execPath, [path.join(ROOT, "scripts", "generate-icons.mjs")], ROOT);
  log.ok("Icons regenerated in src-tauri/icons");
}

async function buildFrontend() {
  log.step("Building the Penpot frontend (this takes a while)");
  const env = { ...process.env };
  if (process.env.USERPROFILE) {
    const depsClj = path.join(process.env.USERPROFILE, "deps.clj");
    const currentPath = process.env.PATH || process.env.Path || "";
    if (exists(depsClj)) {
      env.PATH = `${depsClj}${path.delimiter}${currentPath}`;
      env.Path = `${depsClj}${path.delimiter}${currentPath}`;
    }
  }
  await runInherit("npm", ["run", "build:penpot"], ROOT, env);
  log.ok("Frontend built");
}

function checkBackendJar() {
  if (exists(BACKEND_JAR)) {
    log.ok(`Backend jar present (${(fs.statSync(BACKEND_JAR).size / 1024 / 1024).toFixed(1)} MB)`);
    return true;
  }
  log.err("Backend jar missing: penpot-source/backend/target/penpot.jar");
  log.info("Build it from penpot-source/backend:");
  log.info("    clojure -T:build jar");
  return false;
}

// Spawn a process with inherited stdio and await its exit (reject on nonzero).
function runInherit(cmd, args, cwd = ROOT, env = process.env) {
  return new Promise((resolve, reject) => {
    const formattedCmd = isWin && cmd.includes(" ") && !cmd.startsWith('"') ? `"${cmd}"` : cmd;
    const child = spawn(formattedCmd, args, { cwd, env, stdio: "inherit", windowsHide: true, shell: isWin });
    child.on("error", reject);
    child.on("exit", (code) => (code === 0 ? resolve() : reject(new Error(`${cmd} exited with code ${code}`))));
  });
}

// ── App mode: launch the app (it boots services + backend itself) ────────────
function launchTauriDev() {
  log.step("Launching the app (npm run tauri dev)");
  log.warn("This clears any services currently on :1420/:3449/:5432/:6379 and reboots them fresh.");
  log.info("The app boots Postgres + Redis + the backend itself — watch the splash screen.");
  log.info("Close the window to stop everything gracefully. If services linger after Ctrl+C,");
  log.info("run:  node run_ovion.mjs --stop");

  if (!isWin) {
    return spawn(winExe("npm"), ["run", "tauri", "dev"], { cwd: ROOT, env: process.env, stdio: "inherit", windowsHide: true });
  }

  const env = { ...process.env, PATH: cleanPathForMsBuild() };
  const vs = findVsDevCmd();
  if (vs) {
    log.info(`Initializing VS Build Tools: ${vs}`);
    // shell:true runs this via `cmd /d /s /c "…"`; the surrounding quotes are
    // stripped by /s and the quotes around the VsDevCmd path survive. `&` runs
    // npm even if VsDevCmd reports an error. Stdio is hidden for VsDevCmd's
    // banner; npm/tauri output stays visible.
    const cmdLine = `call "${vs}" >nul 2>&1 & npm run tauri dev`;
    return spawn(cmdLine, { cwd: ROOT, env, stdio: "inherit", windowsHide: true, shell: true });
  }
  log.warn("VsDevCmd.bat not found — if the Rust build fails, set OVION_VS_DEVCMD.");
  return spawn(winExe("npm"), ["run", "tauri", "dev"], { cwd: ROOT, env, stdio: "inherit", windowsHide: true });
}

// ── Backend-only mode ────────────────────────────────────────────────────────
const started = { postgres: false, redis: false, backend: false, proxy: false };
let children = [];

function stopStarted() {
  // Reverse order: proxy, backend, redis, postgres.
  for (const child of [...children].reverse()) {
    try {
      if (isWin) spawnSync("taskkill", ["/pid", String(child.pid), "/T", "/F"], { stdio: "ignore" });
      else child.kill("SIGTERM");
    } catch {}
  }
  children = [];
  if (started.postgres) {
    const pgCtl = path.join(PG_BIN, "pg_ctl.exe");
    runSync(pgCtl, ["-D", PG_DATA, "-m", "fast", "-w", "-t", "15", "stop"], { stdio: "ignore" });
  }
}

function spawnTracked(cmd, args, opts = {}) {
  const child = spawn(cmd, args, { stdio: "inherit", windowsHide: true, shell: false, ...opts });
  child.on("error", (e) => log.err(`Failed to start ${path.basename(String(cmd))}: ${e.message}`));
  children.push(child);
  return child;
}

async function ensurePostgres() {
  log.step("Starting PostgreSQL");
  fs.mkdirSync(PG_DATA, { recursive: true });
  const pgEnv = { ...process.env, PATH: `${PG_BIN}${path.delimiter}${process.env.PATH}` };
  const fresh = !exists(path.join(PG_DATA, "PG_VERSION"));

  if (fresh) {
    log.info("Initializing Postgres data directory (first run)…");
    const init = runSync(path.join(PG_BIN, "initdb.exe"), ["-D", PG_DATA, "--username=postgres", "--auth=trust", "--encoding=UTF8", "--locale=C"], { env: pgEnv });
    if (init.status !== 0) {
      throw new Error(`initdb failed: ${(init.stderr || init.stdout || "").trim().slice(0, 500)}`);
    }
  }

  if (!portPids(PORTS.postgres, true).length) {
    // Null stdio: the long-lived postmaster grandchild would keep inherited
    // pipe handles open forever and make the sync wait block.
    const start = runSync(path.join(PG_BIN, "pg_ctl.exe"), ["-D", PG_DATA, "-l", PG_LOG, "start"], { env: pgEnv, stdio: "ignore" });
    if (start.status !== 0) throw new Error("pg_ctl start failed");
    await waitPort(PORTS.postgres, 30_000, "PostgreSQL");
  } else {
    log.info("PostgreSQL already running.");
  }

  // Idempotent role + database creation (mirrors start_services.bat).
  runSync(path.join(PG_BIN, "createuser.exe"), ["-U", "postgres", "-h", "localhost", "-p", "5432", "-w", "penpot"], { env: pgEnv, stdio: "ignore" });
  runSync(path.join(PG_BIN, "createdb.exe"), ["-U", "postgres", "-h", "localhost", "-p", "5432", "-O", "penpot", "penpot"], { env: pgEnv, stdio: "ignore" });
  started.postgres = true;
  log.ok("PostgreSQL ready on :5432");
}

async function ensureRedis() {
  log.step("Starting Redis");
  if (portPids(PORTS.redis, true).length) {
    log.info("Redis already running.");
    return;
  }
  fs.mkdirSync(REDIS_DATA, { recursive: true });
  const child = spawnTracked(REDIS_BIN, [
    "--bind", "127.0.0.1", "--port", "6379", "--loglevel", "notice", "--dir", REDIS_DATA,
  ]);
  await waitPort(PORTS.redis, 30_000, "Redis");
  started.redis = true;
  log.ok("Redis ready on :6379");
}

function backendEnv() {
  const imagemagick = path.join(ROOT, "tools", "imagemagick");
  const pathEnv = exists(imagemagick)
    ? `${imagemagick}${path.delimiter}${process.env.PATH}`
    : process.env.PATH;
  // Relative storage dirs (cwd = ROOT) match run_backend.bat and dodge the
  // backend's datoteka fs normalize path-doubling for absolute drive paths.
  const storageDir = path.join("data", "assets");
  return {
    ...process.env,
    PATH: pathEnv,
    PENPOT_TENANT: "default",
    PENPOT_HOST: "localhost",
    PENPOT_PUBLIC_URI: `http://localhost:${PORTS.proxy}`,
    PENPOT_HTTP_SERVER_PORT: String(PORTS.backend),
    PENPOT_HTTP_SERVER_HOST: "localhost",
    PENPOT_DATABASE_URI: "postgresql://localhost/penpot",
    PENPOT_DATABASE_USERNAME: "penpot",
    PENPOT_DATABASE_PASSWORD: "penpot",
    PENPOT_REDIS_URI: "redis://localhost/0",
    PENPOT_OBJECTS_STORAGE_BACKEND: "fs",
    PENPOT_OBJECTS_STORAGE_FS_DIRECTORY: storageDir,
    PENPOT_STORAGE_ASSETS_FS_DIRECTORY: storageDir,
    PENPOT_ASSETS_PATH: "/internal/assets/",
    PENPOT_SECRET_KEY: "desktop-local-secret-key-change-in-production",
    PENPOT_FLAGS:
      "disable-secure-session-cookies disable-email-verification disable-google-fonts-provider " +
      "disable-dashboard-templates-section disable-telemetry enable-backend-worker enable-demo-users " +
      "enable-cors disable-feature-render-wasm disable-render-switch disable-render-wasm-info " +
      "disable-available-viewer-wasm disable-render-wasm-dpr",
    PENPOT_ALLOWED_ORIGINS: `http://localhost:${PORTS.proxy} http://localhost:${PORTS.backend}`,
    PENPOT_TELEMETRY_ENABLED: "false",
  };
}

async function ensureBackend() {
  log.step("Starting the JVM backend (penpot.jar)");
  if (!exists(BACKEND_JAR)) throw new Error("Backend jar missing — run `clojure -T:build jar` in penpot-source/backend first.");
  const java = process.env.JAVA_HOME && exists(path.join(process.env.JAVA_HOME, "bin", isWin ? "java.exe" : "java"))
    ? path.join(process.env.JAVA_HOME, "bin", isWin ? "java.exe" : "java")
    : "java";
  spawnTracked(java, ["-jar", BACKEND_JAR, "-m", "app.main"], { cwd: ROOT, env: backendEnv() });
  await waitPort(PORTS.backend, 180_000, "Backend");
  started.backend = true;
  log.ok("Backend ready on :3449");
}

async function ensureProxy() {
  log.step("Starting the frontend proxy (:1420, auto-login)");
  // inject-desktop-config was already run once at the start of backend-only
  // mode (it clears stale listeners and writes config.js/index.html/loading.html
  // + splash), so here we only bring up the proxy itself.
  spawnTracked(process.execPath, [path.join(ROOT, "scripts", "serve-penpot-proxy.js")]);
  await waitPort(PORTS.proxy, 30_000, "Frontend proxy");
  started.proxy = true;
  log.ok("Proxy ready — dashboard at http://localhost:1420");
}

async function runBackendOnly() {
  log.step("backend-only mode");
  log.info("Bringing up Postgres + Redis + backend + proxy.");
  log.info("NOTE: the inject step below clears any stale listeners on 1420/3449/5432/6379 first.");
  // Order matters: inject clears ports BEFORE we start fresh services.
  await runInherit(process.execPath, [path.join(ROOT, "scripts", "inject-desktop-config.js")], ROOT);
  await ensurePostgres();
  await ensureRedis();
  await ensureBackend();
  await ensureProxy();

  console.log(`\n${bold(green("All services up."))}`);
  console.log(`  ${dim("Frontend (proxy + auto-login):")} ${cyan("http://localhost:1420")}`);
  console.log(`  ${dim("Backend API:")}                 ${cyan("http://localhost:3449")}`);
  console.log(`  ${dim("PostgreSQL / Redis:")}          ${cyan(":5432 / :6379")}`);
  console.log(`${dim("Press Ctrl+C to shut down what this run started.")}\n`);

  await new Promise((resolve) => {
    process.on("SIGINT", () => { console.log("\nStopping services…"); stopStarted(); resolve(); });
    process.on("SIGTERM", () => { stopStarted(); resolve(); });
  });
}

// ── Stop mode ────────────────────────────────────────────────────────────────
function stopPorts() {
  log.step("Stopping app processes");
  log.warn("This kills everything listening on :1420, :3449, :5432 and :6379.");
  for (const [name, port] of Object.entries(PORTS)) {
    const pids = portPids(port);
    if (pids.length) {
      killPids(pids);
      log.ok(`Port ${port} (${name}): stopped ${pids.join(", ")}`);
    } else {
      log.info(`Port ${port} (${name}): nothing listening`);
    }
  }
}

// ── Main ─────────────────────────────────────────────────────────────────────
async function main() {
  console.log(`\n${bold(cyan("◆ Ovion Desktop — local test runner"))} ${dim(`(node ${process.version} · ${process.platform})`)}`);

  if (DO_STOP) {
    stopPorts();
    return;
  }

  if (MODE_BACKEND) {
    checkPrereqs({ needFrontendBuild: false, needRust: false });
    await runBackendOnly();
    return;
  }

  // Full app-test mode.
  const willBuildFrontend =
    BUILD_FRONTEND === true || (BUILD_FRONTEND === "auto" && !exists(FRONTEND_BUNDLE));
  checkPrereqs({ needFrontendBuild: willBuildFrontend, needRust: true });

  if (!SKIP_ICONS) await generateIcons();

  if (!checkBackendJar()) {
    log.err("Build the backend jar first (see instructions above), then re-run.");
    process.exit(1);
  }

  if (willBuildFrontend) {
    await buildFrontend();
  } else {
    log.step("Frontend");
    log.ok(exists(FRONTEND_BUNDLE)
      ? "JS bundle present — skipping build (use --build-frontend to force)."
      : "JS bundle missing (--no-build-frontend). The app will not render.");
  }

  // Launch the app. It owns the proxy (via beforeDevCommand) and boots the
  // services + backend itself; we just keep the terminal attached and clean up
  // the process tree on exit.
  const child = launchTauriDev();
  let exited = false;
  child.on("error", (e) => {
    log.err(`Failed to launch: ${e.message}`);
    process.exit(1);
  });
  child.on("exit", (code) => {
    exited = true;
    if (code !== 0 && code !== null) log.warn(`npm run tauri dev exited with code ${code}`);
    process.exit(code ?? 0);
  });

  const cleanup = () => {
    if (exited) return;
    console.log("\nStopping…");
    if (isWin) {
      try { spawnSync("taskkill", ["/pid", String(child.pid), "/T", "/F"], { stdio: "ignore" }); } catch {}
    } else {
      try { child.kill("SIGTERM"); } catch {}
    }
  };
  process.on("SIGINT", () => { cleanup(); process.exit(130); });
  process.on("SIGTERM", () => { cleanup(); process.exit(143); });
}

main().catch((err) => {
  log.err(err.message || String(err));
  if (MODE_BACKEND) stopStarted();
  process.exit(1);
});
