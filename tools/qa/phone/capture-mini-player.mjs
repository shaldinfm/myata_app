/*
 * B2 validation: the Mini Player in the running app.
 *
 *   node tools/qa/phone/capture-mini-player.mjs <api24|api36>
 *
 * MiniPlayerLayoutTest already measures the frozen geometry, and measuring is the
 * stronger check - it does not depend on what a software-rendered emulator draws.
 * This covers the two things a measurement cannot reach:
 *
 *   which screens show the pill   the frozen design draws it on HOME, COLLECTION
 *                                 and ABOUT US and omits it from PLAYER, and that
 *                                 is a navigation fact, not a layout one;
 *   whether the icon follows the  the pill must show pause while the stream plays
 *   real player                   and play when it does not, because it reads the
 *                                 same MediaController the player screen does.
 *
 * Both are recorded as uiautomator facts - presence, bounds, content-description -
 * so the result is checkable rather than eyeballed. Screenshots are captured for
 * human review only: MainActivity picks one of ten window backgrounds at random
 * per launch, so two runs legitimately differ.
 *
 * Set PHONE_SERIAL if the emulator is not emulator-5554.
 */
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const RUN = (process.argv[2] || "").toLowerCase();
if (!/^api\d+$/.test(RUN)) { console.error("usage: capture-mini-player.mjs <api24|api36>"); process.exit(1); }

const here = path.dirname(fileURLToPath(import.meta.url));
const OUT = path.join(here, "mini-player", RUN);
fs.mkdirSync(OUT, { recursive: true });

const SERIAL = process.env.PHONE_SERIAL || "emulator-5554";
const ADB = process.env.ADB || "adb";
const PKG = "dlinemedia.radioplayer.myata";
const ACT = `${PKG}/com.example.musicplayerapp.MainActivity`;

const sh = (a) => execFileSync(ADB, ["-s", SERIAL, ...a], { encoding: "utf8", maxBuffer: 64 * 1024 * 1024 });
const shBin = (a) => execFileSync(ADB, ["-s", SERIAL, ...a], { encoding: "buffer", maxBuffer: 64 * 1024 * 1024 });
const sleep = (ms) => execFileSync(process.execPath, ["-e", `setTimeout(()=>{},${ms})`], { timeout: ms + 5000 });

const DENSITY = Number(sh(["shell", "wm", "density"]).match(/(\d+)/)[1]);
const widthPx = (dp) => Math.round(dp * DENSITY / 160);
const toDp = (px) => Math.round(px * 160 / DENSITY);

/** Every node in the current hierarchy, flattened to id -> {bounds, desc}. */
function hierarchy() {
  try {
    sh(["shell", "uiautomator", "dump", "/sdcard/ui.xml"]);
    const xml = sh(["shell", "cat", "/sdcard/ui.xml"]);
    const out = {};
    for (const m of xml.matchAll(/<node\b([^>]*?)\/?>/g)) {
      const a = Object.fromEntries([...m[1].matchAll(/([\w-]+)="([^"]*)"/g)].map((x) => [x[1], x[2]]));
      const id = (a["resource-id"] || "").replace(PKG + ":id/", "");
      if (id) out[id] = { bounds: a.bounds, desc: a["content-desc"] || "", text: a.text || "" };
    }
    return out;
  } catch { return {}; }
}

/** The pill, in dp, or null when the screen does not show one. */
function pill(h) {
  const n = h.mini_player;
  if (!n || !n.bounds) return null;
  const [, l, t, r, b] = /\[(\d+),(\d+)\]\[(\d+),(\d+)\]/.exec(n.bounds).map(Number);
  return {
    x: toDp(l), width: toDp(r - l), height: toDp(b - t),
    button: h.mini_player_play_pause ? h.mini_player_play_pause.desc : null,
    title: h.mini_player_title ? h.mini_player_title.text : null,
    artist: h.mini_player_artist ? h.mini_player_artist.text : null,
  };
}

function tap(h, id) {
  const n = h[id];
  if (!n) return false;
  const [, l, t, r, b] = /\[(\d+),(\d+)\]\[(\d+),(\d+)\]/.exec(n.bounds).map(Number);
  sh(["shell", "input", "tap", String((l + r) >> 1), String((t + b) >> 1)]);
  return true;
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

/*
 * Switching the theme from the shell needs `cmd uimode`, which does not exist on
 * API 24. Rather than relabel light screenshots as dark, the dark pass is skipped
 * there and the metadata says so - MiniPlayerLayoutTest covers both themes on
 * every API level anyway, through a Configuration overlay.
 */
const THEME_SWITCH = (() => {
  try {
    // 2>&1 on the device: `cmd` reports an unimplemented subcommand on stderr,
    // which execFileSync does not hand back, so probing stdout alone always
    // looks like success.
    const out = sh(["shell", "cmd uimode night no 2>&1"]);
    return /No shell command implementation|Unknown command|not found/i.test(out) ? null : true;
  } catch { return null; }
})();
const THEMES = THEME_SWITCH ? ["light", "dark"] : ["light"];
if (!THEME_SWITCH) console.log("  note: `cmd uimode` unavailable on this image - light only");

function setNight(theme) {
  if (!THEME_SWITCH) return;
  sh(["shell", "cmd", "uimode", "night", theme === "dark" ? "yes" : "no"]);
  sleep(1500);
}

function shot(name) {
  fs.writeFileSync(path.join(OUT, `${name}.png`), shBin(["exec-out", "screencap", "-p"]));
}

function launch() {
  sh(["shell", "am", "force-stop", PKG]);
  sleep(600);
  sh(["shell", "am", "start", "-n", ACT]);
  sleep(7000);
  dismissAnr();
  sleep(4000);          // clear the splash and land on Home
  dismissAnr();
}

// connectedDebugAndroidTest uninstalls the app when it finishes, so a capture run
// straight after a test run would otherwise drive an app that is not there.
if (!sh(["shell", "pm", "list", "packages", PKG]).includes(PKG)) {
  console.error(`  ${PKG} is not installed. Build and install the debug APK first:\n` +
    "    ./gradlew assembleDebug\n" +
    `    adb -s ${SERIAL} install -r app/build/outputs/apk/debug/app-debug.apk`);
  process.exit(1);
}

try { sh(["shell", "pm", "grant", PKG, "android.permission.POST_NOTIFICATIONS"]); } catch {}

const results = {
  run: RUN,
  density: DENSITY,
  themeSwitch: THEME_SWITCH ? "cmd uimode" : "unavailable on this image - light only",
  widths: [],
  flow: [],
};

try {
  /* --- 1. every shipping width, both themes: is the pill 358x74 at 16dp? --- */
  for (const dp of [320, 360, 390, 412]) {
    sh(["shell", "wm", "size", `${widthPx(dp)}x${widthPx(dp) * 2}`]);
    sleep(1500);
    for (const theme of THEMES) {
      setNight(theme);
      launch();
      const p = pill(hierarchy());
      shot(`${dp}dp-${theme}-home`);
      results.widths.push({ width: dp, theme, pill: p });
      console.log(`  ${dp}dp ${theme.padEnd(5)} home  ${p ? `${p.width}x${p.height}dp @${p.x}dp  button="${p.button}"` : "NO PILL"}`);
    }
  }

  /* --- 2. at the design width: playback state, then every destination --- */
  sh(["shell", "wm", "size", `${widthPx(390)}x${widthPx(390) * 2}`]);
  sleep(1500);
  for (const theme of THEMES) {
    setNight(theme);
    launch();

    const step = (name, h) => {
      const p = pill(h);
      shot(`390dp-${theme}-${name}`);
      results.flow.push({ theme, step: name, pill: p });
      console.log(`  390dp ${theme.padEnd(5)} ${name.padEnd(16)} ${p ? `button="${p.button}" title="${p.title}"` : "NO PILL"}`);
      return p;
    };

    step("01-home-idle", hierarchy());

    // The pill's own button, not the player screen's: this is what proves the
    // control is wired to the real MediaController rather than to a local flag.
    tap(hierarchy(), "mini_player_play_pause");
    sleep(9000);
    dismissAnr();
    step("02-home-playing", hierarchy());

    tap(hierarchy(), "mini_player_play_pause");
    sleep(3000);
    step("03-home-paused", hierarchy());

    // Playing again, so the following screens are captured mid-playback and the
    // icon has something to stay in step with.
    tap(hierarchy(), "mini_player_play_pause");
    sleep(9000);
    dismissAnr();

    tap(hierarchy(), "nav_item_favorites"); sleep(2500); step("04-collection", hierarchy());
    tap(hierarchy(), "nav_item_info");      sleep(2500); step("05-about", hierarchy());
    // PLAYER is the one canonical screen with no mini player.
    tap(hierarchy(), "nav_item_player");    sleep(3000); step("06-player", hierarchy());
    tap(hierarchy(), "nav_item_home");      sleep(2500); step("07-back-home", hierarchy());

    // Background and foreground: playback must survive, and the pill must come
    // back showing what the service is actually doing.
    sh(["shell", "input", "keyevent", "HOME"]);
    sleep(6000);
    sh(["shell", "am", "start", "-n", ACT]);
    sleep(4000);
    dismissAnr();
    step("08-after-background", hierarchy());

    sh(["shell", "am", "force-stop", PKG]);
    sleep(1000);
  }
} finally {
  sh(["shell", "wm", "size", "reset"]);
  setNight("light");
  try { sh(["shell", "am", "force-stop", PKG]); } catch {}
}

fs.writeFileSync(path.join(OUT, "metadata.json"), JSON.stringify(results, null, 2) + "\n");
console.log(`\n  -> ${path.relative(process.cwd(), OUT)}`);
