const fs = require("fs");
const file = "penpot-source/frontend/resources/public/js/libs.js";
const text = fs.readFileSync(file, "utf8");
const lines = text.split("\n");
const cols = [38218, 40051, 42088, 43476, 43827, 67927, 83922, 117875, 116902, 116729, 113480, 125609, 124131, 124419, 125675];
const lineIdx = 149;

function enclosingFnName(lineText, col) {
  const before = lineText.slice(0, col);
  let best = null;
  let bestIdx = -1;
  const re = /function\s+([A-Za-z_$][\w$]*)?\s*\(/g;
  let m;
  while ((m = re.exec(before)) !== null) {
    best = m[1] || "(anonymous)";
    bestIdx = m.index;
  }
  const re2 = /([A-Za-z_$][\w$]*)\s*=\s*function\s*\(/g;
  while ((m = re2.exec(before)) !== null) {
    if (m.index >= bestIdx) { best = m[1]; bestIdx = m.index; }
  }
  const re3 = /([A-Za-z_$][\w$]*)\s*=\s*\([^)]*\)\s*=>/g;
  while ((m = re3.exec(before)) !== null) {
    if (m.index >= bestIdx) { best = m[1] + " (arrow)"; bestIdx = m.index; }
  }
  return best;
}

for (const c of cols) {
  const col = c - 1;
  const lineText = lines[lineIdx] || "";
  if (col > lineText.length) { console.log(c, "OUT OF RANGE"); continue; }
  const name = enclosingFnName(lineText, col);
  const ctx = lineText.slice(Math.max(0, col - 150), col).replace(/\n/g, " ");
  console.log(`col ${c}: fn=${name}\n  ctx: ...${ctx}`);
}
