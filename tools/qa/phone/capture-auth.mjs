/*
 * auth-sign-in and auth-create-account against their authoritative frames.
 *
 *   node tools/qa/phone/capture-auth.mjs <api24|api36>
 *
 * AuthLayoutTest already measures both screens - every box, every gap, every token
 * colour, in both themes, at four widths - and measuring is the stronger check. This
 * covers the one thing a measurement cannot: what the screen actually looks like,
 * for a human to hold next to the Figma frame.
 *
 * ## It pins the density to 443
 *
 * The frames are drawn at 390dp and this AVD is 1080px wide, so 443dpi is the one
 * density at which a screenshot is a like-for-like comparison: 1080 / (443/160) =
 * 390.1dp. That is a deliberate departure from the owner's standing convention -
 * manual visual QA runs at the panel's default 420dpi - and it is why the density is
 * restored on the way out, including if this script throws.
 *
 * ## Both themes, from a cold start each time
 *
 * `cmd uimode night` flips the theme, and the app is force-stopped and relaunched
 * around it rather than reconfigured in place: MainActivity declares configChanges
 * for orientation and this keeps the run honest about what a listener sees when they
 * open the app in either mode.
 *
 * Nothing here signs in, and nothing here can: the screenshots are of the resting
 * screens, and the app under test still has the auth backend it shipped with.
 *
 * Facts go to metadata.json; screenshots stay local (../.gitignore drops *.png)
 * because MainActivity picks one of ten window backgrounds at random per launch.
 */
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const RUN = (process.argv[2] || "").toLowerCase();
if (!/^api\d+$/.test(RUN)) { console.error("usage: capture-auth.mjs <api24|api36>"); process.exit(1); }

const here = path.dirname(fileURLToPath(import.meta.url));
const OUT = path.join(here, "auth", RUN);
fs.mkdirSync(OUT, { recursive: true });

const SERIAL = process.env.PHONE_SERIAL || "emulator-5554";
const ADB = process.env.ADB || "adb";
const PKG = "dlinemedia.radioplayer.myata";
const ACT = `${PKG}/com.example.musicplayerapp.MainActivity`;

/** 1080 / (443/160) = 390.1dp, which is the width the frames are drawn at. */
const PARITY_DPI = 443;

const sh = (a) => execFileSync(ADB, ["-s", SERIAL, ...a], { encoding: "utf8", maxBuffer: 64 * 1024 * 1024 });
const shBin = (a) => execFileSync(ADB, ["-s", SERIAL, ...a], { encoding: "buffer", maxBuffer: 64 * 1024 * 1024 });
const sleep = (ms) => execFileSync(process.execPath, ["-e", `setTimeout(()=>{},${ms})`], { timeout: ms + 5000 });

if (!sh(["shell", "pm", "list", "packages", PKG]).includes(PKG)) {
  console.error(`  ${PKG} is not installed. ./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk`);
  process.exit(1);
}

const SDK = Number(sh(["shell", "getprop", "ro.build.version.sdk"]).trim());

// POST_NOTIFICATIONS only exists from API 33; asking below that prints pm's whole
// help text over the run.
if (SDK >= 33) {
  try { sh(["shell", "pm", "grant", PKG, "android.permission.POST_NOTIFICATIONS"]); } catch { /* already granted */ }
}

/** Every node in the current window, by resource id, with its bounds in px. */
function dump() {
  sh(["shell", "uiautomator", "dump", "/sdcard/qa-auth.xml"]);
  const xml = sh(["shell", "cat", "/sdcard/qa-auth.xml"]);
  const nodes = new Map();
  const re = /resource-id="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/g;
  let m;
  while ((m = re.exec(xml)) !== null) {
    const id = m[1].split("/").pop();
    if (!id || nodes.has(id)) continue;
    nodes.set(id, { left: +m[2], top: +m[3], right: +m[4], bottom: +m[5] });
  }
  return nodes;
}

/**
 * Dismisses the emulator's spurious "System UI isn't responding" dialog.
 *
 * It appears on the API 36 image for reasons that have nothing to do with this app -
 * it names System UI, not the package under test - and it covers the whole screen, so
 * a dump taken under it describes the dialog and a tap lands on it. Cheap to check,
 * and it turns an unexplainable "no view with id profile_entry" into nothing at all.
 */
function dismissAnr() {
  const anr = dump().get("aerr_wait");
  if (!anr) return false;
  sh(["shell", "input", "tap",
    String(Math.round((anr.left + anr.right) / 2)),
    String(Math.round((anr.top + anr.bottom) / 2))]);
  sleep(1200);
  return true;
}

function tap(id) {
  dismissAnr();
  const node = dump().get(id);
  if (!node) throw new Error(`no view with id ${id} on screen`);
  const cx = Math.round((node.left + node.right) / 2);
  const cy = Math.round((node.top + node.bottom) / 2);
  sh(["shell", "input", "tap", String(cx), String(cy)]);
  sleep(700);
}

function shot(name) {
  dismissAnr();
  const png = shBin(["exec-out", "screencap", "-p"]);
  fs.writeFileSync(path.join(OUT, `${name}.png`), png);
  return `${name}.png`;
}

/** The rows worth writing down, as dp, for the side-by-side. */
function measure(nodes, density) {
  const dp = (px) => Number((px / density).toFixed(1));
  const out = {};
  for (const [id, b] of nodes) {
    if (!id.startsWith("auth_")) continue;
    out[id] = { y: dp(b.top), height: dp(b.bottom - b.top), x: dp(b.left), width: dp(b.right - b.left) };
  }
  return out;
}

const metadata = { run: RUN, sdk: SDK, densityDpi: PARITY_DPI, frames: {}, screens: {} };
const restore = [];

try {
  sh(["shell", "wm", "density", String(PARITY_DPI)]);
  restore.push(() => sh(["shell", "wm", "density", "reset"]));
  sleep(1500);

  const size = sh(["shell", "wm", "size"]).trim();
  metadata.size = size;
  metadata.widthDp = Number((1080 / (PARITY_DPI / 160)).toFixed(1));
  const density = PARITY_DPI / 160;

  for (const theme of ["light", "dark"]) {
    sh(["shell", "cmd", "uimode", "night", theme === "dark" ? "yes" : "no"]);
    restore.push(() => sh(["shell", "cmd", "uimode", "night", "no"]));
    sleep(1200);

    sh(["shell", "am", "force-stop", PKG]);
    sleep(400);
    sh(["shell", "am", "start", "-n", ACT]);
    sleep(3500);
    dismissAnr();

    tap("profile_entry");
    tap("profile_sign_in");
    metadata.screens[`auth-sign-in-${theme}`] = {
      shot: shot(`auth-sign-in-${theme}`),
      figma: theme === "dark" ? "2517:3570" : "2517:2603",
      measuredDp: measure(dump(), density),
    };

    tap("auth_back");
    tap("profile_create_account");
    metadata.screens[`auth-create-account-${theme}`] = {
      shot: shot(`auth-create-account-${theme}`),
      figma: theme === "dark" ? "2517:3591" : "2517:2624",
      measuredDp: measure(dump(), density),
    };
  }

  // The frames' own numbers, so the file is self-contained for whoever reads it.
  metadata.frames["auth-sign-in"] = {
    size: "390x576",
    rows: { b0: 92, b1: 114, labelEmail: 156, inputEmail: 180, labelPassword: 252,
      inputPassword: 276, forgot: 344, submit: 388, createAccount: 456, guest: 532 },
  };
  metadata.frames["auth-create-account"] = {
    size: "390x596",
    rows: { labelName: 92, inputName: 116, labelEmail: 188, inputEmail: 212,
      labelPassword: 284, inputPassword: 308, rule: 372, submit: 412, haveAccount: 488 },
  };
} finally {
  // Density and theme are device settings; leaving either pinned would silently
  // change every later run, including the ones that are supposed to be at 420.
  for (const undo of restore.reverse()) { try { undo(); } catch { /* best effort */ } }
}

fs.writeFileSync(path.join(OUT, "metadata.json"), JSON.stringify(metadata, null, 2));
console.log(`  wrote ${Object.keys(metadata.screens).length} screenshots to ${path.relative(process.cwd(), OUT)}`);
