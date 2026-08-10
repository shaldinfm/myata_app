/*
 * B1 shell validation: the bottom navigation across themes and phone widths.
 *
 *   node tools/qa/phone/capture-shell.mjs <before|after>
 *
 * Captures Light and Dark at 320, 360 and 412dp, and records what the nav
 * actually contains - item ids, labels and bounds - so a claim about the bar can
 * be checked rather than eyeballed. Restores the display size afterwards.
 */
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const RUN = (process.argv[2] || "").toLowerCase();
if (!["before", "after"].includes(RUN)) { console.error("usage: capture-shell.mjs <before|after>"); process.exit(1); }

const here = path.dirname(fileURLToPath(import.meta.url));
const OUT = path.join(here, "shell-" + RUN);
fs.mkdirSync(OUT, { recursive: true });

const SERIAL = process.env.PHONE_SERIAL || "emulator-5556";
const ADB = process.env.ADB || "adb";
const PKG = "dlinemedia.radioplayer.myata";
const ACT = `${PKG}/com.example.musicplayerapp.MainActivity`;

const sh = (a) => execFileSync(ADB, ["-s", SERIAL, ...a], { encoding: "utf8", maxBuffer: 64 * 1024 * 1024 });
const shBin = (a) => execFileSync(ADB, ["-s", SERIAL, ...a], { encoding: "buffer", maxBuffer: 64 * 1024 * 1024 });
const sleep = (ms) => execFileSync(process.execPath, ["-e", `setTimeout(()=>{},${ms})`], { timeout: ms + 5000 });

const DENSITY = 420;                       // the AVD's density
const widthPx = (dp) => Math.round(dp * DENSITY / 160);

function navReport() {
  try {
    sh(["shell", "uiautomator", "dump", "/sdcard/ui.xml"]);
    const xml = sh(["shell", "cat", "/sdcard/ui.xml"]);
    const items = [];
    for (const m of xml.matchAll(/<node\b([^>]*)\/?>/g)) {
      const a = Object.fromEntries([...m[1].matchAll(/(\w[\w-]*)="([^"]*)"/g)].map((x) => [x[1], x[2]]));
      const id = (a["resource-id"] || "").replace(PKG + ":id/", "");
      if (/^nav_item_/.test(id)) items.push({ id, bounds: a.bounds });
      if (/_label$/.test(id)) items.push({ id, text: a.text, bounds: a.bounds });
    }
    return items;
  } catch { return []; }
}

function dismissAnr() {
  for (let i = 0; i < 3; i++) {
    let xml = "";
    try { sh(["shell", "uiautomator", "dump", "/sdcard/anr.xml"]); xml = sh(["shell", "cat", "/sdcard/anr.xml"]); } catch { return; }
    if (!/isn't responding/i.test(xml)) return;
    const m = /text="Wait"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/.exec(xml);
    if (m) sh(["shell", "input", "tap", String((+m[1] + +m[3]) / 2), String((+m[2] + +m[4]) / 2)]);
    else sh(["shell", "input", "keyevent", "BACK"]);
    sleep(800);
  }
}

// Grant notifications up front: otherwise the runtime permission dialog covers
// the screen and every capture shows the dialog instead of the shell.
try { sh(["shell", "pm", "grant", PKG, "android.permission.POST_NOTIFICATIONS"]); } catch {}

const results = [];
try {
  for (const dp of [320, 360, 412]) {
    sh(["shell", "wm", "size", `${widthPx(dp)}x2400`]);
    sleep(1200);
    for (const theme of ["light", "dark"]) {
      sh(["shell", "cmd", "uimode", "night", theme === "dark" ? "yes" : "no"]);
      sleep(1200);
      sh(["shell", "am", "force-stop", PKG]);
      sleep(500);
      sh(["shell", "am", "start", "-n", ACT]);
      sleep(6000);
      dismissAnr();
      // leave the splash and land on Home so the bar is visible
      sleep(4000);
      dismissAnr();
      const name = `${dp}dp-${theme}`;
      fs.writeFileSync(path.join(OUT, `${name}.png`), shBin(["exec-out", "screencap", "-p"]));
      const nav = navReport();
      results.push({ width: dp, theme, nav });
      console.log(`  ${name.padEnd(14)} nav items: ${nav.filter((n) => n.id.startsWith("nav_item")).length}  labels: ${nav.filter((n) => n.text).map((n) => n.text).join(", ") || "(none visible)"}`);
    }
  }
} finally {
  sh(["shell", "wm", "size", "reset"]);
  sh(["shell", "cmd", "uimode", "night", "no"]);
}

fs.writeFileSync(path.join(OUT, "metadata.json"), JSON.stringify({ run: RUN, results }, null, 2) + "\n");
console.log(`\n  -> ${path.relative(process.cwd(), OUT)}`);
