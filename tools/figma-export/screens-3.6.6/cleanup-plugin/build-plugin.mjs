/*
 * Figma plugins load a single main file and cannot import JSON, so the plan is
 * inlined into code.js. CLEANUP-PLAN.json stays the reviewable artefact.
 *
 *   node tools/figma-export/screens-3.6.6/cleanup-plugin/build-plugin.mjs
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const plan = JSON.parse(fs.readFileSync(path.join(here, "..", "CLEANUP-PLAN.json"), "utf8"));
const template = fs.readFileSync(path.join(here, "code.template.js"), "utf8");

const banner =
  "// GENERATED FILE - do not edit.\n" +
  "// Source: code.template.js + ../CLEANUP-PLAN.json\n" +
  "// Rebuild: node tools/figma-export/screens-3.6.6/cleanup-plugin/build-plugin.mjs\n\n" +
  "var CLEANUP_PLAN = " + JSON.stringify(plan) + ";\n\n";

fs.writeFileSync(path.join(here, "code.js"), banner + template);

const byGroup = {};
for (const m of plan.mutations) byGroup[m.group] = (byGroup[m.group] || 0) + 1;
console.log(`code.js written. ${plan.mutations.length} mutations:`, JSON.stringify(byGroup));
