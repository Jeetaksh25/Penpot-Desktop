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
const OUT_DIR = path.join(ROOT, "icons");

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

  console.log(`\nIcons written to ${OUT_DIR}`);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
