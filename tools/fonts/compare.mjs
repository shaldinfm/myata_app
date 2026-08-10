/*
 * Muller replacement comparison.
 *
 *   node tools/fonts/compare.mjs <family-dir> [more dirs…]
 *
 * Each directory holds one candidate family, with files whose names contain the
 * weight: Regular/400, Medium/500, Bold/700, Black/900 (Black falls back to Bold
 * if the family has no 900). Muller in app/src/main/res/font is the baseline.
 *
 * For every representative frozen string this reports the predicted rendered
 * width against the frozen slot it has to live in, so the question "does this
 * font still fit the frozen design" is answered numerically before anything is
 * added to the app.
 *
 * Adds no fonts and downloads nothing - it reads directories it is given.
 * The licence string embedded in each file is printed so the licence is checked
 * against the actual binary rather than a web page.
 */
import fs from "node:fs";
import path from "node:path";
import { readFont, advance, coverage, STRINGS } from "./measure.mjs";

// Frozen slot each string must fit, measured from the canonical export. `slot`
// is the hard limit; `frozen` is what Muller renders today.
const SLOTS = {
  "nav-home":       { frozen: 47,  slot: 79,  note: "item is weighted 79 at 390dp; label must not force truncation" },
  "nav-player":     { frozen: 38,  slot: 68,  note: "item weighted 68" },
  "nav-collection": { frozen: 64,  slot: 94,  note: "widest label; item weighted 94. At 320dp the item is ~72dp" },
  "nav-about":      { frozen: 33,  slot: 64,  note: "item weighted 64" },
  "heading-home":   { frozen: 261, slot: 358, note: "HOME content width" },
  "heading-about":  { frozen: 225, slot: 358, note: "ABOUT US heading" },
  "btn-export":     { frozen: 249, slot: 310, note: "hug button + 48dp padding must stay inside the 358dp column" },
  "btn-donate":     { frozen: 191, slot: 310, note: "donation CTA, 239x52 frozen incl. 48dp padding" },
  "btn-subscribe":  { frozen: 136, slot: 310, note: "Boosty CTA, 184x52 frozen" },
  "mini-artist":    { frozen: 180, slot: 233, note: "fixed-width slot; overflow truncates" },
  "mini-title":     { frozen: 137, slot: 233, note: "fixed-width slot; overflow truncates" },
  "hist-long":      { frozen: 208, slot: 233, note: "longest track string in the frozen set" },
  "hist-artist":    { frozen: 179, slot: 233, note: "History row artist" },
  "player-title":   { frozen: 219, slot: 300, note: "now-playing title" },
  "about-para":     { frozen: 926, slot: 358, note: "wraps; compare LINE COUNT at 358dp, not width" },
};

const WEIGHTS = ["Regular", "Medium", "Bold", "Black"];
const PATTERNS = {
  Regular: /regular|[-_]400\b|book/i,
  Medium:  /medium|[-_]500\b/i,
  Bold:    /bold(?!er)|[-_]700\b/i,
  Black:   /black|heavy|[-_]900\b/i,
};

function familyFrom(dir) {
  if (!fs.existsSync(dir)) return null;
  const files = fs.readdirSync(dir).filter((f) => /\.(ttf|otf)$/i.test(f));
  const picked = {};
  for (const w of WEIGHTS) {
    // Bold must not swallow a file that is really Black/ExtraBold.
    const hit = files.find((f) => PATTERNS[w].test(f) && !(w === "Bold" && PATTERNS.Black.test(f)));
    if (hit) picked[w] = path.join(dir, hit);
  }
  if (!picked.Black && picked.Bold) picked.Black = picked.Bold;
  return picked;
}

const BASE_DIR = "app/src/main/res/font";
const BASELINE = {
  Regular: `${BASE_DIR}/mullerregular.ttf`,
  Medium:  `${BASE_DIR}/mullermedium.otf`,
  Bold:    `${BASE_DIR}/muller_bold.ttf`,
  Black:   `${BASE_DIR}/mullerblack.ttf`,
};

function load(map) {
  const out = {};
  for (const [w, f] of Object.entries(map)) {
    try { out[w] = readFont(f); } catch (e) { console.log(`  ! ${f}: ${e.message}`); }
  }
  return out;
}

function widthOf(fonts, s) {
  const f = fonts[s.weight] || fonts.Regular;
  if (!f) return null;
  const a = advance(f, s.text);
  return { px: (a.units / 1000) * s.size, missing: a.missing };
}

// Greedy wrap on spaces - enough to compare line counts between candidates.
function lineCount(fonts, s, width) {
  const f = fonts[s.weight] || fonts.Regular;
  if (!f) return null;
  const space = (advance(f, " ").units / 1000) * s.size;
  let lines = 1, x = 0;
  for (const word of s.text.split(/\s+/)) {
    const w = (advance(f, word).units / 1000) * s.size;
    if (x > 0 && x + space + w > width) { lines++; x = w; }
    else x += (x > 0 ? space : 0) + w;
  }
  return lines;
}

const dirs = process.argv.slice(2);
if (!dirs.length) {
  console.error("usage: node tools/fonts/compare.mjs <family-dir…>");
  console.error("  e.g. node tools/fonts/compare.mjs vendor/onest vendor/manrope vendor/montserrat");
  process.exit(1);
}

const baseline = load(BASELINE);
console.log("baseline: Muller (Fontfabric) from app/src/main/res/font\n");

for (const dir of dirs) {
  const picked = familyFrom(dir);
  if (!picked) { console.log(`=== ${dir} === NOT FOUND\n`); continue; }
  const fonts = load(picked);
  const name = path.basename(dir);

  console.log(`=== ${name} ===`);
  for (const w of WEIGHTS) {
    const f = fonts[w];
    if (!f) { console.log(`  ${w.padEnd(8)} MISSING - the design needs 400/500/700`); continue; }
    const cov = coverage(f);
    console.log(`  ${w.padEnd(8)} "${f.names.family}" wght=${f.weightClass} upem=${f.unitsPerEm} ` +
                `cap=${f.capHeight} xh=${f.xHeight} coverage=${cov.have}/${cov.total}` +
                (cov.have < cov.total ? "  << INCOMPLETE" : ""));
    if (f.names.license) console.log(`           licence: ${f.names.license.slice(0, 96)}`);
  }

  console.log(`\n  ${"string".padEnd(15)}${"muller".padStart(8)}${"cand".padStart(9)}${"delta".padStart(9)}${"slot".padStart(7)}  verdict`);
  let overflow = 0, reflow = 0;
  for (const s of STRINGS) {
    const slot = SLOTS[s.id];
    const b = widthOf(baseline, s), c = widthOf(fonts, s);
    if (!b || !c) continue;

    if (s.id === "about-para") {
      const lb = lineCount(baseline, s, slot.slot), lc = lineCount(fonts, s, slot.slot);
      const bad = lb !== lc;
      if (bad) reflow++;
      console.log(`  ${s.id.padEnd(15)}${(lb + " ln").padStart(8)}${(lc + " ln").padStart(9)}` +
                  `${((lc - lb >= 0 ? "+" : "") + (lc - lb) + " ln").padStart(9)}${(slot.slot + "dp").padStart(7)}  ` +
                  (bad ? "REFLOW - frame height changes" : "same line count"));
      continue;
    }

    const d = c.px - b.px;
    const over = c.px > slot.slot;
    if (over) overflow++;
    const pct = ((d / b.px) * 100).toFixed(1);
    console.log(`  ${s.id.padEnd(15)}${b.px.toFixed(0).padStart(7)}dp${c.px.toFixed(0).padStart(8)}dp` +
                `${((d >= 0 ? "+" : "") + d.toFixed(0) + "dp").padStart(9)}${(slot.slot + "dp").padStart(7)}  ` +
                (over ? `OVERFLOWS SLOT (${pct}%)` : `${pct}%`) +
                (c.missing ? `  [${c.missing} MISSING GLYPHS]` : ""));
  }
  console.log(`\n  ${overflow === 0 && reflow === 0 ? "fits every frozen slot" : `${overflow} overflow(s), ${reflow} reflow(s)`}\n`);
}
