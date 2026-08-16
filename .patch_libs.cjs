const fs = require("fs");
const file = "penpot-source/frontend/resources/public/js/libs.js";
let text = fs.readFileSync(file, "utf8");

const target = "function cG(e,t){throw t.$$typeof===Ilr?Error(cr(525)):";
const idx = text.indexOf(target);
if (idx === -1) { console.error("TARGET NOT FOUND"); process.exit(1); }

// NOTE: the injected try/catch must NOT close the function body — the
// original `throw ...` and the function's closing `}` follow after it.
const inject =
  'function cG(e,t){try{console.error("INVALID_CHILD_OBJ",t);console.error("INVALID_CHILD_FIBER",e&&e.type&&(e.type.name||e.type.displayName||String(e.type)),"tag="+e.tag);var f_=e&&e.return,n_=[];while(f_){n_.push(f_.type&&(f_.type.name||f_.type.displayName||String(f_.type).slice(0,100)));f_=f_.return}console.error("INVALID_CHILD_CHAIN",n_);console.error("INVALID_CHILD_STACK",new Error().stack)}catch(_){}throw t.$$typeof===Ilr?Error(cr(525)):';

text = text.replace(target, inject);
fs.writeFileSync(file, text);

// sanity check: the function should parse
const i = text.indexOf("function cG(e,t){try{");
const end = text.indexOf("function GGe", i);
const fn = text.slice(i, end);
try { new Function(fn); console.log("PATCH OK, parse verified"); }
catch (e) { console.error("PARSE FAIL:", e.message); process.exit(1); }
