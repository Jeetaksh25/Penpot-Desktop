// Bracket-balance checker for Clojure/CLJS files.
// Tracks () [] {} ignoring those inside strings, char literals (\x), and
// ; line comments. Prints per-file balance; non-zero = problem.
const fs = require('fs');
const files = process.argv.slice(2);
let bad = false;
for (const f of files) {
  const src = fs.readFileSync(f, 'utf8');
  let i = 0, n = src.length;
  let par = 0, sq = 0, br = 0;          // () [] {}
  let inStr = false, strCh = '', inChar = false, inComment = false;
  while (i < n) {
    const c = src[i], c2 = src[i+1];
    if (inComment) {
      if (c === '\n') inComment = false;
      i++; continue;
    }
    if (inChar) { inChar = false; i++; continue; }
    if (inStr) {
      if (c === '\\') { i += 2; continue; }
      if (c === strCh) { inStr = false; i++; continue; }
      i++; continue;
    }
    if (c === ';') { inComment = true; i++; continue; }
    if (c === '\\') { inChar = true; i++; continue; }
    if (c === '"') { inStr = true; strCh = '"'; i++; continue; }
    if (c === '(') par++;
    else if (c === ')') par--;
    else if (c === '[') sq++;
    else if (c === ']') sq--;
    else if (c === '{') br++;
    else if (c === '}') br--;
    i++;
  }
  const ok = (par === 0 && sq === 0 && br === 0);
  if (!ok) bad = true;
  console.log(`${ok ? 'OK ' : 'BAD'}  ()=${par} []=${sq} {}=${br}  ${f}`);
}
process.exit(bad ? 1 : 0);