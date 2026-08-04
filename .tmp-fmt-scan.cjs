const fs = require("fs");
const path = require("path");
const ROOT = "D:/TestProjects/Penpot-desktop/penpot-source/frontend/src/app/util/code_gen";

function walk(d) {
  let o = [];
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const p = path.join(d, e.name);
    if (e.isDirectory()) o = o.concat(walk(p));
    else if (/\.cljs$/.test(e.name)) o.push(p);
  }
  return o;
}

// Count single-% placeholders in a format string (%% = literal %, skipped).
function countPlaceholders(s) {
  let n = 0, i = 0;
  while (i < s.length) {
    if (s[i] === "%") {
      if (s[i + 1] === "%") { i += 2; continue; }
      n++; i++;
    } else i++;
  }
  return n;
}

// Given source text and the index of the opening paren of (dm/fmt ...),
// parse out the string-literal arg and count the subsequent top-level forms.
function parseDmFmt(text, openIdx) {
  // skip "(dm/fmt"
  let i = openIdx;
  // advance past "(dm/fmt"
  i += "(dm/fmt".length;
  // skip whitespace
  while (i < text.length && /\s/.test(text[i])) i++;
  // expect a string literal (possibly with leading "
  if (text[i] !== '"') return null;
  // parse string literal (handle \" and \\)
  let str = "";
  i++; // opening quote
  while (i < text.length && text[i] !== '"') {
    if (text[i] === "\\") { str += text[i + 1]; i += 2; continue; }
    str += text[i]; i++;
  }
  i++; // closing quote
  // now count top-level forms until matching close paren of the dm/fmt call
  let depth = 1; // we're inside the dm/fmt call
  let argCount = 0;
  while (i < text.length && depth > 0) {
    const c = text[i];
    if (/\s/.test(c)) { i++; continue; }
    if (c === ")") { depth--; i++; continue; }
    if (c === "(") { depth++; i++; continue; }
    if (c === "[") { depth++; i++; continue; }
    if (c === "{") { depth++; i++; continue; }
    if (c === "]" || c === "}") { depth--; i++; continue; }
    if (c === ";") {
      // line comment
      while (i < text.length && text[i] !== "\n") i++;
      continue;
    }
    // a token starts here -> one arg form begins; skip to end of this form
    argCount++;
    // skip until we hit a whitespace/paren/bracket/brace at depth 1
    let localDepth = 0;
    while (i < text.length) {
      const cc = text[i];
      if (cc === "(" || cc === "[" || cc === "{") { localDepth++; i++; continue; }
      if (cc === ")" || cc === "]" || cc === "}") {
        if (localDepth === 0) { break; }
        localDepth--; i++; continue;
      }
      if (localDepth === 0 && /\s/.test(cc)) break;
      if (cc === ";") { while (i < text.length && text[i] !== "\n") i++; continue; }
      i++;
    }
  }
  return { str, argCount };
}

const bad = [];
for (const f of walk(ROOT)) {
  const text = fs.readFileSync(f, "utf8");
  // find "(dm/fmt" occurrences
  let idx = 0;
  while ((idx = text.indexOf("(dm/fmt", idx)) !== -1) {
    const r = parseDmFmt(text, idx);
    if (r) {
      const ph = countPlaceholders(r.str);
      if (ph !== r.argCount) {
        const line = text.slice(0, idx).split("\n").length;
        bad.push({ file: path.relative(ROOT, f).split(path.sep).join("/"), line, ph, args: r.argCount });
      }
    }
    idx += "(dm/fmt".length;
  }
}
if (bad.length === 0) console.log("OK: all dm/fmt placeholder/arg counts match");
else {
  console.log("MISMATCHES (" + bad.length + "):");
  for (const b of bad) console.log(`  ${b.file}:${b.line}  placeholders=${b.ph}  args=${b.args}`);
}