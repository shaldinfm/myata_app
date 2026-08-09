/*
 * Emits spec.json - the single reviewable artefact that both the HTML preview
 * and the Figma plugin read. Colours stay as token names in the spec; each
 * consumer resolves them per theme, so the preview and the Figma frames cannot
 * drift apart the way two hand-built copies would.
 *
 *   node tools/figma-export/screens-3.6.6/build-spec.mjs
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { TOKENS, TYPE } from "./spec/tokens.mjs";
import { SCREENS } from "./spec/screens.mjs";

const here = path.dirname(fileURLToPath(import.meta.url));
const ASSETS = JSON.parse(fs.readFileSync(path.join(here, "spec", "assets.json"), "utf8"));

/* Fail loudly rather than silently shipping a screen with a bad colour or a
 * node that falls outside its parent. */
const errors = [];
const assetUse = {};
function check(node, parent, screen, trail) {
  const where = `${screen.id} :: ${trail}`;
  if (node.t === "ASSET") {
    if (!ASSETS.assets[node.key]) errors.push(`${where}: unknown asset key '${node.key}' - add it to spec/assets.json`);
    else (assetUse[node.key] = assetUse[node.key] || []).push(screen.id);
    if (node.tint && !TOKENS[node.tint]) errors.push(`${where}: unknown tint token '${node.tint}'`);
  }
  for (const key of ["fill", "stroke"]) {
    const v = node[key];
    if (v == null) continue;
    if (typeof v === "string" && v.charAt(0) === "#") { errors.push(`${where}: raw hex ${v} in ${key} - use a token`); continue; }
    if (!TOKENS[v]) errors.push(`${where}: unknown token '${v}' in ${key}`);
  }
  if (node.w == null || node.h == null) errors.push(`${where}: missing width/height`);
  if (parent) {
    if (node.x < -1) errors.push(`${where}: x=${node.x} is outside its parent`);
    if (node.y < -1) errors.push(`${where}: y=${node.y} is outside its parent`);
    if (node.x + node.w > parent.w + 1) errors.push(`${where}: right edge ${node.x + node.w} exceeds parent width ${parent.w}`);
    if (node.y + node.h > parent.h + 1) errors.push(`${where}: bottom edge ${node.y + node.h} exceeds parent height ${parent.h}`);
  }
  (node.ch || []).forEach((c) => check(c, node, screen, trail + " > " + c.n));
}

for (const s of SCREENS) {
  const root = { w: s.w, h: s.h };
  s.nodes.forEach((n) => check(n, root, s, n.n));
}

const ids = SCREENS.map((s) => s.id);
ids.forEach((id, i) => { if (ids.indexOf(id) !== i) errors.push(`duplicate screen id: ${id}`); });

if (errors.length) {
  console.error("spec is invalid:\n  " + errors.join("\n  "));
  process.exit(1);
}

const spec = {
  schemaVersion: "1.0.0",
  status: "PROPOSAL - awaiting visual approval. Not implemented in Android.",
  generatedBy: "tools/figma-export/screens-3.6.6/build-spec.mjs",
  derivedFrom: {
    canonical: "tools/figma-export/canonical/figma-canonical-{dark,light}-normalized.json",
    tokens: "tools/figma-export/canonical/semantic-tokens.json",
    note: "Every metric is measured from the canonical pages. Where a primitive had no " +
          "canonical equivalent, primitives.mjs records what it was derived from."
  },
  figmaPages: { dark: "3.6.6 PROPOSALS — DARK", light: "3.6.6 PROPOSALS - LIGHT" },
  tokens: TOKENS,
  type: TYPE,
  assets: ASSETS.assets,
  screens: SCREENS.map((s) => ({ id: s.id, group: s.group, title: s.title, w: s.w, h: s.h, notes: s.notes, nodes: s.nodes }))
};

fs.writeFileSync(path.join(here, "spec.json"), JSON.stringify(spec, null, 2) + "\n");

const byGroup = {};
for (const s of SCREENS) (byGroup[s.group] = byGroup[s.group] || []).push(s.id);
let count = 0;
(function walk(ns) { for (const n of ns) { count++; walk(n.ch || []); } })(SCREENS.flatMap((s) => s.nodes));

console.log(`spec.json written: ${SCREENS.length} screens x 2 themes = ${SCREENS.length * 2} frames, ${count} nodes per theme.`);
for (const g of Object.keys(byGroup)) console.log(`  ${g}: ${byGroup[g].length} - ${byGroup[g].join(", ")}`);

console.log("\nassets referenced:");
for (const key of Object.keys(assetUse)) {
  const a = ASSETS.assets[key];
  const n = assetUse[key].length;
  console.log(`  ${a.status === "PENDING_OWNER" ? "BLOCKED " : a.status === "CANONICAL_NODE_APPROXIMATE" ? "CONFIRM " : "ok      "} ${key.padEnd(22)} ${n} use${n > 1 ? "s" : " "}  ${a.status}`);
}
const blocked = Object.keys(assetUse).filter((k) => ASSETS.assets[k].status === "PENDING_OWNER");
if (blocked.length) console.log(`\n${blocked.length} asset(s) need owner-supplied Figma nodes: ${blocked.join(", ")}`);
