/*
 * Font metric measurement for the Muller replacement audit.
 *
 *   node tools/fonts/measure.mjs <font.ttf|font.otf> [more fonts…]
 *
 * Prints the vertical metrics and the advance width of every representative
 * frozen string, normalised to a 1000-unit em so fonts with different upem are
 * directly comparable. Advance width is what decides whether a label still fits
 * its slot and whether a paragraph still wraps onto the same number of lines, so
 * this is the number to compare candidates on - not a screenshot.
 *
 * Kerning is not applied: it needs GPOS, and the pairs that matter here are
 * mostly Cyrillic where the effect is small relative to the differences between
 * these candidate families. Treat the output as a close estimate, and confirm
 * the shortlisted font on device.
 *
 * No network access, no font is downloaded. It only reads files it is given.
 */
import fs from "node:fs";
import { pathToFileURL } from "node:url";

// Representative strings drawn from the frozen 3.6.6 design. Each records where
// it comes from and the size it is set at, so a width delta converts straight
// into "does this still fit".
export const STRINGS = [
  { id: "nav-home",        text: "Главная",              size: 12, weight: "Medium",  where: "BottomNav label, HUG width" },
  { id: "nav-player",      text: "Плеер",                size: 12, weight: "Medium",  where: "BottomNav label, HUG width" },
  { id: "nav-collection",  text: "Коллекция",            size: 12, weight: "Medium",  where: "BottomNav label - widest of the four, 64dp frozen" },
  { id: "nav-about",       text: "О нас",                size: 12, weight: "Medium",  where: "BottomNav label, HUG width" },
  { id: "heading-home",    text: "Мятные плейлисты",     size: 28, weight: "Bold",    where: "HOME heading, 261dp frozen" },
  { id: "heading-about",   text: "Поддержать радио",     size: 24, weight: "Medium",  where: "ABOUT US heading" },
  { id: "btn-export",      text: "Экспортировать список", size: 22, weight: "Regular", where: "COLLECTION button, 249dp frozen - widest button" },
  { id: "btn-donate",      text: "Поддержать эфир",      size: 22, weight: "Regular", where: "ABOUT US donation CTA, 239x52 frozen" },
  { id: "btn-subscribe",   text: "Подписаться",          size: 22, weight: "Regular", where: "ABOUT US Boosty CTA, 184x52 frozen" },
  { id: "mini-artist",     text: "TWO DOOR CINEMA CLUB", size: 14, weight: "Regular", where: "mini-player artist, fixed width" },
  { id: "mini-title",      text: "WHAT YOU KNOW",        size: 15, weight: "Medium",  where: "mini-player title, fixed width" },
  { id: "hist-long",       text: "MIAMI HORROR FT. POOLSIDE", size: 14, weight: "Regular", where: "COLLECTION row - longest track string, 233dp slot" },
  { id: "hist-artist",     text: "TWENTY ONE PILOTS",    size: 17, weight: "Regular", where: "History row artist" },
  { id: "player-title",    text: "WHAT YOU KNOW",        size: 24, weight: "Black",   where: "PLAYER now-playing title, 219dp frozen" },
  { id: "about-para",      text: "Это «инди-эклектичное» медиа, предлагающее ироничный взгляд на массовую культуру и оригинальный подход к современной музыке.",
                                                          size: 14, weight: "Regular", where: "ABOUT US paragraph, 358dp fixed width - wrapping decides frame height" },
];

// Cyrillic the design actually uses, plus the Latin the track metadata needs.
const COVERAGE_PROBE =
  "АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯабвгдеёжзийклмнопрстуфхцчшщъыьэюя" +
  "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789«»—…";

function u16(b, p) { return b.readUInt16BE(p); }
function i16(b, p) { return b.readInt16BE(p); }

export function readFont(file) {
  const b = fs.readFileSync(file);
  const numTables = u16(b, 4);
  const t = {};
  for (let i = 0; i < numTables; i++) {
    const p = 12 + i * 16;
    t[b.toString("ascii", p, p + 4)] = { off: b.readUInt32BE(p + 8), len: b.readUInt32BE(p + 12) };
  }
  const head = t["head"], hhea = t["hhea"], os2 = t["OS/2"], maxp = t["maxp"];
  const unitsPerEm = head ? u16(b, head.off + 18) : 1000;
  const numGlyphs = maxp ? u16(b, maxp.off + 4) : 0;
  const numHMetrics = hhea ? u16(b, hhea.off + 34) : 0;

  // name table -> family / subfamily / licence
  const names = {};
  if (t["name"]) {
    const n = t["name"].off, count = u16(b, n + 2), strOff = n + u16(b, n + 4);
    for (let i = 0; i < count; i++) {
      const r = n + 6 + i * 12;
      const pid = u16(b, r), nid = u16(b, r + 6), len = u16(b, r + 8), off = u16(b, r + 10);
      const key = { 1: "family", 2: "subfamily", 13: "license", 14: "licenseURL", 16: "typoFamily" }[nid];
      if (!key || names[key]) continue;
      const raw = b.subarray(strOff + off, strOff + off + len);
      names[key] = pid === 3 ? Buffer.from(raw).swap16().toString("utf16le") : raw.toString("latin1");
    }
  }

  // cmap format 4
  const map = new Map();
  if (t["cmap"]) {
    const c = t["cmap"].off, n = u16(b, c + 2);
    let sub = 0;
    for (let i = 0; i < n; i++) {
      const p = c + 4 + i * 8, pid = u16(b, p), eid = u16(b, p + 2);
      if ((pid === 3 && (eid === 1 || eid === 10)) || pid === 0) sub = c + b.readUInt32BE(p + 4);
    }
    if (sub && u16(b, sub) === 4) {
      const segX2 = u16(b, sub + 6), seg = segX2 / 2;
      const endP = sub + 14, startP = endP + segX2 + 2, deltaP = startP + segX2, rangeP = deltaP + segX2;
      for (let s = 0; s < seg; s++) {
        const end = u16(b, endP + s * 2), start = u16(b, startP + s * 2);
        const delta = i16(b, deltaP + s * 2), ro = u16(b, rangeP + s * 2);
        if (start === 0xffff) continue;
        for (let ch = start; ch <= end && ch !== 0x10000; ch++) {
          let g;
          if (ro === 0) g = (ch + delta) & 0xffff;
          else {
            const gp = rangeP + s * 2 + ro + (ch - start) * 2;
            if (gp + 1 >= b.length) continue;
            g = u16(b, gp);
            if (g) g = (g + delta) & 0xffff;
          }
          if (g) map.set(ch, g);
        }
      }
    }
  }

  // hmtx advances
  const adv = new Uint16Array(numGlyphs);
  if (t["hmtx"] && numHMetrics) {
    const h = t["hmtx"].off;
    let last = 0;
    for (let i = 0; i < numGlyphs; i++) {
      if (i < numHMetrics) { last = u16(b, h + i * 4); }
      adv[i] = last;
    }
  }

  const v2 = os2 && u16(b, os2.off) >= 2;
  return {
    file, names, unitsPerEm, map, adv,
    weightClass: os2 ? u16(b, os2.off + 4) : null,
    fsType: os2 ? u16(b, os2.off + 8) : null,
    typoAscender: os2 ? i16(b, os2.off + 68) : null,
    typoDescender: os2 ? i16(b, os2.off + 70) : null,
    lineGap: os2 ? i16(b, os2.off + 72) : null,
    xHeight: v2 ? i16(b, os2.off + 86) : null,
    capHeight: v2 ? i16(b, os2.off + 88) : null,
  };
}

// Advance width in em units scaled to 1000upem, so fonts compare directly.
export function advance(font, text) {
  const k = 1000 / font.unitsPerEm;
  let total = 0, missing = 0;
  for (const ch of text) {
    const g = font.map.get(ch.codePointAt(0));
    if (g === undefined) { missing++; continue; }
    total += font.adv[g] * k;
  }
  return { units: total, missing };
}

export function coverage(font) {
  let have = 0;
  for (const ch of COVERAGE_PROBE) if (font.map.has(ch.codePointAt(0))) have++;
  return { have, total: [...COVERAGE_PROBE].length };
}

// Only run the CLI when invoked directly: the comparison harness imports
// readFont/advance/STRINGS from here.
const invokedDirectly =
  process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href;

const files = process.argv.slice(2);
if (invokedDirectly && !files.length) {
  console.error("usage: node tools/fonts/measure.mjs <font…>");
  process.exit(1);
}

for (const file of invokedDirectly ? files : []) {
  let f;
  try { f = readFont(file); } catch (e) { console.log(`${file}: ERROR ${e.message}`); continue; }
  const cov = coverage(f);
  console.log(`\n=== ${file.split(/[\\/]/).pop()} ===`);
  console.log(`  family "${f.names.family}" / "${f.names.subfamily}"   weightClass=${f.weightClass}  fsType=${f.fsType}  upem=${f.unitsPerEm}`);
  console.log(`  capHeight=${f.capHeight}  xHeight=${f.xHeight}  typoAsc=${f.typoAscender}  typoDesc=${f.typoDescender}  lineGap=${f.lineGap}`);
  console.log(`  coverage probe: ${cov.have}/${cov.total} glyphs present`);
  if (f.names.license) console.log(`  license string: ${f.names.license.slice(0, 110)}`);
  console.log(`  ${"id".padEnd(15)}${"size".padStart(5)}  ${"width@size".padStart(11)}  string`);
  for (const s of STRINGS) {
    const a = advance(f, s.text);
    const px = (a.units / 1000) * s.size;
    console.log(
      `  ${s.id.padEnd(15)}${String(s.size).padStart(5)}  ${px.toFixed(1).padStart(9)}dp  ` +
      `"${s.text.slice(0, 30)}${s.text.length > 30 ? "…" : ""}"${a.missing ? `  [${a.missing} MISSING GLYPHS]` : ""}`
    );
  }
}
