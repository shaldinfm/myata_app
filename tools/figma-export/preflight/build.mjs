/*
 * Generates preflight/code.js from main.tmpl.js by splicing in the
 * frozen classifier from the trial plugin.
 *
 *   node tools/figma-export/preflight/build.mjs [--check]
 *
 * The preflight must check against exactly the approved contract, so the rule block
 * is never hand-copied. It is lifted verbatim from font-trial/code.js at build
 * time, and --check fails if the committed code.js has drifted from that source.
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const TRIAL = path.join(here, "..", "font-trial", "code.js");
const TMPL = path.join(here, "main.tmpl.js");
const OUT = path.join(here, "code.js");

const trial = fs.readFileSync(TRIAL, "utf8");

// The classifier runs from "var MONTSERRAT" to the end of classify().
const start = trial.indexOf("var MONTSERRAT =");
const marker = "if (typeof globalThis !== \"undefined\") globalThis.__fontTrialClassify = classify;";
const end = trial.indexOf(marker);
if (start < 0 || end < 0) {
  console.error("could not locate the classifier block in font-trial/code.js");
  process.exit(1);
}
const rules = trial.slice(start, end).trimEnd();

const banner = `/*
 * ---------------------------------------------------------------------------
 * GENERATED - do not edit here.
 *
 * Lifted verbatim from tools/figma-export/font-trial/code.js by build.mjs, so
 * the migration can only ever apply the classifier that was approved visually.
 * Change the rules there and re-run the build.
 * ---------------------------------------------------------------------------
 */
`;

const generated = fs.readFileSync(TMPL, "utf8").replace("/*__RULES__*/", banner + rules);

if (process.argv.includes("--check")) {
  const current = fs.existsSync(OUT) ? fs.readFileSync(OUT, "utf8") : "";
  if (current !== generated) {
    console.error("code.js is stale - re-run: node tools/figma-export/preflight/build.mjs");
    process.exit(1);
  }
  const ruleCount = (rules.match(/role:\s*"/g) || []).length;
  console.log(`code.js is in sync with the frozen classifier (${ruleCount} rules)`);
  process.exit(0);
}

fs.writeFileSync(OUT, generated);
const ruleCount = (rules.match(/role:\s*"/g) || []).length;
console.log(`wrote ${path.relative(process.cwd(), OUT)}  (${ruleCount} rules spliced from the trial classifier)`);
