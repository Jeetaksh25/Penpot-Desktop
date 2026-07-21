// Downloads the builtin template .penpot files listed in the backend's
// onboarding.edn so they ship inside the installer and the app does not need
// to fetch them from GitHub at runtime.
//
// app.setup.templates/get-template-stream (penpot-source/backend/src/app/setup/
// templates.clj) looks for each template at <backend-cwd>/builtin-templates/<id>
// FIRST, and only falls back to an HTTP GET of :file-uri if that file is
// absent. The backend CWD is the install dir (spawn_backend sets
// cmd.current_dir(&root)), and bundle.resources maps this dir to
// <install>/builtin-templates/, so a file saved here as <id> (no extension) is
// found locally and the GitHub download never runs. This is what makes
// templates work on an offline desktop app.
//
// Best-effort and idempotent: existing files are skipped (use --force to
// re-download); a download failure is logged and skipped, never thrown — the
// runtime GitHub fallback still covers that template if the machine is online.
import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(__dirname, "..");

const onboardingPath = path.join(
  repoRoot,
  "penpot-source",
  "backend",
  "resources",
  "app",
  "onboarding.edn",
);
const destDir = path.join(repoRoot, "data", "builtin-templates");

const force = process.argv.includes("--force");

// onboarding.edn is a flat vector of maps with :id and :file-uri string
// values. A full EDN parser is overkill; pull the pairs out in order.
function parseTemplates(edn) {
  const ids = [...edn.matchAll(/:id\s+"([^"]+)"/g)].map((m) => m[1]);
  const uris = [...edn.matchAll(/:file-uri\s+"([^"]+)"/g)].map((m) => m[1]);
  if (ids.length !== uris.length) {
    throw new Error(
      `onboarding.edn parse mismatch: ${ids.length} ids vs ${uris.length} file-uris`,
    );
  }
  return ids.map((id, i) => ({ id, uri: uris[i] }));
}

async function main() {
  const edn = await fs.readFile(onboardingPath, "utf-8");
  const templates = parseTemplates(edn);
  await fs.mkdir(destDir, { recursive: true });

  let ok = 0;
  let skipped = 0;
  let failed = 0;
  for (const { id, uri } of templates) {
    const dest = path.join(destDir, id);
    if (!force) {
      try {
        await fs.access(dest);
        skipped++;
        continue;
      } catch {}
    }
    try {
      const res = await fetch(uri, { redirect: "follow" });
      if (!res.ok || !res.body) {
        throw new Error(`HTTP ${res.status}`);
      }
      const buf = Buffer.from(await res.arrayBuffer());
      await fs.writeFile(dest, buf);
      ok++;
      console.log(`template: fetched ${id} (${(buf.length / 1024).toFixed(0)}K)`);
    } catch (e) {
      failed++;
      console.warn(`template: FAILED ${id} (${e.message}) — runtime GitHub fallback will be used`);
    }
  }
  console.log(
    `templates: ${ok} fetched, ${skipped} present, ${failed} failed (of ${templates.length})`,
  );
}

main().catch((err) => {
  // Never hard-fail the build: templates are best-effort offline.
  console.warn(`fetch-templates: ${err.message}`);
});