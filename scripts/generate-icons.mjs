// Dependency-free PNG icon generator for Penpot Desktop.
// Uses Node.js built-in zlib for DEFLATE compression.
// Generates: 32x32, 128x128, 256x256 (HiDPI), 1024x1024 (for `cargo tauri icon`).
//
// Usage:  node scripts/generate-icons.mjs

import fs from "node:fs";
import path from "node:path";
import zlib from "node:zlib";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const OUT_DIR = path.resolve(__dirname, "..", "icons");

// Penpot brand colour: indigo-ish (#6366F1) with a white "P"
const BG = { r: 99, g: 102, b: 241 };
const FG = { r: 255, g: 255, b: 255 };

// ---- Minimal PNG writer ---------------------------------------------------

function crc32(buf) {
  // Standard CRC-32 (ISO 3309 / ITU V.42) used by PNG.
  let crc = 0xffffffff;
  for (let i = 0; i < buf.length; i++) {
    crc ^= buf[i];
    for (let j = 0; j < 8; j++) {
      crc = crc & 1 ? (crc >>> 1) ^ 0xedb88320 : crc >>> 1;
    }
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function pngChunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length, 0);
  const typeBytes = Buffer.from(type, "ascii");
  const crcVal = crc32(Buffer.concat([typeBytes, data]));
  const crcBuf = Buffer.alloc(4);
  crcBuf.writeUInt32BE(crcVal, 0);
  return Buffer.concat([len, typeBytes, data, crcBuf]);
}

function solidColorPNG(width, height, r, g, b) {
  // Build raw scanline data: each row is [filter_byte=0, RGBA...]
  const rowBytes = 1 + width * 4;
  const raw = Buffer.alloc(height * rowBytes);

  for (let y = 0; y < height; y++) {
    const off = y * rowBytes;
    raw[off] = 0; // filter: None
    for (let x = 0; x < width; x++) {
      const px = off + 1 + x * 4;
      raw[px] = r;
      raw[px + 1] = g;
      raw[px + 2] = b;
      raw[px + 3] = 255;
    }
  }

  const deflated = zlib.deflateSync(raw, { level: 9 });

  // PNG signature
  const sig = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);

  // IHDR
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8;  // bit depth
  ihdr[9] = 6;  // color type: RGBA
  ihdr[10] = 0; // compression: deflate
  ihdr[11] = 0; // filter: adaptive
  ihdr[12] = 0; // interlace: none

  // Draw a white "P" letter (simple blocky approach for small sizes)
  // For 32x32: 4 rows of "P" bar, 5 rows of "P" bowl
  // For larger sizes, scale the pattern proportionally
  const pWidth = Math.max(1, Math.floor(width * 0.4));
  const pHeight = Math.max(1, Math.floor(height * 0.75));
  const pLeft = Math.floor(width * 0.2);
  const pTop = Math.floor(height * 0.15);
  const barWidth = Math.max(1, Math.floor(pWidth * 0.25));
  const bowlWidth = Math.floor(pWidth * 0.7);
  const bowlTop = pTop;
  const bowlBottom = pTop + Math.floor(pHeight * 0.45);

  for (let y = pTop; y < pTop + pHeight && y < height; y++) {
    const off = y * rowBytes;
    for (let x = pLeft; x < pLeft + pWidth && x < width; x++) {
      // Vertical bar of "P" (always drawn)
      const inBar = x < pLeft + barWidth;
      // Horizontal top bar and middle bar of "P"
      const inTopBar = y >= bowlTop && y <= bowlTop + barWidth && x < pLeft + bowlWidth;
      const inMidBar = y >= bowlBottom - barWidth && y <= bowlBottom && x < pLeft + bowlWidth;
      const inBowl = inTopBar || inMidBar;

      if (inBar || inBowl) {
        const px = off + 1 + x * 4;
        raw[px] = FG.r;
        raw[px + 1] = FG.g;
        raw[px + 2] = FG.b;
        raw[px + 3] = 255;
      }
    }
  }

  const idatContent = deflated;
  return Buffer.concat([sig, pngChunk("IHDR", ihdr), pngChunk("IDAT", idatContent), pngChunk("IEND", Buffer.alloc(0))]);
}

// ---- Generate -------------------------------------------------------------

const sizes = [
  { name: "32x32.png", w: 32, h: 32 },
  { name: "128x128.png", w: 128, h: 128 },
  { name: "128x128@2x.png", w: 256, h: 256 },
  { name: "icon.png", w: 1024, h: 1024 },
];

fs.mkdirSync(OUT_DIR, { recursive: true });

for (const { name, w, h } of sizes) {
  const png = solidColorPNG(w, h, BG.r, BG.g, BG.b);
  const outPath = path.join(OUT_DIR, name);
  fs.writeFileSync(outPath, png);
  const kb = (png.length / 1024).toFixed(1);
  console.log(`Generated ${name}  (${w}x${h}, ${kb} KB)`);
}

console.log(`\nIcons written to ${OUT_DIR}`);
