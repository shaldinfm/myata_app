/*
 * Muller replacement comparison.
 *
 *   node tools/fonts/compare.mjs <family-dir…>
 *
 * Each directory holds one candidate family: either static files whose names
 * carry the weight, or a single variable file (any name) with a wght axis. For a
 * variable font the weight instances are read through fvar/avar/HVAR, so the
 * numbers are the real per-weight advance widths rather than the default
 * instance scaled - verified against upstream Montserrat statics to within 2.5
 * font units of 1000 upem.
 *
 * Muller in app/src/main/res/font is the baseline. For every representative
 * frozen string this reports the predicted rendered width against the frozen
 * slot it has to live in, so "does this font still fit the frozen design" is
 * answered numerically before anything is added to the app.
 *
 * Adds no fonts and downloads nothing - it reads directories it is given, and
 * prints each file's embedded licence so the licence is checked against the
 * binary rather than a web page.
 */
import fs from "node:fs";
import path from "node:path";
import { readFont, advance, coverage, STRINGS } from "./measure.mjs";

const WEIGHTS = { Regular: 400, Medium: 500, Bold: 700, Black: 900 };

/*
 * Frozen slots. `slot` is the hard limit the string has to fit inside.
 *
 * The BottomNav slots are the item widths at 320dp, which is the tightest case
 * in the app: the bar pads 23.32/18 and gaps 3x14.6, leaving 234.88dp shared out
 * by the frozen item weights 79/68/94/64. Labels are singleLine, so overflow
 * ellipsises rather than wraps.
 */
const NAV320 = (w) => ((320 - 23.32 - 18 - 3 * 14.6) * w) / 305;

const SLOTS = {
  "nav-home":       { slot: NAV320(79), where: "BottomNav item at 320dp" },
  "nav-player":     { slot: NAV320(68), where: "BottomNav item at 320dp" },
  "nav-collection": { slot: NAV320(94), where: "BottomNav item at 320dp - tightest label in the app" },
  "nav-about":      { slot: NAV320(64), where: "BottomNav item at 320dp" },
  "heading-home":   { slot: 358, where: "HOME content column" },
  "heading-about":  { slot: 358, where: "ABOUT US heading" },
  "btn-export":     { slot: 310, where: "hug button + 48dp padding inside the 358dp column" },
  "btn-donate":     { slot: 310, where: "donation CTA, 239x52 frozen" },
  "btn-subscribe":  { slot: 310, where: "Boosty CTA, 184x52 frozen" },
  "mini-artist":    { slot: 233, where: "fixed slot, truncates" },
  "mini-title":     { slot: 233, where: "fixed slot, truncates" },
  "hist-long":      { slot: 233, where: "canonical COLLECTION row" },
  "hist-artist":    { slot: 233, where: "History row artist" },
  "player-title":   { slot: 300, where: "now-playing title" },
  // Wrapping cases: compare LINE COUNT, not width. Row height follows the count
  // because the owner ruled out ellipsis in History.
  "about-para":     { slot: 358, wrap: true, where: "ABOUT US paragraph - wrapping sets frame height" },
  "hist-stress-up": { slot: 233, wrap: true, where: "History long title, no ellipsis - lines set row height" },
  "hist-stress-mx": { slot: 233, wrap: true, where: "History long title, no ellipsis - lines set row height" },
};

const BASE = "app/src/main/res/font";
const BASELINE = {
  Regular: `${BASE}/mullerregular.ttf`,
  Medium:  `${BASE}/mullermedium.otf`,
  Bold:    `${BASE}/muller_bold.ttf`,
  Black:   `${BASE}/mullerblack.ttf`,
};

const PATTERNS = {
  Regular: /regular|[-_]400\b|book/i,
  Medium:  /medium|[-_]500\b/i,
  Bold:    /bold(?!er)|[-_]700\b/i,
  Black:   /black|heavy|[-_]900\b/i,
};

function loadFamily(dir) {
  if (!fs.existsSync(dir)) return null;
  const files = fs.readdirSync(dir).filter((f) => /\.(ttf|otf)$/i.test(f));
  if (!files.length) return null;

  // A single variable file covers every weight through its axis.
  for (const f of files) {
    const full = path.join(dir, f);
    let font;
    try { font = readFont(full); } catch { continue; }
    if (font.isVariable && font.fvar.axes.some((a) => a.tag === "wght")) {
      return { kind: "variable", font, files: [full], dir };
    }
  }

  const picked = {};
  for (const w of Object.keys(WEIGHTS)) {
    const hit = files.find((f) => PATTERNS[w].test(f) && !(w === "Bold" && PATTERNS.Black.test(f)));
    if (hit) { try { picked[w] = readFont(path.join(dir, hit)); } catch {} }
  }
  return { kind: "static", fonts: picked, files: files.map((f) => path.join(dir, f)), dir };
}

function widthOf(fam, s) {
  if (fam.kind === "variable") {
    const r = advance(fam.font, s.text, WEIGHTS[s.weight]);
    return { px: (r.units / 1000) * s.size, missing: r.missing, varied: r.varied };
  }
  const f = fam.fonts[s.weight] || fam.fonts.Regular;
  if (!f) return null;
  const r = advance(f, s.text);
  return { px: (r.units / 1000) * s.size, missing: r.missing, varied: true };
}

// Greedy wrap on spaces - enough to compare line counts between candidates.
function lines(fam, s, width) {
  const measure = (t) => {
    if (fam.kind === "variable") return (advance(fam.font, t, WEIGHTS[s.weight]).units / 1000) * s.size;
    const f = fam.fonts[s.weight] || fam.fonts.Regular;
    return f ? (advance(f, t).units / 1000) * s.size : 0;
  };
  const space = measure(" ");
  let n = 1, x = 0;
  for (const word of s.text.split(/\s+/)) {
    const w = measure(word);
    if (x > 0 && x + space + w > width) { n++; x = w; }
    else x += (x > 0 ? space : 0) + w;
  }
  return n;
}

const baselineFonts = {};
for (const [w, f] of Object.entries(BASELINE)) baselineFonts[w] = readFont(f);
const baseline = { kind: "static", fonts: baselineFonts };

const mullerBytes = fs.readdirSync(BASE)
  .filter((f) => /\.(ttf|otf)$/i.test(f))
  .reduce((n, f) => n + fs.statSync(path.join(BASE, f)).size, 0);

const dirs = process.argv.slice(2);
if (!dirs.length) {
  console.error("usage: node tools/fonts/compare.mjs <family-dir…>");
  process.exit(1);
}

console.log(`baseline: Muller (Fontfabric), 9 files, ${(mullerBytes / 1024).toFixed(0)} KB\n`);

for (const dir of dirs) {
  const fam = loadFamily(dir);
  const label = path.basename(dir);
  if (!fam) { console.log(`=== ${label} === NOT FOUND\n`); continue; }

  console.log(`=== ${label} ===`);

  // --- identity, weights, coverage, licence ------------------------------
  if (fam.kind === "variable") {
    const f = fam.font;
    const axis = f.fvar.axes.find((a) => a.tag === "wght");
    console.log(`  VARIABLE  "${f.names.family}"  upem=${f.unitsPerEm}  wght ${axis.min}..${axis.max} (default ${axis.def})`);
    console.log(`  HVAR=${f.hvar ? "yes" : "NO - per-weight widths unavailable"}  avar=${f.avar ? "yes" : "no"}`);
    console.log(`  named instances: ${f.namedInstances.map((i) => `${i.name}@${i.coords.join(",")}`).join("  ")}`);
    for (const [w, v] of Object.entries(WEIGHTS)) {
      const named = f.namedInstances.find((i) => i.coords[0] === v);
      const inRange = v >= axis.min && v <= axis.max;
      console.log(`    ${String(v).padEnd(4)} ${inRange ? (named ? `named instance "${named.name}"` : "on-axis, no named instance") : `OUT OF RANGE (axis max ${axis.max}) - clamps to ${axis.max}`}`);
    }
    const has300 = 300 >= axis.min && 300 <= axis.max;
    console.log(`    TV 300: ${has300 ? `available on axis${f.namedInstances.find((i) => i.coords[0] === 300) ? ' as "Light"' : ""}` : `NOT AVAILABLE (axis starts at ${axis.min})`}`);
    const cov = coverage(f);
    console.log(`  cyrillic+latin probe: ${cov.have}/${cov.total}${cov.have < cov.total ? "  << INCOMPLETE" : ""}`);
    if (f.names.license) console.log(`  embedded licence: ${f.names.license.slice(0, 100)}`);
  } else {
    for (const [w, v] of Object.entries(WEIGHTS)) {
      const f = fam.fonts[w];
      if (!f) { console.log(`    ${v} MISSING`); continue; }
      const cov = coverage(f);
      console.log(`    ${String(v).padEnd(4)} "${f.names.family}" wght=${f.weightClass} coverage=${cov.have}/${cov.total}`);
    }
  }

  const bytes = fam.files.reduce((n, f) => n + fs.statSync(f).size, 0);
  console.log(`  files: ${fam.files.length}, ${(bytes / 1024).toFixed(0)} KB  (Muller today: ${(mullerBytes / 1024).toFixed(0)} KB, delta ${bytes > mullerBytes ? "+" : ""}${((bytes - mullerBytes) / 1024).toFixed(0)} KB)`);

  // --- per-string widths --------------------------------------------------
  console.log(`\n  ${"string".padEnd(16)}${"wt".padStart(4)}${"muller".padStart(9)}${"cand".padStart(9)}${"delta".padStart(9)}${"%".padStart(8)}${"slot".padStart(8)}  verdict`);
  const overflow = [], gained = [];
  let worstPos = { d: -Infinity }, worstNeg = { d: Infinity };

  for (const s of STRINGS) {
    const cfg = SLOTS[s.id];
    if (!cfg) continue;
    const b = widthOf(baseline, s), c = widthOf(fam, s);
    if (!b || !c) continue;

    if (cfg.wrap) {
      const lb = lines(baseline, s, cfg.slot), lc = lines(fam, s, cfg.slot);
      const delta = lc - lb;
      if (delta > 0) gained.push({ id: s.id, lb, lc });
      console.log(`  ${s.id.padEnd(16)}${String(WEIGHTS[s.weight]).padStart(4)}${(lb + " ln").padStart(9)}${(lc + " ln").padStart(9)}` +
                  `${((delta >= 0 ? "+" : "") + delta + " ln").padStart(9)}${"".padStart(8)}${(Math.round(cfg.slot) + "dp").padStart(8)}  ` +
                  (delta > 0 ? "GAINS A LINE - height grows" : delta < 0 ? "loses a line" : "same lines"));
      continue;
    }

    const d = c.px - b.px;
    const pct = (d / b.px) * 100;
    if (d > worstPos.d) worstPos = { d, id: s.id, pct };
    if (d < worstNeg.d) worstNeg = { d, id: s.id, pct };
    const over = c.px > cfg.slot;
    if (over) overflow.push({ id: s.id, px: c.px, slot: cfg.slot, where: cfg.where });

    console.log(`  ${s.id.padEnd(16)}${String(WEIGHTS[s.weight]).padStart(4)}${b.px.toFixed(1).padStart(8)}dp${c.px.toFixed(1).padStart(8)}dp` +
                `${((d >= 0 ? "+" : "") + d.toFixed(1)).padStart(8)}dp${(pct >= 0 ? "+" : "") + pct.toFixed(1) + "%"}`.padStart(0).padStart(0) +
                `${(Math.round(cfg.slot) + "dp").padStart(8)}  ` +
                (over ? `OVERFLOWS by ${(c.px - cfg.slot).toFixed(1)}dp` : `fits, ${(cfg.slot - c.px).toFixed(1)}dp spare`) +
                (c.missing ? `  [${c.missing} MISSING GLYPHS]` : "") +
                (c.varied === false ? "  [NOT VARIED - no HVAR]" : ""));
  }

  console.log(`\n  worst widening : ${worstPos.id} ${worstPos.d > 0 ? "+" : ""}${worstPos.d.toFixed(1)}dp (${worstPos.pct.toFixed(1)}%)`);
  console.log(`  worst narrowing: ${worstNeg.id} ${worstNeg.d.toFixed(1)}dp (${worstNeg.pct.toFixed(1)}%)`);
  console.log(`  overflows      : ${overflow.length ? overflow.map((o) => `${o.id} (+${(o.px - o.slot).toFixed(1)}dp)`).join(", ") : "none"}`);
  console.log(`  gains a line   : ${gained.length ? gained.map((g) => `${g.id} ${g.lb}->${g.lc}`).join(", ") : "none"}\n`);
}
