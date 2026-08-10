/*
 * Deterministic verification that the A3 theme refactor preserved the random
 * window background feature, and that AppTheme0..9 did not pick up AppTheme's
 * window flags.
 *
 *   node tools/qa/themes/verify-random-themes.mjs <before.apk> <after.apk>
 *   node tools/qa/themes/verify-random-themes.mjs <after.apk>
 *
 * This replaces an earlier check that hashed whole screenshots across launches
 * and counted distinct results. That check was wrong, and provably so: it
 * reported 11 distinct results from 10 possible backgrounds, because it was
 * seeing album art, playlists and the clock rather than the theme. Screen
 * uniqueness cannot evidence anything about theme selection.
 *
 * What is compared here is the compiled resource table of the APK - what
 * actually ships - together with the source of the selection itself. The
 * on-device counterpart is RandomWindowBackgroundTest, which resolves the
 * attributes through the real parent chain.
 */
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";

const args = process.argv.slice(2);
if (!args.length) { console.error("usage: node verify-random-themes.mjs [before.apk] <after.apk>"); process.exit(1); }
const AFTER = args[args.length - 1];
const BEFORE = args.length > 1 ? args[0] : null;

const SDK = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT || "D:/Program Files/Android/Sdk";
const AAPT2 = (() => {
  const bt = path.join(SDK, "build-tools");
  for (const v of fs.readdirSync(bt).sort().reverse()) {
    const p = path.join(bt, v, process.platform === "win32" ? "aapt2.exe" : "aapt2");
    if (fs.existsSync(p)) return p;
  }
  throw new Error("aapt2 not found under " + bt);
})();

/* Framework attributes appear in aapt2's dump as raw ids, not names. These four
 * are asserted below against AppTheme, whose XML is known to set exactly
 * windowIsTranslucent, windowFullscreen, windowDisablePreview and
 * statusBarColor - so a wrong mapping here fails loudly instead of silently
 * passing everything. */
const ATTR = {
  "0x01010054": "android:windowBackground",
  "0x01010058": "android:windowIsTranslucent",
  "0x0101020d": "android:windowFullscreen",
  "0x01010222": "android:windowDisablePreview",
  "0x01010451": "android:statusBarColor"
};

function themeTable(apk) {
  const dump = execFileSync(AAPT2, ["dump", "resources", apk], { encoding: "utf8", maxBuffer: 512 * 1024 * 1024 });
  const out = {};
  let cur = null;
  for (const line of dump.split(/\r?\n/)) {
    const res = /^\s*resource\s+0x[0-9a-f]+\s+(\w+)\/([A-Za-z0-9_.]+)/.exec(line);
    if (res) { cur = res[1] === "style" ? res[2] : null; if (cur) out[cur] = { parent: null, items: {} }; continue; }
    if (!cur) continue;
    const par = /parent=style\/([A-Za-z0-9_.]+)/.exec(line);
    if (par) { out[cur].parent = par[1]; continue; }
    const entry = /^\s*(?:([A-Za-z_]+)\(0x[0-9a-f]+\)|(0x[0-9a-f]+))\s*=\s*(.+?)\s*$/.exec(line);
    if (entry) out[cur].items[entry[1] || ATTR[entry[2]] || entry[2]] = entry[3];
  }
  return out;
}

const problems = [];
const ok = (m) => console.log(`  PASS  ${m}`);
const bad = (m) => { console.log(`  FAIL  ${m}`); problems.push(m); };

/* ---- 0. the attr-id mapping is right ---- */
console.log("=== 0. self-check: framework attribute ids ===");
const after = themeTable(AFTER);
const appTheme = after["AppTheme"];
if (!appTheme) bad("AppTheme not found in the compiled resources");
else {
  const expect = ["android:windowIsTranslucent", "android:windowFullscreen",
                  "android:windowDisablePreview", "android:statusBarColor"];
  const missing = expect.filter((a) => !(a in appTheme.items));
  if (missing.length) bad(`attr-id mapping is wrong: AppTheme should declare ${missing.join(", ")}`);
  else ok("AppTheme declares all four expected framework attributes - id mapping confirmed");
}

/* ---- 1. the selection expression ---- */
console.log("\n=== 1. MainActivity selection ===");
const src = fs.readFileSync("app/src/main/java/com/example/musicplayerapp/MainActivity.kt", "utf8");
if (/val\s+theme\s*=\s*\(0\.\.9\)\.random\(\)/.test(src)) ok("selects (0..9).random()");
else bad("(0..9).random() not found in MainActivity");
let branches = 0;
for (let i = 0; i <= 9; i++) {
  if (new RegExp(`${i}\\s*->\\s*\\{\\s*setTheme\\(R\\.style\\.AppTheme${i}\\)`).test(src)) branches++;
  else bad(`branch ${i} does not call setTheme(R.style.AppTheme${i})`);
}
if (branches === 10) ok("all 10 branches map i -> AppTheme<i>");

/* ---- 2/3. reachable, and each keeps its own background ---- */
console.log("\n=== 2/3. compiled resource table (what actually ships) ===");
const bgOf = (t, n) => (t[n] ? t[n].items["android:windowBackground"] : undefined);
const afterBg = {};
for (let i = 0; i <= 9; i++) {
  const n = `AppTheme${i}`;
  if (!after[n]) { bad(`${n} is absent from the compiled resources`); continue; }
  const bg = bgOf(after, n);
  afterBg[n] = bg;
  if (bg === `@drawable/screen${i}`) ok(`${n} -> ${bg}   parent=${after[n].parent}`);
  else bad(`${n} windowBackground is ${bg || "(absent)"}, expected @drawable/screen${i}`);
}
if (Object.keys(afterBg).length !== 10) bad(`only ${Object.keys(afterBg).length} of 10 themes were read`);

/* ---- 4. identical before vs after ---- */
if (BEFORE) {
  console.log("\n=== 4. before vs after ===");
  const before = themeTable(BEFORE);
  let diffs = 0, read = 0;
  for (let i = 0; i <= 9; i++) {
    const n = `AppTheme${i}`;
    const b = bgOf(before, n), a = bgOf(after, n);
    if (b === undefined || a === undefined) { bad(`${n}: could not read windowBackground in ${b === undefined ? "before" : "after"}`); continue; }
    read++;
    if (b !== a) { bad(`${n}: ${b} -> ${a}`); diffs++; }
  }
  if (read !== 10) bad(`only ${read} of 10 mappings could be compared - refusing to call this identical`);
  else if (!diffs) ok(`all 10 windowBackground mappings identical: ${Object.entries(afterBg).map(([k, v]) => k.replace("AppTheme", "") + "->" + v.replace("@drawable/screen", "")).join(" ")}`);
  console.log(`  note  parent before: ${before["AppTheme0"] ? before["AppTheme0"].parent : "?"}`);
  console.log(`  note  parent after : ${after["AppTheme0"] ? after["AppTheme0"].parent : "?"}`);
} else console.log("\n=== 4. before vs after: skipped (no before APK given) ===");

/* ---- 5. window flags not declared ---- */
console.log("\n=== 5. window flags on AppTheme0..9 ===");
const FLAGS = ["android:windowFullscreen", "android:windowIsTranslucent", "android:windowDisablePreview"];
let flagged = 0;
for (let i = 0; i <= 9; i++) {
  const n = `AppTheme${i}`;
  if (!after[n]) continue;
  const has = FLAGS.filter((f) => f in after[n].items && !/^false$/i.test(after[n].items[f]));
  if (has.length) { bad(`${n} declares ${has.join(", ")}`); flagged++; }
}
if (!flagged) ok("none of AppTheme0..9 declares fullscreen / translucent / disablePreview");
console.log("  note  this is the declaration level. Inheritance through the parent chain is");
console.log("        covered on-device by RandomWindowBackgroundTest.");

console.log(`\n${problems.length ? `FAILED: ${problems.length} problem(s)` : "ALL DETERMINISTIC CHECKS PASSED"}`);
process.exitCode = problems.length ? 1 : 0;
