#!/usr/bin/env node

// build_script.mjs — Penpot Desktop release automation
//
// Usage:
//   node build_script.mjs
//
// This script:
//   1. Reads the current version from package.json
//   2. Asks which type of bump (1=major / 2=minor / 3=patch)
//   3. Updates the version in:
//      - package.json
//      - src-tauri/tauri.conf.json
//      - src-tauri/Cargo.toml
//   4. Generates icons
//   5. Commits, tags, and pushes to GitHub
//
// GitHub Actions then builds the installer automatically — no local build.

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { execSync } from "node:child_process";
import readline from "node:readline";

const __dirname = path.dirname(fileURLToPath(import.meta.url));

// ── Helpers ───────────────────────────────────────────────────────────────

function readFile(p) {
  return fs.readFileSync(path.resolve(__dirname, p), "utf-8");
}

function writeFile(p, content) {
  fs.writeFileSync(path.resolve(__dirname, p), content, "utf-8");
  console.log(`  Updated ${p}`);
}

function ask(question) {
  const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
  return new Promise((resolve) => rl.question(question, (a) => { rl.close(); resolve(a.trim()); }));
}

function exec(cmd) {
  console.log(`\n$ ${cmd}`);
  try {
    const out = execSync(cmd, { cwd: __dirname, stdio: "inherit", encoding: "utf-8" });
    return out || "";
  } catch (e) {
    console.error(`Command failed: ${cmd}`);
    console.error(e.stderr?.toString() || e.message);
    process.exit(1);
  }
}

// ── Version bump logic ────────────────────────────────────────────────────

function bumpVersion(current, type) {
  const parts = current.split(".").map(Number);
  if (parts.length !== 3 || parts.some(isNaN)) {
    console.error(`Invalid version format: ${current}. Expected semver (e.g., 1.0.0)`);
    process.exit(1);
  }
  let [major, minor, patch] = parts;
  switch (type) {
    case "major":
      major += 1;
      minor = 0;
      patch = 0;
      break;
    case "minor":
      minor += 1;
      patch = 0;
      break;
    case "patch":
      patch += 1;
      break;
    default:
      console.error(`Unknown bump type: ${type}. Use major, minor, or patch.`);
      process.exit(1);
  }
  return `${major}.${minor}.${patch}`;
}

// ── Check git status ──────────────────────────────────────────────────────

function checkCleanWorkingTree() {
  try {
    const status = execSync("git status --porcelain", { cwd: __dirname, encoding: "utf-8" }).trim();
    if (status) {
      console.log("\n⚠️  You have uncommitted changes:\n");
      console.log(status);
      console.log("\nPlease commit or stash them first, then re-run this script.");
      process.exit(1);
    }
  } catch {
    // Not a git repo yet or git not available — that's fine for first push
  }
}

async function checkRemote() {
  try {
    const remotes = execSync("git remote -v", { cwd: __dirname, encoding: "utf-8" }).trim();
    if (!remotes.includes("Jeetaksh25/Penpot-Desktop")) {
      console.log("\n⚠️  Remote does not point to github.com/Jeetaksh25/Penpot-Desktop.git");
      console.log(`Current remotes:\n${remotes}`);
      console.log("\nRun this to fix:");
      console.log("  git remote set-url origin https://github.com/Jeetaksh25/Penpot-Desktop.git");
      const yn = await ask("\nContinue anyway? (y/N): ");
      if (yn.toLowerCase() !== "y") process.exit(0);
    }
  } catch {
    // Not a git repo yet
  }
}

// ── Main ──────────────────────────────────────────────────────────────────

async function main() {
  console.log("╔══════════════════════════════════════════════╗");
  console.log("║     Penpot Desktop — Release Builder         ║");
  console.log("╚══════════════════════════════════════════════╝\n");

  const pkgJson = JSON.parse(readFile("package.json"));
  const currentVersion = pkgJson.version;
  console.log(`Current version: ${currentVersion}\n`);

  // 0. Verify git remote
  await checkRemote();

  // 1. Ask for bump type (default: patch — just press Enter)
  const bumpInput = await ask("Bump type?  [1] major  [2] minor  [3] patch (default): ");
  let bumpType;
  switch (bumpInput.trim() || "3") {
    case "1": bumpType = "major"; break;
    case "2": bumpType = "minor"; break;
    case "3": bumpType = "patch"; break;
    default:
      console.error("Invalid choice. Enter 1-3.");
      process.exit(1);
  }
  const newVersion = bumpVersion(currentVersion, bumpType);

  // 2. Single confirmation — does everything: bump, tag, push. CI builds.
  const confirm = await ask(`\n${currentVersion} → v${newVersion}. Push to GitHub? (y/N): `);
  if (confirm.toLowerCase() !== "y") {
    console.log("Aborted.");
    process.exit(0);
  }

  // 3. Update version in files
  console.log("\n--- Updating version files ---");
  writeFile("package.json", JSON.stringify({ ...pkgJson, version: newVersion }, null, 2) + "\n");
  const tauriConf = JSON.parse(readFile("src-tauri/tauri.conf.json"));
  tauriConf.version = newVersion;
  writeFile("src-tauri/tauri.conf.json", JSON.stringify(tauriConf, null, 2) + "\n");
  let cargoToml = readFile("src-tauri/Cargo.toml");
  cargoToml = cargoToml.replace(/^version\s*=\s*"[^"]+"/m, `version = "${newVersion}"`);
  writeFile("src-tauri/Cargo.toml", cargoToml);

  // 4. Regenerate icons
  console.log("\n--- Generating icons ---");
  exec("node scripts/generate-icons.mjs");

  // 5. Git operations (no local build — CI handles everything)
  console.log("\n--- Git operations ---");
  exec("git add -A");

  try {
    exec(`git commit -m "chore: bump version to v${newVersion}"`);
  } catch {
    console.log("  (nothing to commit — already up to date)");
  }

  try {
    exec(`git tag -a "v${newVersion}" -m "Penpot Desktop v${newVersion}"`);
  } catch {
    console.log(`  Tag v${newVersion} already exists — skipping.`);
  }

  // 6. Push
  exec("git push origin main --tags");

  console.log("\n✓ Pushed! GitHub Actions is now building the installer.");
  console.log("  Check progress at: https://github.com/Jeetaksh25/Penpot-Desktop/actions\n");
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
