/*
 * Figma plugins load a single main file and cannot import JSON, so spec.json is
 * inlined into code.js. spec.json stays the reviewable artefact; code.js is
 * generated and committed so the plugin runs without a build step.
 *
 *   node tools/figma-export/screens-3.6.6/create-plugin/build-plugin.mjs
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const spec = JSON.parse(fs.readFileSync(path.join(here, "..", "spec.json"), "utf8"));
const template = fs.readFileSync(path.join(here, "code.template.js"), "utf8");

const banner =
  "// GENERATED FILE - do not edit.\n" +
  "// Source: code.template.js + ../spec.json\n" +
  "// Rebuild: node tools/figma-export/screens-3.6.6/create-plugin/build-plugin.mjs\n\n" +
  "var SCREEN_SPEC = " + JSON.stringify(spec) + ";\n\n";

fs.writeFileSync(path.join(here, "code.js"), banner + template);

let nodes = 0;
(function walk(ns) { for (const n of ns) { nodes++; walk(n.ch || []); } })(spec.screens.flatMap((s) => s.nodes));
console.log(`code.js written. ${spec.screens.length} screens, ${spec.screens.length * 2} frames, ${nodes} nodes per theme.`);
