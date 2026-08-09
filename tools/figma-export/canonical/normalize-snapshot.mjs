/*
 * Reduce a ~20 MB raw canonical snapshot to a compact, human-diffable baseline
 * that is worth committing.
 *
 *   node tools/figma-export/canonical/normalize-snapshot.mjs <raw.json> <out.json>
 *
 * What is kept: everything a structural or design drift check needs - identity,
 * geometry, layout, corners, visibility, the colours that matter, and full text
 * properties.
 *
 * What is dropped, and why:
 *   - VECTOR internals. 5,800 of the ~8,600 nodes are vector paths inside the
 *     stream-banner artwork. They are the bulk of the file, they only ever differ
 *     by sub-pixel float noise, and that noise is explicitly not a defect. A
 *     vector subtree collapses to one summary line with a node count and a
 *     rounded bounding box, so a real change (an artwork swap) still shows up.
 *   - Image metadata beyond the fact that an image fill exists.
 *   - Gradient stop arrays, transforms, effect geometry.
 *   - Anything undefined or at its default.
 *
 * Coordinates are rounded to 0.5 px so HUG re-measurement noise cannot create a
 * spurious diff. Object keys are emitted in a fixed order and children keep
 * document order, so two runs of the same document produce byte-identical output.
 */
import fs from "node:fs";
import path from "node:path";

const [, , inPath, outPath] = process.argv;
if (!inPath || !outPath) {
  console.error("usage: node normalize-snapshot.mjs <raw.json> <out.json>");
  process.exit(1);
}

const round = (n) => (typeof n === "number" ? Math.round(n * 2) / 2 : n);
const VECTORISH = new Set(["VECTOR", "BOOLEAN_OPERATION", "STAR", "POLYGON", "LINE"]);

function solids(list) {
  if (!Array.isArray(list)) return undefined;
  const out = list
    .filter((p) => p.visible !== false)
    .map((p) => (p.type === "SOLID" ? p.color : p.type === "IMAGE" ? "IMAGE" : p.type));
  return out.length ? out : undefined;
}

function summariseVectors(node) {
  let count = 0;
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
  const colours = new Set();
  (function w(n) {
    count++;
    if (typeof n.x === "number") { minX = Math.min(minX, n.x); maxX = Math.max(maxX, n.x + (n.width || 0)); }
    if (typeof n.y === "number") { minY = Math.min(minY, n.y); maxY = Math.max(maxY, n.y + (n.height || 0)); }
    (solids(n.fills) || []).forEach((c) => colours.add(c));
    (n.children || []).forEach(w);
  })(node);
  return {
    nodes: count,
    bounds: isFinite(minX) ? [round(minX), round(minY), round(maxX - minX), round(maxY - minY)] : null,
    colours: [...colours].sort()
  };
}

function normalise(node) {
  const out = {
    id: node.id,
    name: node.name,
    type: node.type
  };
  if (node.visible === false) out.visible = false;
  if (typeof node.x === "number") out.x = round(node.x);
  if (typeof node.y === "number") out.y = round(node.y);
  if (typeof node.width === "number") out.w = round(node.width);
  if (typeof node.height === "number") out.h = round(node.height);
  if (node.opacity !== undefined && node.opacity !== 1) out.opacity = node.opacity;
  if (node.rotation) out.rotation = round(node.rotation);
  if (node.cornerRadius !== undefined) out.radius = node.cornerRadius;
  if (node.constraints) out.constraints = node.constraints.horizontal + "/" + node.constraints.vertical;
  if (node.layout) {
    const l = node.layout;
    out.layout = [l.layoutMode, l.itemSpacing, l.padding.top + "," + l.padding.right + "," + l.padding.bottom + "," + l.padding.left,
      l.primaryAxisAlignItems, l.counterAxisAlignItems].join(" ");
  }
  if (node.layoutSizingHorizontal) out.sizeH = node.layoutSizingHorizontal;
  if (node.layoutSizingVertical) out.sizeV = node.layoutSizingVertical;

  const f = solids(node.fills); if (f) out.fills = f;
  const s = solids(node.strokes); if (s) { out.strokes = s; if (node.strokeWeight !== undefined) out.strokeWeight = node.strokeWeight; }
  if (Array.isArray(node.effects) && node.effects.length)
    out.effects = node.effects.filter((e) => e.visible !== false).map((e) => e.type + ":" + (e.radius ?? "") + (e.color ? ":" + e.color : ""));

  if (node.text) {
    const t = node.text;
    out.text = {
      chars: t.characters,
      font: [t.fontFamily, t.fontStyle, t.fontSize].join("/"),
      lineHeight: t.lineHeight && t.lineHeight.value !== undefined ? t.lineHeight.value : "auto",
      letterSpacing: t.letterSpacing && t.letterSpacing.value ? t.letterSpacing.value : undefined,
      align: t.textAlignHorizontal
    };
    if (t.textStyleId) out.text.styleId = t.textStyleId;
  }

  if (node.instance && node.instance.mainComponent) out.instanceOf = node.instance.mainComponent.name;
  if (node.component) out.componentKey = node.component.key || undefined;
  if (node.boundVariables && Object.keys(node.boundVariables).length) out.boundVariables = node.boundVariables;

  if (node.children && node.children.length) {
    // Collapse pure-artwork subtrees to a summary.
    const allVector = node.children.every((c) => VECTORISH.has(c.type) || (c.type === "GROUP" && !hasText(c)));
    if (allVector && countNodes(node) > 40) out.artwork = summariseVectors(node);
    else out.children = node.children.map(normalise);
  }
  return out;
}

function hasText(n) {
  if (n.text) return true;
  return (n.children || []).some(hasText);
}
function countNodes(n) {
  let c = 1;
  (n.children || []).forEach((x) => (c += countNodes(x)));
  return c;
}

const raw = JSON.parse(fs.readFileSync(inPath, "utf8"));
const doc = {
  schemaVersion: "normalized-1.0.0",
  source: {
    fileName: raw.source.fileName,
    pageName: raw.source.pageName,
    pageId: raw.source.pageId,
    exportedAt: raw.exportedAt,
    topLevelFrameCount: raw.source.topLevelFrameCount
  },
  variables: Object.values(raw.variables || {}).map((v) => ({
    name: v.name, collection: v.collection ? v.collection.name : null,
    type: v.resolvedType, exportedMode: v.exportedModeName, value: v.resolvedValueForExportedMode
  })).sort((a, b) => (a.collection + a.name).localeCompare(b.collection + b.name)),
  frames: raw.frames.map(normalise)
};

fs.writeFileSync(outPath, JSON.stringify(doc, null, 1));
const before = fs.statSync(inPath).size, after = fs.statSync(outPath).size;
console.log(`${path.basename(inPath)}  ${(before / 1048576).toFixed(1)} MB  ->  ${path.basename(outPath)}  ${(after / 1024).toFixed(0)} KB  (${(100 - (after / before) * 100).toFixed(1)}% smaller)`);
