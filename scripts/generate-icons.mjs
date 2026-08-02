// Icon generator for Oriole Desktop.
// Renders every Tauri app icon size from the brand PNG logo
// (data/assets/penpot-light.png — the master Oriole logo placed there by the
// rebrand). The logo is fitted with `contain` on a transparent background so
// the brand mark is preserved as-is at every size.
//
// Requires: sharp  (install via `npm install`)
//
// Usage:  node scripts/generate-icons.mjs

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, "..");
// Tauri resolves icon paths relative to src-tauri/tauri.conf.json.
// Generate icons there so `bundle.icon` in tauri.conf.json finds them.
const OUT_DIR = path.join(ROOT, "src-tauri", "icons");

// ── Source logo ───────────────────────────────────────────────────────────
// The master brand logo (Oriole) as a PNG. fit:"contain" preserves its aspect
// and content; a transparent background keeps the logo as designed. To put the
// logo on a filled rounded-square tile instead, set FIT background to a solid
// colour (e.g. { r:29, g:31, b:38, alpha:1 }) — left transparent by default so
// the brand mark is shown verbatim.
const PNG_SOURCE = path.join(ROOT, "data/assets/penpot-light.png");

// Target sizes for Tauri (tauri.conf.json references these).
const SIZES = [
  { name: "32x32.png",       size: 32   },
  { name: "128x128.png",     size: 128  },
  { name: "128x128@2x.png",  size: 256  },
  { name: "icon.png",        size: 1024 },
];

// Sizes embedded in icon.ico (PNG-compressed entries, supported since Vista).
// Tauri uses icon.ico for the Windows .exe icon AND the NSIS installer icon,
// so it must be regenerated from the branding — the scaffolded default
// icon.ico (blue/white Tauri logo) must not survive a build.
const ICO_SIZES = [16, 32, 48, 64, 128, 256];

// Transparent background — the logo is rendered verbatim, letterboxed inside
// the square target so its aspect ratio is never distorted.
const BG = { r: 0, g: 0, b: 0, alpha: 0 };

/// Pack an array of {size, png: Buffer} into a Windows .ico file (PNG entries).
function buildIco(entries) {
  const headerSize = 6;
  const dirEntrySize = 16;
  const dirSize = headerSize + entries.length * dirEntrySize;
  const parts = [];
  // ICONDIR: reserved=0, type=1 (icon), count
  const header = Buffer.alloc(headerSize);
  header.writeUInt16LE(0, 0);
  header.writeUInt16LE(1, 2);
  header.writeUInt16LE(entries.length, 4);
  parts.push(header);
  let offset = dirSize;
  for (const { size, png } of entries) {
    const entry = Buffer.alloc(dirEntrySize);
    // 0 in width/height means 256.
    entry.writeUInt8(size >= 256 ? 0 : size, 0);
    entry.writeUInt8(size >= 256 ? 0 : size, 1);
    entry.writeUInt8(0, 2);            // palette
    entry.writeUInt8(0, 3);            // reserved
    entry.writeUInt16LE(1, 4);         // planes
    entry.writeUInt16LE(32, 6);        // bpp
    entry.writeUInt32LE(png.length, 8);
    entry.writeUInt32LE(offset, 12);
    parts.push(entry);
    offset += png.length;
  }
  for (const { png } of entries) parts.push(png);
  return Buffer.concat(parts);
}

// Render the brand logo into a square PNG buffer of the given pixel size.
async function renderPng(sharp, size) {
  return sharp(PNG_SOURCE)
    .resize(size, size, { fit: "contain", background: BG })
    .png()
    .toBuffer();
}

// ── Generate ──────────────────────────────────────────────────────────────

async function main() {
  fs.mkdirSync(OUT_DIR, { recursive: true });

  // Try sharp first (npm dependency, prebuilt binaries).
  let sharp;
  try {
    sharp = (await import("sharp")).default;
  } catch {
    console.error("sharp is not installed. Run:  npm install   or   npm install sharp");
    process.exit(1);
  }

  if (!fs.existsSync(PNG_SOURCE)) {
    console.error("No logo source found. Place penpot-light.png in data/assets/");
    process.exit(1);
  }

  for (const { name, size } of SIZES) {
    const outPath = path.join(OUT_DIR, name);
    const png = await renderPng(sharp, size);
    fs.writeFileSync(outPath, png);
    const kb = (fs.statSync(outPath).size / 1024).toFixed(1);
    console.log(`Generated ${name}  (${size}x${size}, ${kb} KB)`);
  }

  // Build icon.ico from the branding so the Windows .exe and the NSIS
  // installer both show the real Oriole icon instead of the default Tauri one.
  const icoEntries = [];
  for (const size of ICO_SIZES) {
    icoEntries.push({ size, png: await renderPng(sharp, size) });
  }
  const icoPath = path.join(OUT_DIR, "icon.ico");
  fs.writeFileSync(icoPath, buildIco(icoEntries));
  const icoKb = (fs.statSync(icoPath).size / 1024).toFixed(1);
  console.log(`Generated icon.ico  (${ICO_SIZES.join(", ")}, ${icoKb} KB)`);

  console.log(`\nIcons written to ${OUT_DIR}`);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});