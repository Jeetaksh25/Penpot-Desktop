import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import proc from "node:child_process";
import { promisify } from "node:util";
import os from "node:os";

const exec = promisify(proc.exec);

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(__dirname, "..");

const frontendDir = path.join(repoRoot, "penpot-source", "frontend");
const renderWasmDir = path.join(repoRoot, "penpot-source", "render-wasm");

const isCI = () =>
  process.env.CI === "true" || process.env.GITHUB_ACTIONS === "true";

// Artifacts required for the ClojureScript frontend to compile and for the
// render worker to load. These must exist before `npm run build:penpot`.
const requiredArtifacts = [
  {
    key: "shared-js",
    dest: path.join(
      frontendDir,
      "src",
      "app",
      "render_wasm",
      "api",
      "shared.js",
    ),
    description: "ClojureScript shared constants (required to compile frontend)",
  },
  {
    key: "worker-render-js",
    dest: path.join(
      frontendDir,
      "resources",
      "public",
      "js",
      "worker",
      "render.js",
    ),
    description: "WASM renderer web worker",
  },
];

// Artifacts only needed when the WASM WebGL renderer is enabled. They are
// optional when the app is built with render-wasm disabled (SVG fallback).
const optionalArtifacts = [
  {
    key: "render-wasm-js",
    dest: path.join(
      frontendDir,
      "resources",
      "public",
      "js",
      "render-wasm.js",
    ),
    description: "WASM renderer JS loader",
  },
  {
    key: "render-wasm-wasm",
    dest: path.join(
      frontendDir,
      "resources",
      "public",
      "js",
      "render-wasm.wasm",
    ),
    description: "WASM renderer binary",
  },
];

const allArtifacts = [...requiredArtifacts, ...optionalArtifacts];

async function fileExists(filePath) {
  try {
    await fs.access(filePath);
    return true;
  } catch {
    return false;
  }
}

async function findSharedJsSource() {
  const targetDir = path.join(
    renderWasmDir,
    "target",
    "wasm32-unknown-emscripten",
  );
  try {
    const entries = await fs.readdir(targetDir, { recursive: true });
    for (const entry of entries) {
      if (entry.endsWith("render_wasm_shared.js")) {
        return path.join(targetDir, entry);
      }
    }
  } catch {
    return null;
  }
  return null;
}

async function getWasmTargetFiles() {
  const configs = [
    {
      dir: path.join(
        renderWasmDir,
        "target",
        "wasm32-unknown-emscripten",
        "debug",
      ),
    },
    {
      dir: path.join(
        renderWasmDir,
        "target",
        "wasm32-unknown-emscripten",
        "release",
      ),
    },
  ];

  for (const { dir } of configs) {
    const js = path.join(dir, "render_wasm.js");
    const wasm = path.join(dir, "render_wasm.wasm");
    if ((await fileExists(js)) && (await fileExists(wasm))) {
      return { js, wasm, mode: path.basename(dir) };
    }
  }
  return null;
}

async function copyFile(src, dest) {
  await fs.mkdir(path.dirname(dest), { recursive: true });
  await fs.copyFile(src, dest);
  console.log(
    `Copied ${path.relative(repoRoot, src)} -> ${path.relative(repoRoot, dest)}`,
  );
}

async function buildWorkerRender(mode) {
  const src = path.join(
    renderWasmDir,
    "target",
    "wasm32-unknown-emscripten",
    mode,
    "render_wasm.js",
  );
  const dest = requiredArtifacts.find((a) => a.key === "worker-render-js").dest;
  await fs.mkdir(path.dirname(dest), { recursive: true });

  const esbuildCmd = `pnpm exec esbuild ${JSON.stringify(src)} --log-level=error --outfile=${JSON.stringify(dest)} --platform=neutral --format=iife --global-name=WasmModule`;
  console.log(`Building worker/render.js from ${mode} render_wasm.js...`);
  await exec(esbuildCmd, { cwd: frontendDir });
  console.log(`Built ${path.relative(repoRoot, dest)}`);
}

async function tryCopyArtifacts() {
  const sharedSrc = await findSharedJsSource();
  const wasmFiles = await getWasmTargetFiles();

  if (!sharedSrc || !wasmFiles) {
    return false;
  }

  console.log("Found existing render-wasm build output; copying artifacts...");
  await copyFile(sharedSrc, requiredArtifacts[0].dest);
  await copyFile(wasmFiles.js, optionalArtifacts[0].dest);
  await copyFile(wasmFiles.wasm, optionalArtifacts[1].dest);
  await buildWorkerRender(wasmFiles.mode);
  return true;
}

async function createStubs(missing) {
  console.log(
    "Render-wasm artifacts are not available; creating SVG-fallback stubs...",
  );

  const needsShared = missing.some((a) => a.key === "shared-js");
  if (needsShared) {
    const stubSrc = path.join(repoRoot, "scripts", "shared-js-stub.js");
    if (!(await fileExists(stubSrc))) {
      throw new Error(
        `shared.js stub not found: ${stubSrc}\n` +
          `Build render-wasm locally once to generate the real file, or refresh the stub.`,
      );
    }
    const dest = requiredArtifacts.find((a) => a.key === "shared-js").dest;
    await fs.mkdir(path.dirname(dest), { recursive: true });
    await fs.copyFile(stubSrc, dest);
    console.log(`Stubbed ${path.relative(repoRoot, dest)}`);
  }

  const needsWorker = missing.some((a) => a.key === "worker-render-js");
  if (needsWorker) {
    const dest = requiredArtifacts.find((a) => a.key === "worker-render-js").dest;
    await fs.mkdir(path.dirname(dest), { recursive: true });
    // ponytail: a TRUE no-op. The real render.js is an Emscripten module
    // (esbuild --global-name=WasmModule) that the worker imports via
    // importScripts('./render.js') (shadow-cljs.edn :prepend-js). With no
    // Emscripten build available (CI), this no-op stands in so importScripts
    // does not 404. It deliberately registers NO message listener and defines
    // NO globals: the worker's own on-message (app.worker) handles every
    // message, and render-wasm is disabled via penpotFlags
    // (disable-feature-render-wasm + disable-render-switch), so the SVG
    // renderer is used and js/globalThis "WasmModule" is never dereferenced
    // (app.worker.thumbnails/wasm-module is a delay only realized on the
    // :thumbnails/generate-for-file-wasm path, which the host never sends
    // when render-wasm is off).
    //
    // A prior version of this stub added `self.addEventListener("message",
    // ... self.postMessage({error, payload: event.data})` — that echoed a
    // raw JS object back on EVERY worker message, which the host ran through
    // app.common.transit/decode-str -> JSON.parse("[object Object]") -> a
    // continuous "Something wrong has happened" toast (visible whenever a
    // draft was opened/edited). This no-op avoids that entirely.
    await fs.writeFile(
      dest,
      `// No-op render.js stub for desktop builds without Emscripten WASM.\n` +
        `// The worker imports this via importScripts; with render-wasm disabled\n` +
        `// (penpotFlags) the SVG renderer is used and WasmModule is never used.\n` +
        `// Intentionally no message listener, no postMessage, no globals.\n`,
      "utf-8",
    );
    console.log(`Stubbed ${path.relative(repoRoot, dest)}`);
  }
}

async function runRenderWasmBuild() {
  const isWindows = os.platform() === "win32";
  const script = isWindows
    ? path.join(repoRoot, "build_render_wasm.bat")
    : path.join(renderWasmDir, "build");

  if (!(await fileExists(script))) {
    throw new Error(
      `Render-wasm build script not found: ${script}\n` +
        `Please build render-wasm manually (see DESKTOP.md section "Building render-wasm").`,
    );
  }

  console.log("Running render-wasm build...");
  const command = isWindows ? `cmd /c "${script}"` : `bash "${script}"`;
  const { error, stdout, stderr } = await exec(command, {
    cwd: repoRoot,
    maxBuffer: 1024 * 1024 * 10,
  });
  if (error) {
    throw new Error(
      `render-wasm build failed:\n${stderr || ""}\n${stdout || ""}`,
    );
  }
  console.log(stdout);
  if (stderr) console.error(stderr);
}

async function ensureArtifacts() {
  const missing = [];
  for (const artifact of allArtifacts) {
    if (!(await fileExists(artifact.dest))) {
      missing.push(artifact);
    }
  }

  if (missing.length === 0) {
    console.log("All render-wasm artifacts are present.");
    return;
  }

  console.log(
    `Missing ${missing.length} render-wasm artifact(s):\n` +
      missing
        .map((a) => `  - ${a.description}: ${path.relative(repoRoot, a.dest)}`)
        .join("\n"),
  );

  let copied = await tryCopyArtifacts();
  if (!copied && !isCI()) {
    try {
      await runRenderWasmBuild();
      copied = await tryCopyArtifacts();
    } catch (err) {
      console.warn(err.message);
      console.warn("Falling back to SVG renderer stubs.");
    }
  }

  const stillMissing = [];
  for (const artifact of allArtifacts) {
    if (!(await fileExists(artifact.dest))) {
      stillMissing.push(artifact);
    }
  }

  if (stillMissing.length > 0) {
    // In CI we never try to compile render-wasm (no Emscripten), so stub the
    // compile-time required artifacts and accept that the optional WASM files
    // are absent because the desktop flags disable the WASM renderer.
    if (isCI()) {
      await createStubs(stillMissing);
    } else {
      throw new Error(
        `Render-wasm artifacts still missing after build:\n` +
          stillMissing
            .map((a) => `  - ${path.relative(repoRoot, a.dest)}`)
            .join("\n"),
      );
    }
  }

  // Final sanity check: the frontend cannot compile without shared.js and the
  // worker cannot load without worker/render.js.
  for (const artifact of requiredArtifacts) {
    if (!(await fileExists(artifact.dest))) {
      throw new Error(
        `Required artifact missing: ${path.relative(repoRoot, artifact.dest)}`,
      );
    }
  }

  const optionalMissing = [];
  for (const artifact of optionalArtifacts) {
    if (!(await fileExists(artifact.dest))) {
      optionalMissing.push(artifact);
    }
  }
  if (optionalMissing.length > 0) {
    console.log(
      `Optional WASM renderer artifacts missing (SVG fallback will be used):\n` +
        optionalMissing
          .map((a) => `  - ${path.relative(repoRoot, a.dest)}`)
          .join("\n"),
    );
  }

  console.log("Render-wasm artifacts are ready for the frontend build.");
}

ensureArtifacts().catch((err) => {
  console.error(err.message);
  process.exit(1);
});
