/*
 * Compare the before and after TV captures.
 *
 *   node tools/qa/tv/compare-tv-baseline.mjs
 *
 * Structure is compared strictly: the focus chain, and every node's id, class
 * and bounds at every step. That is what an unintended theme change would move.
 *
 * Screenshots are compared byte-wise for information only. The player screens
 * show live radio, so their pixels legitimately differ between two runs - a
 * screenshot difference there is not evidence of a regression, and a screenshot
 * difference on splash or selection is.
 */
import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const load = (r) => JSON.parse(fs.readFileSync(path.join(here, r, "metadata.json"), "utf8"));

let A, B;
try { A = load("before"); B = load("after"); }
catch (e) { console.error("missing capture: " + e.message); process.exit(1); }

const problems = [];
const notes = [];

console.log("=== environment ===");
for (const k of ["ro.product.model", "ro.product.device", "ro.build.characteristics", "ro.build.version.sdk", "size", "density", "emulator"]) {
  const a = A.env[k], b = B.env[k];
  console.log(`  ${k.padEnd(28)} ${a}${a === b ? "" : `   AFTER: ${b}`}`);
  if (a !== b) problems.push(`environment differs on ${k}: "${a}" vs "${b}" — the runs are not comparable`);
}
console.log(`  app commit                   before ${A.env.appCommit}\n                               after  ${B.env.appCommit}`);

console.log("\n=== focus chain ===");
const fa = A.steps.map((s) => s.focused || "-");
const fb = B.steps.map((s) => s.focused || "-");
console.log(`  before: ${fa.join(" > ")}`);
console.log(`  after : ${fb.join(" > ")}`);
if (fa.join("|") !== fb.join("|")) problems.push("focus chain differs — navigation or focus order changed");

console.log("\n=== per-step structure ===");
for (let i = 0; i < Math.max(A.steps.length, B.steps.length); i++) {
  let a = A.steps[i], b = B.steps[i];
  if (!a || !b) { problems.push(`step ${i + 1} missing in one run`); continue; }
  if (a.name !== b.name) { problems.push(`step ${i + 1} name differs`); continue; }

  // btn_back fades itself out 4s after the last key press (TvPlayerFragment
  // hideRunnable), and uiautomator omits a fully transparent view. Whether it
  // appears in a dump is therefore a function of elapsed milliseconds, not of
  // the theme, so it cannot be part of a structural gate. Its geometry is still
  // compared whenever it is present in both runs.
  const TRANSIENT = new Set(["btn_back"]);
  const key = (n) => `${n.id}|${n.cls}|${n.bounds}|${n.focused ? "F" : ""}${n.selected ? "S" : ""}`;
  const present = (nodes, id) => nodes.some((n) => n.id === id);
  const drop = (nodes) => nodes.filter((n) => !(TRANSIENT.has(n.id) && !(present(a.nodes, n.id) && present(b.nodes, n.id))));
  a = { ...a, nodes: drop(a.nodes) };
  b = { ...b, nodes: drop(b.nodes) };

  const sa = a.nodes.map(key), sb = b.nodes.map(key);
  const onlyA = sa.filter((x) => !sb.includes(x));
  const onlyB = sb.filter((x) => !sa.includes(x));

  const same = onlyA.length === 0 && onlyB.length === 0;
  console.log(`  ${same ? "OK  " : "DIFF"} ${String(i + 1).padStart(2)} ${a.name.padEnd(34)} nodes ${a.nodes.length}/${b.nodes.length}`);
  if (!same) {
    problems.push(`step ${i + 1} "${a.name}": ${onlyA.length} node(s) only before, ${onlyB.length} only after`);
    onlyA.slice(0, 4).forEach((x) => console.log(`         before only: ${x}`));
    onlyB.slice(0, 4).forEach((x) => console.log(`         after  only: ${x}`));
  }
}

console.log("\n=== screenshots (informational) ===");
// live radio metadata/artwork, and the 2s splash transient, legitimately differ
const LIVE = /player|splash-transient/i;
for (const f of fs.readdirSync(path.join(here, "before")).filter((x) => x.endsWith(".png"))) {
  const pa = path.join(here, "before", f), pb = path.join(here, "after", f);
  if (!fs.existsSync(pb)) { problems.push(`screenshot missing after: ${f}`); continue; }
  const ha = crypto.createHash("sha256").update(fs.readFileSync(pa)).digest("hex");
  const hb = crypto.createHash("sha256").update(fs.readFileSync(pb)).digest("hex");
  const live = LIVE.test(f);
  const mark = ha === hb ? "identical" : live ? "differs (live content — review by eye)" : "DIFFERS";
  console.log(`  ${ha === hb ? "==" : "!="} ${f.padEnd(40)} ${mark}`);
  if (ha !== hb && !live) notes.push(`static screen ${f} differs — inspect before accepting`);
}

console.log("\n=== verdict ===");
if (problems.length) {
  console.log(`  REGRESSION: ${problems.length} structural problem(s)`);
  problems.forEach((p) => console.log(`    - ${p}`));
} else {
  console.log("  structure identical: focus chain, node ids, classes and bounds all match");
}
if (notes.length) {
  console.log(`\n  ${notes.length} screenshot note(s) needing an eye:`);
  notes.forEach((n) => console.log(`    - ${n}`));
}
process.exitCode = problems.length ? 1 : 0;
