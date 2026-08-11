/*
 * Parity validation for the typography migration.
 *
 *   node tools/figma-export/typography-migration/verify-parity.mjs <before.json> <after.json>
 *
 * Compares two canonical-snapshot exports of the same page and asserts that the
 * migration changed typography and the approved geometry, and nothing else.
 *
 * The point is to catch what a screenshot cannot: a colour that shifted, a
 * character that got edited, a node that vanished, a fill that changed while
 * everyone was looking at the type. Every node is matched by id, and every
 * property difference is classified as either expected or unexpected. Anything
 * unexpected is reported per node rather than summarised away.
 */
import fs from "node:fs";

const [beforePath, afterPath] = process.argv.slice(2);
if (!beforePath || !afterPath) {
  console.error("usage: verify-parity.mjs <before.json> <after.json>");
  process.exit(1);
}

// Typography and the approved geometry corrections may move. Nothing else may.
const EXPECTED_TEXT = new Set(["fontFamily", "fontStyle", "fontSize", "lineHeight", "letterSpacing"]);
const EXPECTED_GEOM = new Set(["width", "height", "x", "y", "layoutSizingHorizontal"]);

function index(doc) {
  const map = new Map();
  const walk = (n, frame, path) => {
    const here = path.concat(n.name || "?");
    map.set(n.id, { node: n, frame, path: here.join(" > ") });
    (n.children || []).forEach((c) => walk(c, frame, here));
  };
  for (const fr of doc.frames || []) walk(fr, fr.name, []);
  return map;
}

const before = JSON.parse(fs.readFileSync(beforePath, "utf8"));
const after = JSON.parse(fs.readFileSync(afterPath, "utf8"));
const A = index(before), B = index(after);

const missing = [], added = [], unexpected = [], typography = [], geometry = [], contentEdits = [], colourEdits = [];

for (const [id, a] of A) {
  const b = B.get(id);
  if (!b) { missing.push(a); continue; }

  const na = a.node, nb = b.node;

  if (na.type !== nb.type)
    unexpected.push({ id, path: a.path, prop: "type", from: na.type, to: nb.type });

  // Content must be untouched.
  const ca = na.text && na.text.characters, cb = nb.text && nb.text.characters;
  if (ca !== undefined && ca !== cb)
    contentEdits.push({ id, path: a.path, from: String(ca).slice(0, 40), to: String(cb).slice(0, 40) });

  // Colour must be untouched — fills, strokes and effects alike.
  for (const prop of ["fills", "strokes", "effects"]) {
    const fa = JSON.stringify(na[prop] ?? null), fb = JSON.stringify(nb[prop] ?? null);
    if (fa !== fb) colourEdits.push({ id, path: a.path, prop, from: fa.slice(0, 70), to: fb.slice(0, 70) });
  }

  // Typography.
  if (na.text && nb.text) {
    for (const key of EXPECTED_TEXT) {
      const va = JSON.stringify(na.text[key] ?? null), vb = JSON.stringify(nb.text[key] ?? null);
      if (va !== vb) typography.push({ id, path: a.path, frame: a.frame, prop: key, from: va, to: vb });
    }
    for (const key of Object.keys(na.text)) {
      if (EXPECTED_TEXT.has(key) || key === "characters") continue;
      const va = JSON.stringify(na.text[key] ?? null), vb = JSON.stringify(nb.text[key] ?? null);
      if (va !== vb) unexpected.push({ id, path: a.path, prop: "text." + key, from: va.slice(0, 50), to: vb.slice(0, 50) });
    }
  }

  // Geometry: allowed to move, but recorded so the deltas can be eyeballed.
  for (const key of EXPECTED_GEOM) {
    const va = na[key], vb = nb[key];
    if (va === undefined && vb === undefined) continue;
    if (JSON.stringify(va ?? null) !== JSON.stringify(vb ?? null))
      geometry.push({ id, path: a.path, frame: a.frame, prop: key, from: va, to: vb });
  }

  // Everything else must be identical.
  const skip = new Set([...EXPECTED_GEOM, "children", "text", "fills", "strokes", "effects", "id", "type"]);
  for (const key of Object.keys(na)) {
    if (skip.has(key)) continue;
    const va = JSON.stringify(na[key] ?? null), vb = JSON.stringify(nb[key] ?? null);
    if (va !== vb) unexpected.push({ id, path: a.path, prop: key, from: va.slice(0, 50), to: vb.slice(0, 50) });
  }
}
for (const [id, b] of B) if (!A.has(id)) added.push(b);

const line = (s) => console.log(s);
line(`before : ${beforePath}  (${A.size} nodes)`);
line(`after  : ${afterPath}  (${B.size} nodes)\n`);

line(`nodes missing after migration : ${missing.length}`);
missing.slice(0, 10).forEach((m) => line(`   ${m.path}`));
line(`nodes added after migration   : ${added.length}`);
added.slice(0, 10).forEach((m) => line(`   ${m.path}`));
line(`content edits                 : ${contentEdits.length}`);
contentEdits.slice(0, 10).forEach((c) => line(`   ${c.path}  "${c.from}" -> "${c.to}"`));
line(`colour / fill / effect edits  : ${colourEdits.length}`);
colourEdits.slice(0, 10).forEach((c) => line(`   ${c.path}  ${c.prop}`));
line(`unexpected property changes   : ${unexpected.length}`);
unexpected.slice(0, 20).forEach((u) => line(`   ${u.path}  ${u.prop}: ${u.from} -> ${u.to}`));

line(`\ntypography changes            : ${typography.length}`);
const byProp = {};
for (const t of typography) byProp[t.prop] = (byProp[t.prop] || 0) + 1;
Object.entries(byProp).forEach(([k, v]) => line(`   ${k.padEnd(14)} ${v}`));

line(`geometry changes              : ${geometry.length}`);
const byFrame = {};
for (const g of geometry) byFrame[g.frame] = (byFrame[g.frame] || 0) + 1;
Object.entries(byFrame).slice(0, 12).forEach(([k, v]) => line(`   ${k.padEnd(24)} ${v}`));

const clean = !missing.length && !added.length && !contentEdits.length && !colourEdits.length && !unexpected.length;
line(`\n${clean ? "PARITY PASS - only typography and geometry moved" : "PARITY FAIL - see the categories above"}`);
process.exit(clean ? 0 : 1);
