/*
 * HOME validation: the frozen re-skin in the running app.
 *
 *   node tools/qa/phone/capture-home.mjs <api24|api36>
 *
 * HomeLayoutTest already measures the frozen anchors, and measuring is the
 * stronger check. This covers what a measurement cannot reach:
 *
 *   the Mini Player gate   HOME is the screen the pill floats over, so it is
 *                          where "hidden until a stream is started, visible
 *                          afterwards" is actually observable;
 *   the stream targets     tapping a banner must still switch stream and open
 *                          PLAYER, which is behaviour the re-skin must not change;
 *   the bottom clearance   the last playlist must be reachable and not sit under
 *                          the pill once it appears.
 *
 * Facts go to metadata.json; screenshots stay local (../.gitignore drops *.png)
 * because MainActivity picks one of ten window backgrounds at random per launch.
 */
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const RUN = (process.argv[2] || "").toLowerCase();
if (!/^api\d+$/.test(RUN)) { console.error("usage: capture-home.mjs <api24|api36>"); process.exit(1); }

const here = path.dirname(fileURLToPath(import.meta.url));
const OUT = path.join(here, "home", RUN);
fs.mkdirSync(OUT, { recursive: true });

const SERIAL = process.env.PHONE_SERIAL || "emulator-5554";
const ADB = process.env.ADB || "adb";
const PKG = "dlinemedia.radioplayer.myata";
const ACT = `${PKG}/com.example.musicplayerapp.MainActivity`;

const sh = (a) => execFileSync(ADB, ["-s", SERIAL, ...a], { encoding: "utf8", maxBuffer: 64 * 1024 * 1024 });
const shBin = (a) => execFileSync(ADB, ["-s", SERIAL, ...a], { encoding: "buffer", maxBuffer: 64 * 1024 * 1024 });
const sleep = (ms) => execFileSync(process.execPath, ["-e", `setTimeout(()=>{},${ms})`], { timeout: ms + 5000 });

if (!sh(["shell", "pm", "list", "packages", PKG]).includes(PKG)) {
  console.error(`  ${PKG} is not installed. ./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk`);
  process.exit(1);
}

const DENSITY = Number(sh(["shell", "wm", "density"]).match(/(\d+)/)[1]);
const widthPx = (dp) => Math.round(dp * DENSITY / 160);
const toDp = (px) => Math.round(px * 160 / DENSITY);

const THEME_SWITCH = (() => {
  try {
    const out = sh(["shell", "cmd uimode night no 2>&1"]);
    return /No shell command implementation|Unknown command|not found/i.test(out) ? null : true;
  } catch { return null; }
})();
const THEMES = THEME_SWITCH ? ["light", "dark"] : ["light"];
if (!THEME_SWITCH) console.log("  note: `cmd uimode` unavailable on this image - light only");
const setNight = (t) => { if (THEME_SWITCH) { sh(["shell", "cmd", "uimode", "night", t === "dark" ? "yes" : "no"]); sleep(1500); } };

function hierarchy() {
  try {
    sh(["shell", "uiautomator", "dump", "/sdcard/ui.xml"]);
    const xml = sh(["shell", "cat", "/sdcard/ui.xml"]);
    const out = {};
    for (const m of xml.matchAll(/<node\b([^>]*?)\/?>/g)) {
      const a = Object.fromEntries([...m[1].matchAll(/([\w-]+)="([^"]*)"/g)].map((x) => [x[1], x[2]]));
      const id = (a["resource-id"] || "").replace(PKG + ":id/", "");
      if (id) out[id] = { bounds: a.bounds, text: a.text || "", desc: a["content-desc"] || "" };
    }
    return out;
  } catch { return {}; }
}

const box = (n) => {
  if (!n || !n.bounds) return null;
  const [, l, t, r, b] = /\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]/.exec(n.bounds).map(Number);
  return { x: toDp(l), y: toDp(t), w: toDp(r - l), h: toDp(b - t) };
};

function tap(h, id) {
  const n = h[id];
  if (!n) return false;
  const [, l, t, r, b] = /\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]/.exec(n.bounds).map(Number);
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

const shot = (n) => fs.writeFileSync(path.join(OUT, `${n}.png`), shBin(["exec-out", "screencap", "-p"]));

function launch() {
  // force-stop takes the playback service with it, which is what makes the next
  // start a genuine cold launch with no session for the pill to find.
  sh(["shell", "am", "force-stop", PKG]);
  sleep(700);
  sh(["shell", "am", "start", "-n", ACT]);
  sleep(7000); dismissAnr(); sleep(4000); dismissAnr();
}

try { sh(["shell", "pm", "grant", PKG, "android.permission.POST_NOTIFICATIONS"]); } catch {}

const results = { run: RUN, density: DENSITY, themeSwitch: THEME_SWITCH ? "cmd uimode" : "unavailable", widths: [], flow: [] };

try {
  /* --- 1. every width and theme, clean launch: anchors, and no pill yet --- */
  for (const dp of [320, 360, 390, 412]) {
    sh(["shell", "wm", "size", `${widthPx(dp)}x${widthPx(dp) * 2}`]);
    sleep(1500);
    for (const theme of THEMES) {
      setNight(theme);
      launch();
      const h = hierarchy();
      shot(`${dp}dp-${theme}-home`);
      const row = {
        width: dp, theme,
        greeting: box(h.home_greeting),
        streams: box(h.streams),
        playlistsHeading: box(h.playlistString),
        playlists: box(h.playlists),
        miniPlayer: box(h.mini_player),
      };
      results.widths.push(row);
      console.log(`  ${dp}dp ${theme.padEnd(5)} greeting=${row.greeting ? row.greeting.h + "dp" : "?"} ` +
        `streams@${row.streams ? row.streams.y : "?"} playlists@${row.playlists ? row.playlists.y : "?"} ` +
        `pill=${row.miniPlayer ? "PRESENT" : "none"}`);
    }
  }

  /* --- 2. at the design width: the Mini Player gate, driven from HOME --- */
  sh(["shell", "wm", "size", `${widthPx(390)}x${widthPx(390) * 2}`]);
  sleep(1500);
  for (const theme of THEMES) {
    setNight(theme);
    launch();

    const step = (name) => {
      const h = hierarchy();
      shot(`390dp-${theme}-${name}`);
      const rec = { theme, step: name, miniPlayer: box(h.mini_player), playlists: box(h.playlists) };
      results.flow.push(rec);
      console.log(`  390dp ${theme.padEnd(5)} ${name.padEnd(22)} pill=${rec.miniPlayer ? "PRESENT" : "none"}`);
      return h;
    };

    // Clean launch: nothing has been started, so the pill must not be up.
    step("01-clean-launch");

    // Starting a stream is the existing HOME behaviour: the banner switches the
    // stream and opens PLAYER. The re-skin must not have changed either.
    const h = hierarchy();
    tap(h, "myata_stream_banner");
    sleep(9000);
    dismissAnr();
    step("02-player-after-banner-tap");

    // Back on HOME the pill is up, because there is a session now.
    tap(hierarchy(), "nav_item_home");
    sleep(2500);
    step("03-home-with-pill");

    // Scrolled to the end, the last playlist must still clear the pill.
    sh(["shell", "input", "swipe", String(widthPx(195)), String(widthPx(500)), String(widthPx(195)), String(widthPx(200)), "400"]);
    sleep(1500);
    step("04-home-scrolled-to-end");

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
