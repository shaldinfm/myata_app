/*
 * Finalise the typography migration: parity, baselines, report — one command.
 *
 *   node tools/figma-export/typography-migration/finalize.mjs <dir-with-4-exports>
 *   node tools/figma-export/typography-migration/finalize.mjs --check   # verify the pipeline only
 *
 * Expects the four post-migration exports in one directory. Names are matched
 * loosely, so whatever the snapshot plugin called them is fine as long as each
 * contains its page name:
 *
 *   …CURRENT ANDROID UI - LIGHT…      …CURRENT ANDROID UI — DARK…
 *   …3.6.6 PROPOSALS - LIGHT…         …3.6.6 PROPOSALS - DARK…
 *
 * For each page it runs the parity validator against the committed
 * pre-migration snapshot, then regenerates the normalized baseline in place.
 * It refuses to write any baseline unless every page passes parity, so a failed
 * migration cannot quietly become the new reference.
 */
import fs from "node:fs";
import path from "node:path";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.join(here, "..", "..", "..");
const rel = (p) => path.relative(repo, p).replace(/\\/g, "/");

// before (committed) -> baseline it normalises to. Verified: each `before` file
// reproduces its committed baseline byte-for-byte once line endings are ignored.
const PAGES = [
  { page: "CURRENT ANDROID UI - LIGHT", match: /current android ui.*light/i,
    before: "tools/figma-export/canonical/figma-canonical-light-final.json",
    baseline: "tools/figma-export/canonical/figma-canonical-light-normalized.json" },
  { page: "CURRENT ANDROID UI — DARK", match: /current android ui.*dark/i,
    before: "tools/figma-export/canonical/figma-canonical-dark-final.json",
    baseline: "tools/figma-export/canonical/figma-canonical-dark-normalized.json" },
  { page: "3.6.6 PROPOSALS - LIGHT", match: /proposals.*light/i,
    before: "tools/figma-export/screens-3.6.6/snapshots/proposals-light.json",
    baseline: "tools/figma-export/screens-3.6.6/baselines/proposals-light-normalized.json" },
  { page: "3.6.6 PROPOSALS - DARK", match: /proposals.*dark/i,
    before: "tools/figma-export/screens-3.6.6/snapshots/proposals-dark.json",
    baseline: "tools/figma-export/screens-3.6.6/baselines/proposals-dark-normalized.json" },
];

const NORMALIZER = path.join(repo, "tools/figma-export/canonical/normalize-snapshot.mjs");
const PARITY = path.join(here, "verify-parity.mjs");
const CONTRACT = path.join(here, "verify-contract.mjs");

const stripCr = (s) => s.replace(/\r/g, "");

/* --- --check: prove the pipeline still reproduces every committed baseline -- */
if (process.argv.includes("--check")) {
  let ok = true;
  const tmp = path.join(here, ".finalize-check.json");
  for (const p of PAGES) {
    const before = path.join(repo, p.before), baseline = path.join(repo, p.baseline);
    if (!fs.existsSync(before) || !fs.existsSync(baseline)) { console.log(`  ${p.page}: source or baseline missing`); ok = false; continue; }
    execFileSync(process.execPath, [NORMALIZER, before, tmp], { stdio: "ignore" });
    const same = stripCr(fs.readFileSync(tmp, "utf8")) === stripCr(fs.readFileSync(baseline, "utf8"));
    console.log(`  ${p.page.padEnd(28)} ${same ? "baseline reproduces exactly" : "BASELINE DOES NOT REPRODUCE"}`);
    if (!same) ok = false;
  }
  fs.existsSync(tmp) && fs.unlinkSync(tmp);
  console.log(ok ? "\npipeline OK" : "\npipeline BROKEN");
  process.exit(ok ? 0 : 1);
}

const dir = process.argv[2];
if (!dir) {
  console.error("usage: finalize.mjs <dir-with-4-exports>   |   finalize.mjs --check");
  process.exit(1);
}
if (!fs.existsSync(dir)) { console.error(`no such directory: ${dir}`); process.exit(1); }

const files = fs.readdirSync(dir).filter((f) => f.toLowerCase().endsWith(".json"));
const resolved = [];
for (const p of PAGES) {
  const hit = files.find((f) => p.match.test(f) || p.match.test(stripCr(fs.readFileSync(path.join(dir, f), "utf8")).slice(0, 400)));
  if (!hit) { console.error(`missing export for: ${p.page}`); process.exit(1); }
  resolved.push({ ...p, after: path.join(dir, hit) });
}

/* --- 1. parity ----------------------------------------------------------- */
console.log("=".repeat(72));
console.log("PARITY");
console.log("=".repeat(72));
let allPass = true;
const results = [];
for (const p of resolved) {
  console.log(`\n--- ${p.page} ---`);
  let out = "", pass = true;
  try {
    out = execFileSync(process.execPath, [PARITY, path.join(repo, p.before), p.after], { encoding: "utf8" });
  } catch (e) {
    out = (e.stdout || "") + (e.stderr || "");
    pass = false;
  }
  console.log(out.trim());
  results.push({ page: p.page, pass, out });
  if (!pass) allPass = false;
}

if (!allPass) {
  console.log("\n" + "=".repeat(72));
  console.log("PARITY FAILED - no baseline was regenerated.");
  console.log("Fix the migration (or revert to the (pre-typography) pages) and re-export.");
  process.exit(1);
}

/* --- 1b. contract conformance ------------------------------------------- */
// Parity proves nothing changed that should not have. This proves everything
// that should have changed is correct - a button that came out at 19px instead
// of 22px passes parity, because "size changed" is an allowed category, and only
// this check knows what the size was supposed to be.
console.log("\n" + "=".repeat(72));
console.log("CONTRACT");
console.log("=".repeat(72));
let contractPass = true;
try {
  console.log(execFileSync(process.execPath, [CONTRACT, ...resolved.map((p) => p.after)], { encoding: "utf8" }).trim());
} catch (e) {
  console.log(((e.stdout || "") + (e.stderr || "")).trim());
  contractPass = false;
}
if (!contractPass) {
  console.log("\n" + "=".repeat(72));
  console.log("CONTRACT FAILED - no baseline was regenerated.");
  console.log("Re-run the migration on the affected pages and re-export.");
  process.exit(1);
}

/* --- 2. baselines -------------------------------------------------------- */
console.log("\n" + "=".repeat(72));
console.log("BASELINES");
console.log("=".repeat(72));
for (const p of resolved) {
  const dest = path.join(repo, p.baseline);
  execFileSync(process.execPath, [NORMALIZER, p.after, dest], { stdio: "inherit" });
  console.log(`  ${rel(dest)}  (${(fs.statSync(dest).size / 1024).toFixed(0)} KB)`);
}

/* --- 3. summary ---------------------------------------------------------- */
console.log("\n" + "=".repeat(72));
console.log("SUMMARY");
console.log("=".repeat(72));
for (const r of results) {
  const t = /typography changes\s+:\s+(\d+)/.exec(r.out);
  const g = /geometry changes\s+:\s+(\d+)/.exec(r.out);
  console.log(`  ${r.page.padEnd(28)} PASS   typography ${t ? t[1] : "?"}   geometry ${g ? g[1] : "?"}`);
}
console.log("\nPARITY PASS on all four pages. Baselines regenerated.");
