const fs = require("fs");
const path = require("path");
const ROOT = "D:/TestProjects/Penpot-desktop";

function walk(d) {
  let o = [];
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const p = path.join(d, e.name);
    if (e.isDirectory()) o = o.concat(walk(p));
    else if (/\.scss$/.test(e.name)) o.push(p);
  }
  return o;
}

// Collect functions defined in the refactor module (@function name)
const refactorRoot = path.join(ROOT, "penpot-source/frontend/resources/styles/common/refactor");
const definedFn = new Set();
for (const f of walk(refactorRoot)) {
  const c = fs.readFileSync(f, "utf8");
  const re = /@function\s+([A-Za-z0-9-]+)/g;
  let m;
  while ((m = re.exec(c))) definedFn.add(m[1]);
}
console.log("refactor functions: " + [...definedFn].sort().join(", "));

// Scan all src scss for deprecated.<name>( usages
const srcRoot = path.join(ROOT, "penpot-source/frontend/src");
const bad = new Map();
for (const f of walk(srcRoot)) {
  const c = fs.readFileSync(f, "utf8");
  if (!/as\s+deprecated/.test(c)) continue;
  const re = /deprecated\.([A-Za-z0-9-]+)\s*\(/g;
  let m;
  const b = new Set();
  while ((m = re.exec(c))) {
    // skip mixin includes are @include deprecated.x — those use the same regex shape but
    // @include is handled separately; here we only flag function-style calls that are NOT mixins.
    // Heuristic: if the call is preceded by "@include " it's a mixin, not a function.
    const start = m.index;
    const prefix = c.slice(Math.max(0, start - 12), start);
    if (/@include\s+$/.test(prefix)) continue; // mixin, skip
    if (!definedFn.has(m[1])) b.add(m[1]);
  }
  if (b.size) bad.set(f.split(ROOT + path.sep).join("").split(path.sep).join("/"), [...b]);
}
if (bad.size === 0) console.log("OK: all deprecated.<fn>( calls resolve to defined functions");
else for (const [f, ns] of bad) console.log("INVALID deprecated." + ns.join(", ") + "()  in " + f);