/*
 * Finalise the typography migration: parity, baselines, report — one command.
 *
 *   node tools/figma-export/typography-migration/finalize.mjs <dir-with-4-exports>
 *   node tools/figma-export/typography-migration/finalize.mjs --check   # verify the pipeline only
 *
 * Expects the four post-migration exports in one directory. Filenames do not
 * matter: each file is matched by the page id recorded inside it, so an export
 * taken from a "(pre-typography)" clone can never be mistaken for the original.
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
  { page: "CURRENT ANDROID UI - LIGHT", pageId: "2388:366", match: /current android ui.*light/i,
    expect: { Onest: 69, Montserrat: 22 },
    before: "tools/figma-export/canonical/figma-canonical-light-final.json",
    baseline: "tools/figma-export/canonical/figma-canonical-light-normalized.json" },
  { page: "CURRENT ANDROID UI — DARK", pageId: "2436:531", match: /current android ui.*dark/i,
    expect: { Onest: 130, Montserrat: 29 },
    before: "tools/figma-export/canonical/figma-canonical-dark-final.json",
    baseline: "tools/figma-export/canonical/figma-canonical-dark-normalized.json" },
  { page: "3.6.6 PROPOSALS - LIGHT", pageId: "2517:1936", match: /proposals.*light/i,
    expect: { Onest: 243, Montserrat: 37 },
    before: "tools/figma-export/screens-3.6.6/snapshots/proposals-light.json",
    baseline: "tools/figma-export/screens-3.6.6/baselines/proposals-light-normalized.json" },
  { page: "3.6.6 PROPOSALS - DARK", pageId: "2517:2903", match: /proposals.*dark/i,
    expect: { Onest: 243, Montserrat: 37 },
    before: "tools/figma-export/screens-3.6.6/snapshots/proposals-dark.json",
    baseline: "tools/figma-export/screens-3.6.6/baselines/proposals-dark-normalized.json" },
];

const NORMALIZER = path.join(repo, "tools/figma-export/canonical/normalize-snapshot.mjs");
const PARITY = path.join(here, "verify-parity.mjs");
const CONTRACT = path.join(here, "verify-contract.mjs");

const stripCr = (s) => s.replace(/\r/g, "");

/*
 * --check: assert the committed baselines still describe the migrated design.
 *
 * This used to re-normalise the pre-migration snapshots and require them to
 * reproduce the baselines. That premise died the moment the migration landed -
 * the baselines are post-migration now, so the old check would report a broken
 * pipeline forever and teach everyone to ignore it.
 *
 * What is still worth asserting is the invariant the migration established: the
 * baselines carry no Muller, and each page holds exactly the family counts the
 * approved contract produces.
 */
if (process.argv.includes("--check")) {
  let ok = true;
  for (const p of PAGES) {
    const baseline = path.join(repo, p.baseline);
    if (!fs.existsSync(baseline)) { console.log(`  ${p.page.padEnd(28)} baseline missing`); ok = false; continue; }
    const doc = JSON.parse(stripCr(fs.readFileSync(baseline, "utf8")));
    const fam = {};
    for (const fr of doc.frames || []) {
      (function walk(n) {
        if (n.type === "TEXT" && n.text && n.text.font) {
          const f = String(n.text.font).split("/")[0];
          fam[f] = (fam[f] || 0) + 1;
        }
        (n.children || []).forEach(walk);
      })(fr);
    }
    const muller = Object.keys(fam).filter((f) => /^muller/i.test(f)).reduce((n, f) => n + fam[f], 0);
    const want = p.expect || {};
    const mismatch = Object.keys(want).filter((f) => (fam[f] || 0) !== want[f]);
    const good = muller === 0 && !mismatch.length;
    if (!good) ok = false;
    console.log(`  ${p.page.padEnd(28)} ${good ? "OK" : "MISMATCH"}  ` +
      Object.entries(fam).map(([k, v]) => `${k} ${v}`).join(" / ") +
      (muller ? `   << ${muller} Muller remaining` : "") +
      (mismatch.length ? `   << expected ${mismatch.map((f) => f + " " + want[f]).join(", ")}` : ""));
  }
  console.log(ok ? "\nbaselines describe the migrated design" : "\nBASELINES DO NOT MATCH THE CONTRACT");
  process.exit(ok ? 0 : 1);
}

const dir = process.argv[2];
if (!dir) {
  console.error("usage: finalize.mjs <dir-with-4-exports>   |   finalize.mjs --check");
  process.exit(1);
}
if (!fs.existsSync(dir)) { console.error(`no such directory: ${dir}`); process.exit(1); }

/*
 * Export files are matched by the page id recorded inside them, not by filename.
 *
 * Filenames lie. The first round of exports was taken from the "(pre-typography)"
 * clones, and their names looked exactly right - the clones only revealed
 * themselves inside the JSON, by page name and by a wholesale change of node
 * ids. Matching on the original page id makes picking a clone impossible, and a
 * stale clone left in the directory is reported and skipped rather than silently
 * preferred.
 */
const files = fs.readdirSync(dir).filter((f) => f.toLowerCase().endsWith(".json"));
const meta = [];
for (const f of files) {
  try {
    const j = JSON.parse(stripCr(fs.readFileSync(path.join(dir, f), "utf8")));
    if (j && j.source && j.source.pageId) meta.push({ file: f, pageId: j.source.pageId, pageName: j.source.pageName || "?" });
  } catch (e) { /* not a snapshot */ }
}

const rejected = meta.filter((m) => /\(pre-typography\)/i.test(m.pageName));
if (rejected.length) {
  console.log("Ignoring stale exports taken from clone pages:");
  rejected.forEach((m) => console.log(`  ${m.file}   [${m.pageName}]`));
  console.log("");
}

const resolved = [];
for (const p of PAGES) {
  const hit = meta.find((m) => m.pageId === p.pageId && !/\(pre-typography\)/i.test(m.pageName));
  if (!hit) {
    console.error(`missing export for: ${p.page}  (expected a snapshot with source.pageId ${p.pageId})`);
    const near = meta.filter((m) => p.match.test(m.pageName));
    if (near.length) {
      console.error("  candidates found, but none matched that page id:");
      near.forEach((m) => console.error(`    ${m.file}   pageId ${m.pageId}   [${m.pageName}]`));
    }
    process.exit(1);
  }
  resolved.push({ ...p, after: path.join(dir, hit.file), exportedPage: hit.pageName });
}
console.log("Exports resolved by page id:");
resolved.forEach((r) => console.log(`  ${r.page.padEnd(28)} ${path.basename(r.after)}   [${r.exportedPage}]`));
console.log("");

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
