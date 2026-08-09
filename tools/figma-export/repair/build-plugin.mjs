/*
 * Figma plugins load a single main file and cannot import JSON, so the plan is
 * inlined into code.js. repair-plan.json stays the reviewable artefact; code.js
 * is generated and committed so the plugin runs without a build step.
 *
 *   node tools/figma-export/repair/build-plugin.mjs
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const plan = JSON.parse(fs.readFileSync(path.join(here, "repair-plan.json"), "utf8"));
const template = fs.readFileSync(path.join(here, "code.template.js"), "utf8");

const banner =
  "// GENERATED FILE - do not edit.\n" +
  "// Source: code.template.js + repair-plan.json\n" +
  "// Rebuild: node tools/figma-export/repair/build-plugin.mjs\n\n" +
  "var REPAIR_PLAN = " + JSON.stringify(plan, null, 2) + ";\n\n";

fs.writeFileSync(path.join(here, "code.js"), banner + template);

const counts = {
  mutations: plan.mutations.length,
  bindings: plan.tokenBindings.length,
  boundNodes: plan.tokenBindings.length * 2,
  variables: Object.keys(plan.tokens).length,
  textStyles: plan.textStyles.length
};
console.log("code.js written.", JSON.stringify(counts));
