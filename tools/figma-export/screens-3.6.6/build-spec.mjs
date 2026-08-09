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

// Expand declared families (avatar/m3 -> avatar/m3-01 … -16) so every slot the
// plugin will create has its own entry the owner can point at a node.
for (const [base, fam] of Object.entries(ASSETS.families || {})) {
  for (let i = 1; i <= fam.count; i++) {
    const key = `${base}-${String(i).padStart(fam.pad || 2, "0")}`;
    if (ASSETS.assets[key]) continue;
    ASSETS.assets[key] = {
      status: fam.status, darkNodeId: null, lightNodeId: null, source: null,
      nativeSize: fam.nativeSize, note: fam.note, family: base
    };
  }
}

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
  // Inside an auto-layout parent, x/y are computed by Figma and heights hug, so
  // absolute containment is not a meaningful check.
  if (parent && !parent.al) {
    if (node.x < -1) errors.push(`${where}: x=${node.x} is outside its parent`);
    if (node.y < -1) errors.push(`${where}: y=${node.y} is outside its parent`);
    if (node.x + node.w > parent.w + 1) errors.push(`${where}: right edge ${node.x + node.w} exceeds parent width ${parent.w}`);
    if (node.y + node.h > parent.h + 1 && !parent.hugsChild) errors.push(`${where}: bottom edge ${node.y + node.h} exceeds parent height ${parent.h}`);
  }
  if (node.al) {
    // A horizontal auto-layout must not be over-subscribed at its nominal widths.
    if (node.al.mode === "HORIZONTAL") {
      const kids = node.ch || [];
      const pad = node.al.pad || [0, 0, 0, 0];
      const used = pad[3] + pad[1] + (kids.length - 1) * (node.al.gap || 0) + kids.reduce((a, c) => a + c.w, 0);
      if (used > node.w + 1) errors.push(`${where}: horizontal auto-layout needs ${used} but the frame is ${node.w}`);
    }
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
  figmaPages: { dark: "3.6.6 PROPOSALS - DARK", light: "3.6.6 PROPOSALS - LIGHT" },
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
const rolled = {};
for (const key of Object.keys(assetUse)) {
  const a = ASSETS.assets[key];
  const label = a.family ? a.family + "-* (" + ASSETS.families[a.family].count + " slots)" : key;
  rolled[label] = rolled[label] || { status: a.status, uses: 0 };
  rolled[label].uses += assetUse[key].length;
}
for (const [label, r] of Object.entries(rolled))
  console.log(`  ${r.status === "PENDING_OWNER" ? "NEEDS ASSET" : r.status === "CANONICAL_NODE_APPROXIMATE" ? "CONFIRM    " : "ok         "} ${label.padEnd(28)} ${r.uses} use(s)`);

const blocked = Object.entries(rolled).filter(([, r]) => r.status === "PENDING_OWNER").map(([l]) => l);
if (blocked.length) console.log(`\nowner must supply: ${blocked.join(", ")}`);
