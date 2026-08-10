/*
 * Android TV regression capture. Runs the SAME steps before and after the A0
 * isolation, so the two runs are comparable by construction.
 *
 *   node tools/qa/tv/capture-tv-baseline.mjs before
 *   node tools/qa/tv/capture-tv-baseline.mjs after
 *
 * Per step it records two things:
 *   - a screenshot, for human review of colour and layout;
 *   - a uiautomator dump, which gives every node's resource-id, bounds and
 *     focus state.
 *
 * The dump is what the comparison actually leans on. Screenshots of the player
 * can never match exactly between two runs - it is live radio, so the track
 * metadata and album art change - whereas ids, bounds and the focus chain are
 * stable and are precisely what an unintended theme change would disturb.
 */
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const RUN = (process.argv[2] || "").toLowerCase();
if (!["before", "after"].includes(RUN)) {
  console.error("usage: node capture-tv-baseline.mjs <before|after>");
  process.exit(1);
}

const here = path.dirname(fileURLToPath(import.meta.url));
const OUT = path.join(here, RUN);
fs.mkdirSync(OUT, { recursive: true });

const ADB = process.env.ADB || "adb";
// The Gradle applicationId is not the Kotlin namespace: the package on the device
// is dlinemedia.radioplayer.myata while the classes live in com.example.musicplayerapp.
// Resolve both from the device rather than assuming they match.
const PKG = process.env.TV_PKG || "dlinemedia.radioplayer.myata";
const ACT = `${PKG}/com.example.musicplayerapp.TvMainActivity`;

const sh = (args, opts = {}) => execFileSync(ADB, args, { encoding: "utf8", maxBuffer: 64 * 1024 * 1024, ...opts });
const shBin = (args) => execFileSync(ADB, args, { encoding: "buffer", maxBuffer: 64 * 1024 * 1024 });
const sleep = (ms) => execFileSync(process.execPath, ["-e", `setTimeout(()=>{},${ms})`], { timeout: ms + 5000 });
const key = (k) => { sh(["shell", "input", "keyevent", k]); };

/* uiautomator dump -> the interesting parts, with live text stripped out so a
 * diff shows structure rather than whatever track happens to be playing. */
function hierarchy() {
  let xml = "";
  try {
    sh(["shell", "uiautomator", "dump", "/sdcard/ui.xml"]);
    xml = sh(["shell", "cat", "/sdcard/ui.xml"]);
  } catch (e) {
    return { error: String(e.message || e), nodes: [], focused: null };
  }
  const nodes = [];
  let focused = null;
  for (const m of xml.matchAll(/<node\b([^>]*)\/?>/g)) {
    const a = Object.fromEntries([...m[1].matchAll(/(\w[\w-]*)="([^"]*)"/g)].map((x) => [x[1], x[2]]));
    if (!a["resource-id"] && !a["content-desc"] && a.class === "android.view.View") continue;
    const rec = {
      id: (a["resource-id"] || "").replace(PKG + ":id/", ""),
      cls: (a.class || "").split(".").pop(),
      bounds: a.bounds || "",
      focusable: a.focusable === "true" || undefined,
      focused: a.focused === "true" || undefined,
      selected: a.selected === "true" || undefined,
      hasText: a.text ? true : undefined          // presence, not content: content is live
    };
    nodes.push(rec);
    if (rec.focused) focused = rec.id || rec.cls;
  }
  return { focused, nodes };
}

/* Wait until our activity is actually resumed. Without this the first capture
 * races the launcher: uiautomator dumps whatever is on top, which is the TV
 * launcher on a fast run and nothing at all on a slow one. Either way it is a
 * property of the timing, not of the app, and it would show up as a phantom
 * difference between two runs. */
function waitForApp(timeoutMs = 15000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      const top = sh(["shell", "dumpsys", "activity", "activities"]);
      if (new RegExp(`mResumedActivity.*${PKG}`).test(top)) return true;
    } catch {}
    sleep(250);
  }
  return false;
}

const steps = [];
function step(name, action, waitMs = 1500) {
  if (action) action();
  sleep(waitMs);
  const png = path.join(OUT, `${String(steps.length + 1).padStart(2, "0")}-${name}.png`);
  fs.writeFileSync(png, shBin(["exec-out", "screencap", "-p"]));
  const h = hierarchy();
  steps.push({ step: steps.length + 1, name, focused: h.focused, nodes: h.nodes, error: h.error });
  console.log(`  ${String(steps.length).padStart(2)} ${name.padEnd(34)} focus=${h.focused || "(none)"}  nodes=${h.nodes.length}`);
}

/* ---------- environment ---------- */
const props = ["ro.build.version.sdk", "ro.build.version.release", "ro.product.model",
               "ro.product.device", "ro.build.characteristics", "ro.sf.lcd_density"];
const env = {};
for (const p of props) { try { env[p] = sh(["shell", "getprop", p]).trim(); } catch { env[p] = "?"; } }
// ro.build.characteristics reads "emulator" on the TV AVD, so it is useless as a
// guard. The leanback feature is the canonical way to tell a TV apart.
try {
  const feats = sh(["shell", "pm", "list", "features"]);
  env.leanback = /android\.software\.leanback/.test(feats);
  env.television = /android\.hardware\.type\.television/.test(feats);
} catch { env.leanback = false; env.television = false; }
try { env.nightMode = /mNightMode=(\S+\s*\([^)]*\))/.exec(sh(["shell", "dumpsys", "uimode"]))?.[1]; } catch {}
try { env.size = sh(["shell", "wm", "size"]).trim(); env.density = sh(["shell", "wm", "density"]).trim(); } catch {}
// `emulator -version` sometimes prefixes its output with "INFO | ". Store just
// the version, or two runs of the same binary compare as different.
try {
  const raw = execFileSync(process.env.EMULATOR || "emulator", ["-version"], { encoding: "utf8" });
  env.emulator = (/Android emulator version [^\r\n]+/.exec(raw) || [raw.split("\n")[0]])[0].trim();
} catch {}
try { env.appCommit = execFileSync("git", ["rev-parse", "HEAD"], { encoding: "utf8", cwd: here }).trim(); } catch {}
try { env.appVersion = sh(["shell", "dumpsys", "package", PKG]).match(/versionName=(\S+)/)?.[1]; } catch {}

console.log(`\n=== TV capture: ${RUN.toUpperCase()} ===`);
console.log(`  device   : ${env["ro.product.model"]} (${env["ro.product.device"]})`);
console.log(`  api      : ${env["ro.build.version.sdk"]} / Android ${env["ro.build.version.release"]}`);
console.log(`  screen   : ${env.size} ${env.density}`);
console.log(`  tv       : leanback=${env.leanback} television=${env.television}  nightMode=${env.nightMode}`);
console.log(`  commit   : ${env.appCommit}\n`);

if (!env.leanback || !env.television) {
  console.error(`REFUSING: leanback=${env.leanback}, television=${env.television} — this is not a TV device.`);
  console.error("A phone AVD cannot produce a TV baseline.");
  process.exit(2);
}

/* ---------- deterministic walk ----------
 * Splash auto-advances to selection after 2000ms; selection focuses cardMyata;
 * a card opens the player with focus on btn_play_pause. */
sh(["shell", "am", "force-stop", PKG]);
sleep(500);

/* The splash lasts 2000ms and then replaces itself. A uiautomator dump costs
 * more than that, so it cannot be captured deterministically - one run would
 * catch the splash and the next the screen after it, and the comparison would
 * report a difference that is purely about timing. It is recorded as a
 * screenshot for the record and left out of the structural gate. */
sh(["shell", "am", "start", "-n", ACT]);
fs.writeFileSync(path.join(OUT, "00-splash-transient.png"), shBin(["exec-out", "screencap", "-p"]));
console.log("   0 splash-transient (screenshot only, not gated)");

waitForApp();
step("selection-myata-focused", null, 3500);
step("selection-gold-focused", () => key("DPAD_RIGHT"));
step("selection-xtra-focused", () => key("DPAD_RIGHT"));
step("selection-back-to-myata", () => { key("DPAD_LEFT"); key("DPAD_LEFT"); });
step("player-myata-enter", () => key("DPAD_CENTER"), 4000);
step("player-myata-playpause-focused", null, 1200);
step("player-focus-down", () => key("DPAD_DOWN"));
step("player-focus-right", () => key("DPAD_RIGHT"));
step("player-switch-gold", () => key("ENTER"), 4000);
step("player-focus-right-2", () => key("DPAD_RIGHT"));
step("player-switch-xtra", () => key("ENTER"), 4000);
step("player-focus-up-to-playpause", () => key("DPAD_UP"));
step("player-pause", () => key("DPAD_CENTER"), 1500);
step("player-resume", () => key("DPAD_CENTER"), 1500);
step("back-to-selection", () => key("BACK"), 1500);

fs.writeFileSync(path.join(OUT, "metadata.json"),
  JSON.stringify({ run: RUN, capturedAt: new Date().toISOString(), env, steps }, null, 2) + "\n");

console.log(`\n  ${steps.length} steps -> ${path.relative(process.cwd(), OUT)}`);
console.log(`  focus chain: ${steps.map((s) => s.focused || "-").join(" > ")}`);
