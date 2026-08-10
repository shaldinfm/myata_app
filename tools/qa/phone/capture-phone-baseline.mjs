/*
 * Phone rendering capture, for the A3 theme refactor.
 *
 *   node tools/qa/phone/capture-phone-baseline.mjs before
 *   node tools/qa/phone/capture-phone-baseline.mjs after
 *   node tools/qa/phone/capture-phone-baseline.mjs before --night
 *
 * A3 changes the theme architecture, so the question is whether the phone UI
 * still renders as it did. Same shape as the TV harness: a fixed walk, a
 * screenshot and a uiautomator hierarchy per step.
 *
 * One complication specific to this app - MainActivity picks one of ten window
 * backgrounds at random per launch, so two runs will legitimately differ. The
 * theme applied is therefore recorded per run and screenshots are advisory; the
 * gate is the hierarchy, plus the check that the random selection still happens
 * at all.
 */
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const RUN = (process.argv[2] || "").toLowerCase();
const NIGHT = process.argv.includes("--night");
if (!["before", "after"].includes(RUN)) {
  console.error("usage: node capture-phone-baseline.mjs <before|after> [--night]");
  process.exit(1);
}

const here = path.dirname(fileURLToPath(import.meta.url));
const OUT = path.join(here, RUN + (NIGHT ? "-night" : ""));
fs.mkdirSync(OUT, { recursive: true });

const SERIAL = process.env.PHONE_SERIAL || "emulator-5556";
const ADB = process.env.ADB || "adb";
const PKG = "dlinemedia.radioplayer.myata";
const ACT = `${PKG}/com.example.musicplayerapp.MainActivity`;

const sh = (args) => execFileSync(ADB, ["-s", SERIAL, ...args], { encoding: "utf8", maxBuffer: 64 * 1024 * 1024 });
const shBin = (args) => execFileSync(ADB, ["-s", SERIAL, ...args], { encoding: "buffer", maxBuffer: 64 * 1024 * 1024 });
const sleep = (ms) => execFileSync(process.execPath, ["-e", `setTimeout(()=>{},${ms})`], { timeout: ms + 5000 });

function hierarchy() {
  try {
    sh(["shell", "uiautomator", "dump", "/sdcard/ui.xml"]);
    const xml = sh(["shell", "cat", "/sdcard/ui.xml"]);
    const nodes = [];
    for (const m of xml.matchAll(/<node\b([^>]*)\/?>/g)) {
      const a = Object.fromEntries([...m[1].matchAll(/(\w[\w-]*)="([^"]*)"/g)].map((x) => [x[1], x[2]]));
      const id = (a["resource-id"] || "").replace(PKG + ":id/", "");
      if (!id && a.class === "android.view.View") continue;
      nodes.push({ id, cls: (a.class || "").split(".").pop(), bounds: a.bounds || "", hasText: a.text ? true : undefined });
    }
    return nodes;
  } catch (e) { return []; }
}

function waitForApp(timeoutMs = 20000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try { if (new RegExp(`mResumedActivity.*${PKG}`).test(sh(["shell", "dumpsys", "activity", "activities"]))) return true; } catch {}
    sleep(250);
  }
  return false;
}

const env = {};
for (const p of ["ro.build.version.sdk", "ro.product.model", "ro.product.device"]) { try { env[p] = sh(["shell", "getprop", p]).trim(); } catch {} }
try { env.size = sh(["shell", "wm", "size"]).trim(); env.density = sh(["shell", "wm", "density"]).trim(); } catch {}
try { env.nightMode = /mNightMode=(\S+)/.exec(sh(["shell", "dumpsys", "uimode"]))?.[1]; } catch {}
try { env.appCommit = execFileSync("git", ["rev-parse", "HEAD"], { encoding: "utf8", cwd: here }).trim(); } catch {}
try {
  const feats = sh(["shell", "pm", "list", "features"]);
  env.touchscreen = /android\.hardware\.touchscreen/.test(feats);
  env.leanback = /android\.software\.leanback/.test(feats);
} catch {}

console.log(`\n=== PHONE capture: ${RUN.toUpperCase()}${NIGHT ? " (night)" : ""} ===`);
console.log(`  device : ${env["ro.product.model"]}  api ${env["ro.build.version.sdk"]}  ${env.size} ${env.density}`);
console.log(`  night  : ${env.nightMode}   touchscreen=${env.touchscreen} leanback=${env.leanback}\n`);

if (env.leanback) { console.error("REFUSING: this is a TV device, not a phone."); process.exit(2); }

sh(["shell", "cmd", "uimode", "night", NIGHT ? "yes" : "no"]);
sleep(1200);

const steps = [];
function step(name, action, waitMs = 1800) {
  if (action) action();
  sleep(waitMs);
  fs.writeFileSync(path.join(OUT, `${String(steps.length + 1).padStart(2, "0")}-${name}.png`), shBin(["exec-out", "screencap", "-p"]));
  const nodes = hierarchy();
  steps.push({ step: steps.length + 1, name, nodes });
  console.log(`  ${String(steps.length).padStart(2)} ${name.padEnd(24)} nodes=${nodes.length}`);
}

/* A slow emulator throws "System UI isn't responding" over the app; dismiss it
 * so it does not end up in a screenshot and get mistaken for a rendering change. */
function dismissAnr() {
  for (let i = 0; i < 3; i++) {
    let xml = "";
    try { sh(["shell", "uiautomator", "dump", "/sdcard/anr.xml"]); xml = sh(["shell", "cat", "/sdcard/anr.xml"]); } catch { return; }
    if (!/isn't responding|не отвечает/i.test(xml)) return;
    const m = /text="(Wait|Подождать)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/.exec(xml);
    if (m) sh(["shell", "input", "tap", String((+m[2] + +m[4]) / 2), String((+m[3] + +m[5]) / 2)]);
    else sh(["shell", "input", "keyevent", "BACK"]);
    sleep(800);
  }
}

/* MainActivity picks one of ten window backgrounds at random and logs nothing,
 * and the chosen drawable is not exposed by dumpsys. So detect the randomness
 * behaviourally: launch repeatedly and count how many DISTINCT screens come
 * back. One distinct result across many launches means the feature is gone. */
import crypto from "node:crypto";
function launchAndHash() {
  sh(["shell", "am", "force-stop", PKG]);
  sleep(400);
  sh(["shell", "am", "start", "-n", ACT]);
  waitForApp();
  sleep(1400);
  dismissAnr();
  const png = shBin(["exec-out", "screencap", "-p"]);
  return crypto.createHash("sha256").update(png).digest("hex").slice(0, 12);
}

sh(["shell", "am", "force-stop", PKG]);
sleep(600);
sh(["shell", "am", "start", "-n", ACT]);
waitForApp();
sleep(2500);
dismissAnr();
step("home", null, 1200);
step("home-settled", null, 1200);

const picks = [];
for (let i = 0; i < 12; i++) picks.push(launchAndHash());
const distinct = [...new Set(picks)];
console.log(`  12 launches -> ${distinct.length} distinct screens`);
console.log(`  hashes: ${picks.join(" ")}`);

fs.writeFileSync(path.join(OUT, "metadata.json"),
  JSON.stringify({ run: RUN, night: NIGHT, capturedAt: new Date().toISOString(), env, steps, randomLaunchHashes: picks, distinctLaunchScreens: [...new Set(picks)].length }, null, 2) + "\n");
console.log(`\n  -> ${path.relative(process.cwd(), OUT)}`);
