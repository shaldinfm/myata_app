/*
 * Contract conformance for a post-migration export.
 *
 *   node tools/figma-export/typography-migration/verify-contract.mjs <after.json> [more…]
 *
 * Parity proves the migration changed nothing it should not. This proves it
 * changed everything it should, correctly: every text node in the exported page
 * is re-classified with the frozen classifier, and its actual family, weight and
 * size are checked against what the contract demands.
 *
 * The two checks catch different failures. Parity would not notice a button that
 * ended up at 19px instead of 22px - a size change is an allowed category. This
 * does notice, because it knows what the size was supposed to be.
 */
import fs from "node:fs";
import path from "node:path";

globalThis.figma = { showUI() {}, ui: { postMessage() {}, onmessage: null } };
globalThis.__html__ = "";
await import(new URL("../font-trial/code.js", import.meta.url).href);
const classify = globalThis.__fontTrialClassify;

const files = process.argv.slice(2);
if (!files.length) {
  console.error("usage: verify-contract.mjs <after.json> [more…]");
  process.exit(1);
}

// Weight numbers the exporter records, mapped back to the style names the
// classifier speaks.
const STYLE_OF_WEIGHT = { 300: "Light", 400: "Regular", 500: "Medium", 600: "SemiBold", 700: "Bold", 800: "ExtraBold", 900: "Black" };

let failed = 0;

for (const file of files) {
  const doc = JSON.parse(fs.readFileSync(file, "utf8"));
  const pageName = (doc.source && doc.source.pageName) || path.basename(file);
  const rows = [];
  for (const fr of doc.frames || []) {
    (function walk(n, p) {
      const here = p.concat(n.name || "?");
      if (n.type === "TEXT" && n.text) {
        rows.push({
          frame: fr.name, name: n.name, path: here.join(" > "),
          text: String(n.text.characters || "").replace(/\n/g, " ").trim(),
          size: n.text.fontSize,
          family: n.text.fontFamily,
          style: n.text.fontStyle || STYLE_OF_WEIGHT[n.text.fontWeight] || "?",
        });
      }
      (n.children || []).forEach((c) => walk(c, here));
    })(fr, []);
  }

  const violations = [];
  const famTally = {};
  for (const r of rows) {
    // The classifier expects the FROZEN size for size-conditional rules, so a
    // node already migrated to 21px has to be considered at its pre-migration
    // 22px. Only the compact-action rule is size-conditional, and only at 22.
    const probeSize = r.size === 21 ? 22 : r.size;
    const v = classify({ name: r.name, path: r.path, text: r.text, size: probeSize, frame: r.frame });
    famTally[v.family] = (famTally[v.family] || 0) + 1;

    if (r.family !== v.family)
      violations.push({ ...r, prop: "family", want: v.family, got: r.family, role: v.role });
    if (v.weight && r.style !== v.weight)
      violations.push({ ...r, prop: "weight", want: v.weight, got: r.style, role: v.role });
    if (v.size && r.size !== v.size)
      violations.push({ ...r, prop: "size", want: v.size + "px", got: r.size + "px", role: v.role });
    // A role with no size override must keep a size the contract never changes.
    if (!v.size && (r.size === 19 || r.size === 20))
      violations.push({ ...r, prop: "size", want: "unchanged (22px)", got: r.size + "px", role: v.role });
  }

  console.log(`\n=== ${pageName} ===`);
  console.log(`  text nodes : ${rows.length}   families: ${Object.entries(famTally).map(([k, v]) => k + " " + v).join(" / ")}`);
  if (!violations.length) {
    console.log("  CONTRACT PASS - every node matches its role's family, weight and size");
  } else {
    failed++;
    console.log(`  CONTRACT FAIL - ${violations.length} node(s) off contract:`);
    for (const v of violations)
      console.log(`     [${v.frame}] "${v.text.slice(0, 34)}"  ${v.prop}: got ${v.got}, contract wants ${v.want}   (${v.role})`);
  }
}

console.log(failed ? `\n${failed} page(s) off contract` : "\nall pages conform to the typography contract");
process.exit(failed ? 1 : 0);
