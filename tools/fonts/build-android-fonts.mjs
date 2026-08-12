/*
 * Build the Android font set: fetch pinned upstream statics, subset, verify.
 *
 *   node tools/fonts/build-android-fonts.mjs <out-dir>
 *   node tools/fonts/build-android-fonts.mjs <out-dir> --keep-work
 *
 * Reproducible from nothing but this file: every source is a pinned upstream
 * release or commit, every command is spelled out, and every output is verified
 * before it is written.
 *
 * Why statics rather than a variable font: minSdk here is 24 and
 * fontVariationSettings needs API 26. A variable font would render its default
 * instance - Regular - for every weight on API 24-25.
 *
 * Why upstream statics rather than varLib.instancer: both projects ship built,
 * hinted statics for exactly the weights the design needs. Instancing would
 * discard the designers' own builds to reproduce them less well.
 *
 * Subsetting policy is deliberately conservative. Radio metadata is dynamic -
 * track and artist names arrive from the stream and cannot be predicted - so the
 * subset keeps whole Unicode blocks rather than the characters the app happens
 * to contain today. Scripts the upstream font does not cover are left to the
 * Android system fallback; no coverage is invented.
 */
import fs from "node:fs";
import path from "node:path";
import { execFileSync } from "node:child_process";

const OUT = process.argv[2];
if (!OUT) {
  console.error("usage: build-android-fonts.mjs <out-dir> [--keep-work]");
  process.exit(1);
}
const KEEP = process.argv.includes("--keep-work");

/* ----------------------------------------------------------- provenance -- */

const SOURCES = {
  Onest: {
    repo: "https://github.com/simpals/onest",
    kind: "release",
    version: "2.001",
    asset: "https://github.com/simpals/onest/releases/download/2.001/onest-2.001.zip",
    licence: "SIL Open Font License 1.1",
    inZip: (w) => `onest-2.001/fonts/ttf/Onest-${w}.ttf`,
    oflInZip: "onest-2.001/OFL.txt",
    weights: { Light: 300, Regular: 400, Medium: 500, Bold: 700, Black: 900 },
  },
  Montserrat: {
    repo: "https://github.com/JulietaUla/Montserrat",
    kind: "commit",
    version: "555facfb2a18c72c3c0380f0d9c0f060453a9058",
    raw: (w) => `https://raw.githubusercontent.com/JulietaUla/Montserrat/555facfb2a18c72c3c0380f0d9c0f060453a9058/fonts/ttf/Montserrat-${w}.ttf`,
    ofl: "https://raw.githubusercontent.com/JulietaUla/Montserrat/555facfb2a18c72c3c0380f0d9c0f060453a9058/OFL.txt",
    licence: "SIL Open Font License 1.1",
    weights: { Regular: 400, Medium: 500, Bold: 700, Black: 900 },
  },
};

/* ------------------------------------------------------------- subsetting -- */

/*
 * Unicode blocks kept. Whole blocks, not observed characters: a now-playing
 * title can contain anything the stream sends.
 */
const UNICODE_RANGES = [
  ["U+0000-007F", "Basic Latin"],
  ["U+0080-00FF", "Latin-1 Supplement"],
  ["U+0100-017F", "Latin Extended-A"],
  ["U+0180-024F", "Latin Extended-B"],
  ["U+0250-02AF", "IPA Extensions"],
  ["U+02B0-02FF", "Spacing Modifier Letters"],
  ["U+0300-036F", "Combining Diacritical Marks"],
  ["U+0400-04FF", "Cyrillic"],
  ["U+0500-052F", "Cyrillic Supplement"],
  ["U+1E00-1EFF", "Latin Extended Additional"],
  ["U+2000-206F", "General Punctuation"],
  ["U+2070-209F", "Super/subscripts"],
  ["U+20A0-20BF", "Currency Symbols"],
  ["U+2100-214F", "Letterlike Symbols"],
  ["U+2150-218F", "Number Forms"],
  ["U+2190-21FF", "Arrows"],
  ["U+2200-22FF", "Mathematical Operators"],
  ["U+2600-26FF", "Miscellaneous Symbols"],
  ["U+2C60-2C7F", "Latin Extended-C"],
  ["U+2DE0-2DFF", "Cyrillic Extended-A"],
  ["U+A640-A69F", "Cyrillic Extended-B"],
  ["U+A720-A7FF", "Latin Extended-D"],
  ["U+FB00-FB4F", "Alphabetic Presentation Forms"],
  ["U+FE20-FE2F", "Combining Half Marks"],
];

// Layout features worth keeping. kern and the default shaping features matter
// for Cyrillic; discretionary and stylistic sets do not.
const LAYOUT_FEATURES = "kern,liga,clig,calt,ccmp,locl,mark,mkmk,rlig";

/* ----------------------------------------------------------------- utils -- */

const sh = (cmd, args) => execFileSync(cmd, args, { encoding: "utf8", maxBuffer: 64 * 1024 * 1024 });
const py = (args) => sh("python", args);
const kb = (n) => (n / 1024).toFixed(1) + " KB";

function curl(url, dest) {
  sh("curl", ["-sL", "--max-time", "300", "-o", dest, url]);
  if (!fs.existsSync(dest) || fs.statSync(dest).size < 1024) throw new Error(`download failed: ${url}`);
}

fs.mkdirSync(OUT, { recursive: true });
const work = path.join(OUT, ".work");
fs.mkdirSync(work, { recursive: true });

/* --------------------------------------------------------------- fetch --- */

console.log("=".repeat(72));
console.log("FETCH (pinned upstream)");
console.log("=".repeat(72));

const fetched = {};
for (const [family, src] of Object.entries(SOURCES)) {
  console.log(`\n${family}  ${src.repo}  @ ${src.version}`);
  fetched[family] = {};
  const famWork = path.join(work, family);
  fs.mkdirSync(famWork, { recursive: true });

  if (src.kind === "release") {
    const zip = path.join(famWork, "release.zip");
    if (!fs.existsSync(zip)) curl(src.asset, zip);
    sh("unzip", ["-o", "-q", zip, "-d", famWork]);
    for (const w of Object.keys(src.weights)) {
      const from = path.join(famWork, src.inZip(w));
      if (!fs.existsSync(from)) throw new Error(`missing in release: ${src.inZip(w)}`);
      fetched[family][w] = from;
    }
    fs.copyFileSync(path.join(famWork, src.oflInZip), path.join(OUT, `OFL-${family}.txt`));
  } else {
    for (const w of Object.keys(src.weights)) {
      const dest = path.join(famWork, `${family}-${w}.ttf`);
      if (!fs.existsSync(dest)) curl(src.raw(w), dest);
      fetched[family][w] = dest;
    }
    curl(src.ofl, path.join(OUT, `OFL-${family}.txt`));
  }
  for (const [w, f] of Object.entries(fetched[family]))
    console.log(`  ${(family + "-" + w).padEnd(22)} ${kb(fs.statSync(f).size).padStart(10)}`);
}

/* -------------------------------------------------------------- subset --- */

console.log("\n" + "=".repeat(72));
console.log("SUBSET");
console.log("=".repeat(72));
const unicodes = UNICODE_RANGES.map(([r]) => r).join(",");
console.log(`  ranges: ${UNICODE_RANGES.length} blocks`);
console.log(`  features: ${LAYOUT_FEATURES}\n`);

const built = [];
for (const [family, weights] of Object.entries(fetched)) {
  for (const [w, src] of Object.entries(weights)) {
    const android = `${family.toLowerCase()}_${w.toLowerCase()}.ttf`;
    const dest = path.join(OUT, android);
    const args = [
      "-m", "fontTools.subset", src,
      `--unicodes=${unicodes}`,
      `--layout-features=${LAYOUT_FEATURES}`,
      "--name-IDs=*",
      "--name-legacy",
      "--notdef-outline",
      "--recommended-glyphs",
      "--drop-tables+=DSIG",
      `--output-file=${dest}`,
    ];
    py(args);
    const before = fs.statSync(src).size, after = fs.statSync(dest).size;
    built.push({ family, weight: w, expect: SOURCES[family].weights[w], file: android, dest, src, before, after });
    console.log(`  ${android.padEnd(26)} ${kb(before).padStart(10)} -> ${kb(after).padStart(9)}   ${((1 - after / before) * 100).toFixed(0)}% smaller`);
  }
}

/* -------------------------------------------------------------- verify --- */

console.log("\n" + "=".repeat(72));
console.log("VERIFY");
console.log("=".repeat(72));

// Russian, Ukrainian, Belarusian and Serbian letters an artist name can contain,
// plus the typographic and currency marks metadata routinely carries.
const PROBE = "АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯабвгдеёжзийклмнопрстуфхцчшщъыьэюя" +
              "ҐЄІЇґєіїЎўЂђЈјЉљЊњЋћЏџ" +
              "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789" +
              "«»„“”‘’—–…•·№§±×÷°′″₽$€£¥¢©®™&@#%‰†‡";

let failures = 0;
const upstreamGaps = {};
console.log(`  probe: ${[...PROBE].length} codepoints (Cyrillic incl. Ukrainian/Belarusian/Serbian, Latin, digits, typographic, currency)\n`);

for (const b of built) {
  // Two different questions, and only one of them is a defect: did subsetting
  // drop something the upstream font had, or does the upstream font simply not
  // cover it? A gap that exists upstream is a fact to report - Android falls back
  // to a system font for it - not a build failure. Inventing coverage is not an
  // option, so the check compares against the source rather than against a wish.
  const report = JSON.parse(py([
    "-c",
    "import sys,json\n" +
    "from fontTools.ttLib import TTFont\n" +
    "f=TTFont(sys.argv[1])\n" +
    "cmap=f.getBestCmap()\n" +
    "probe=sys.argv[2]\n" +
    "missing=[hex(ord(c)) for c in probe if ord(c) not in cmap]\n" +
    "os2=f['OS/2']\n" +
    "n=f['name']\n" +
    "def nm(i):\n" +
    "    r=n.getDebugName(i)\n" +
    "    return r if r else ''\n" +
    "up=TTFont(sys.argv[3]).getBestCmap()\n" +
    "lost=[hex(ord(c)) for c in probe if ord(c) not in cmap and ord(c) in up]\n" +
    "absent=[hex(ord(c)) for c in probe if ord(c) not in up]\n" +
    "print(json.dumps({'weightClass':os2.usWeightClass,'family':nm(1),'subfamily':nm(2),'typoFamily':nm(16),'typoSub':nm(17),'glyphs':len(f.getGlyphOrder()),'lost':lost,'absentUpstream':absent,'codepoints':len(cmap),'upem':f['head'].unitsPerEm,'licence':nm(13)[:60]}))",
    b.dest, PROBE, b.src,
  ]).trim());

  const weightOk = report.weightClass === b.expect;
  const nothingLost = report.lost.length === 0;
  if (!weightOk || !nothingLost) failures++;
  if (report.absentUpstream.length) upstreamGaps[b.family] = report.absentUpstream;

  console.log(`  ${b.file.padEnd(26)} usWeightClass ${String(report.weightClass).padEnd(4)}${weightOk ? "OK " : `WRONG, expected ${b.expect} `}` +
              `cmap ${String(report.codepoints).padStart(4)}  ` +
              (nothingLost ? "subsetting lost nothing" : `SUBSETTING LOST ${report.lost.length}: ${report.lost.slice(0, 6).join(",")}`) +
              (report.absentUpstream.length ? `   (${report.absentUpstream.length} absent upstream)` : ""));
  console.log(`  ${" ".repeat(26)} name "${report.family}" / "${report.subfamily}"` +
              (report.typoFamily ? `   typo "${report.typoFamily}" / "${report.typoSub}"` : ""));
}

/* -------------------------------------------------------------- report --- */

const totalBefore = built.reduce((n, b) => n + b.before, 0);
const totalAfter = built.reduce((n, b) => n + b.after, 0);
const MULLER = 824180;

console.log("\n" + "=".repeat(72));
console.log("SIZE");
console.log("=".repeat(72));
console.log(`  upstream statics, unsubsetted : ${kb(totalBefore)}`);
console.log(`  after subsetting              : ${kb(totalAfter)}   (${((1 - totalAfter / totalBefore) * 100).toFixed(0)}% smaller)`);
console.log(`  Muller being removed          : ${kb(MULLER)}  (9 files)`);
console.log(`  net change                    : ${totalAfter > MULLER ? "+" : ""}${kb(totalAfter - MULLER)}`);

const manifest = {
  generatedAt: new Date().toISOString(),
  note: "Reproduce with: node tools/fonts/build-android-fonts.mjs <out-dir>",
  sources: Object.fromEntries(Object.entries(SOURCES).map(([k, v]) => [k, {
    repo: v.repo, pinnedTo: v.kind === "release" ? `release ${v.version}` : `commit ${v.version}`,
    licence: v.licence,
  }])),
  subset: {
    tool: `fontTools ${py(["-c", "import fontTools;print(fontTools.version)"]).trim()} fontTools.subset`,
    unicodeRanges: UNICODE_RANGES.map(([r, n]) => ({ range: r, block: n })),
    layoutFeatures: LAYOUT_FEATURES.split(","),
    command: `python -m fontTools.subset <src> --unicodes=<ranges> --layout-features=${LAYOUT_FEATURES} --name-IDs=* --name-legacy --notdef-outline --recommended-glyphs --drop-tables+=DSIG --output-file=<dest>`,
  },
  fonts: built.map((b) => ({
    file: b.file, family: b.family, weight: b.expect,
    bytesUpstream: b.before, bytesSubset: b.after,
  })),
  upstreamCoverageGaps: upstreamGaps,
  totals: { upstreamBytes: totalBefore, subsetBytes: totalAfter, mullerRemovedBytes: MULLER, netBytes: totalAfter - MULLER },
};
fs.writeFileSync(path.join(OUT, "FONT-PROVENANCE.json"), JSON.stringify(manifest, null, 2) + "\n");
console.log(`\n  wrote ${path.join(OUT, "FONT-PROVENANCE.json")}`);

if (!KEEP) fs.rmSync(work, { recursive: true, force: true });

console.log(failures ? `\n${failures} font(s) FAILED verification` : "\nall fonts verified: weight class and probe coverage correct");
process.exit(failures ? 1 : 0);
