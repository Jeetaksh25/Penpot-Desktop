// Icon generator for Penpot Desktop.
// Uses the actual Penpot SVG logo from data/assets/ to generate all
// Tauri app icon sizes at native resolution with proper antialiasing.
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

// ── Source logos ----------------------------------------------------------
// The user placed these in data/assets/. We prefer the SVG for crisp vector
// scaling; fall back to the PNG if sharp is unavailable.

const SVG_SOURCE = path.join(ROOT, "data/assets/penpot-light.svg");
const PNG_SOURCE = path.join(ROOT, "data/assets/penpot-light.png");

// Penpot brand indigo used as the icon background colour.
const BRAND_BG = "#1d1f26";

// Target sizes for Tauri (tauri.conf.json references these).
const SIZES = [
  { name: "32x32.png",       size: 32   },
  { name: "128x128.png",     size: 128  },
  { name: "128x128@2x.png",  size: 256  },
  { name: "icon.png",        size: 1024 },
];

// Sizes embedded in icon.ico (PNG-compressed entries, supported since Vista).
// Tauri uses icon.ico for the Windows .exe icon AND the NSIS installer icon,
// so it must be regenerated from the branding — the scaffolced default
// icon.ico (blue/white Tauri logo) must not survive a build.
const ICO_SIZES = [16, 32, 48, 64, 128, 256];

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

// ── Pre-read source logo ─────────────────────────────────────────────────
// The raw Penpot logo SVG has viewBox="62.2 0 387.7 512" — the logo content
// occupies a 387.7×512 area starting at offset (62.2, 0).
// We cache the path content so the wrapped SVG builder doesn't re-read it
// for every icon size.

const VIEWBOX_MIN_X = 62.2;
const LOGO_W = 387.7;  // viewBox width — the logo content is 387.7 units wide
const LOGO_H = 512;    // viewBox height

let _svgContent = null;
function getSvgContent() {
  if (!_svgContent) {
    _svgContent = fs
      .readFileSync(SVG_SOURCE, "utf-8")
      .replace(/<svg[^>]*>/i, "")   // strip outer <svg …>
      .replace(/<\/svg>/i, "");     // strip </svg>
  }
  return _svgContent;
}

// ── SVG wrapper ───────────────────────────────────────────────────────────
// Wraps the raw logo paths in a new square <svg> so that sharp renders the
// logo centred on a brand-colour background at exactly the requested size.
//
// The original SVG has viewBox="62.2 0 387.7 512" so paths use coordinates
// offset by +62.2 in X. We compensate with a three-part transform:
//   translate(cx, cy)  scale(s)  translate(-62.2, 0)
//   1. shift logo so its left edge lands at x=0
//   2. scale to fit our padding-inscribed square
//   3. centre the result in the icon square

function wrappedSvg(width) {
  const padding = Math.round(width * 0.12); // 12 % padding inside the square
  const inner   = width - padding * 2;
  const scale   = Math.min(inner / LOGO_W, inner / LOGO_H);
  const drawW   = Math.round(LOGO_W * scale);
  const drawH   = Math.round(LOGO_H * scale);
  const dx      = Math.round((width - drawW) / 2);
  const dy      = Math.round((width - drawH) / 2);

  return `<svg xmlns="http://www.w3.org/2000/svg"
             width="${width}" height="${width}"
             viewBox="0 0 ${width} ${width}">
    <rect width="${width}" height="${width}" fill="${BRAND_BG}" rx="${Math.round(width * 0.22)}"/>
    <g transform="translate(${dx}, ${dy}) scale(${scale.toFixed(6)}) translate(-${VIEWBOX_MIN_X}, 0)">
      ${getSvgContent()}
    </g>
  </svg>`;
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

  const svgExists = fs.existsSync(SVG_SOURCE);
  const pngExists = fs.existsSync(PNG_SOURCE);

  if (!svgExists && !pngExists) {
    console.error("No logo source found. Place penpot-light.svg (or .png) in data/assets/");
    process.exit(1);
  }

  for (const { name, size } of SIZES) {
    const outPath = path.join(OUT_DIR, name);

    if (svgExists) {
      // SVG path — render directly at the target size via sharp (librsvg).
      const svg = wrappedSvg(size);
      await sharp(Buffer.from(svg))
        .png()
        .toFile(outPath);
    } else {
      // PNG fallback — resize from source.
      await sharp(PNG_SOURCE)
        .resize(size, size, { fit: "contain", background: BRAND_BG })
        .png()
        .toFile(outPath);
    }

    const stat = fs.statSync(outPath);
    const kb = (stat.size / 1024).toFixed(1);
    console.log(`Generated ${name}  (${size}x${size}, ${kb} KB)`);
  }

  // Build icon.ico from the branding so the Windows .exe and the NSIS
  // installer both show the real Penpot icon instead of the default Tauri one.
  const icoEntries = [];
  for (const size of ICO_SIZES) {
    let png;
    if (svgExists) {
      const svg = wrappedSvg(size);
      png = await sharp(Buffer.from(svg)).png().toBuffer();
    } else {
      png = await sharp(PNG_SOURCE)
        .resize(size, size, { fit: "contain", background: BRAND_BG })
        .png()
        .toBuffer();
    }
    icoEntries.push({ size, png });
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
