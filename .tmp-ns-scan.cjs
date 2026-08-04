const fs = require("fs");
const path = require("path");
const ROOT = "D:/TestProjects/Penpot-desktop/penpot-source/frontend/src";

function walk(d) {
  let o = [];
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const p = path.join(d, e.name);
    if (e.isDirectory()) o = o.concat(walk(p));
    else if (/\.clj[cs]?$/.test(e.name)) o.push(p);
  }
  return o;
}

// shadow-cljs derives the expected namespace from the file PATH
// (underscores in the filename become hyphens in the namespace), and
// requires the declared `ns` to match. So any UNDERSCORE in a namespace
// name is a bug (it will mismatch the path-derived name). Hyphenated
// FILENAMES are also a bug (the file should use underscores). Detect both.
const bad = [];
for (const f of walk(ROOT)) {
  const c = fs.readFileSync(f, "utf8");
  const m = c.match(/\(ns\s+([^\s()]+)/);
  if (!m) continue;
  const ns = m[1];
  const ext = path.extname(f);
  const actualRel = path.relative(ROOT, f).split(path.sep).join("/");
  // path-derived ns: drop ext, slashes->dots, underscores->hyphens
  const pathDerivedNs = actualRel
    .slice(0, -ext.length)
    .split("/").join(".")
    .replace(/_/g, "-");
  if (ns !== pathDerivedNs) {
    bad.push({ file: actualRel, declaredNs: ns, pathDerivedNs });
  }
}
if (bad.length === 0) console.log("OK: all ns declarations match path-derived names");
else {
  console.log("MISMATCHES (" + bad.length + "):");
  for (const b of bad) console.log(`  file=${b.file}\n    declared=${b.declaredNs}\n    expected=${b.pathDerivedNs}`);
}