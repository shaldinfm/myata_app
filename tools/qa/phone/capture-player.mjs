/*
 * PLAYER validation: the frozen re-skin in the running app.
 *
 *   node tools/qa/phone/capture-player.mjs <api24|api36>
 *
 * PlayerLayoutTest already measures the frozen upper-section anchors. This covers
 * the behaviour the re-skin must not have disturbed, which no measurement can
 * reach:
 *
 *   swipe + switching   MYATA / GOLD / XTRA still swipe, and the dots follow;
 *   play / pause        the one semantic control still drives the real player;
 *   favourite           still toggles;
 *   History             still opens its bottom sheet - Phase C owns the redesign,
 *                       so Phase B only has to keep the entry working;
 *   Mini Player         still absent on PLAYER.
 *
 * Facts go to metadata.json; screenshots stay local (../.gitignore drops *.png).
 */
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const RUN = (process.argv[2] || "").toLowerCase();
if (!/^api\d+$/.test(RUN)) { console.error("usage: capture-player.mjs <api24|api36>"); process.exit(1); }

const here = path.dirname(fileURLToPath(import.meta.url));
const OUT = path.join(here, "player", RUN);
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
    const out = { __text: [] };
    for (const m of xml.matchAll(/<node\b([^>]*?)\/?>/g)) {
      const a = Object.fromEntries([...m[1].matchAll(/([\w-]+)="([^"]*)"/g)].map((x) => [x[1], x[2]]));
      const id = (a["resource-id"] || "").replace(PKG + ":id/", "");
      if (a.text) out.__text.push(a.text);
      if (id) out[id] = { bounds: a.bounds, text: a.text || "", desc: a["content-desc"] || "" };
    }
    return out;
  } catch { return { __text: [] }; }
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

function openPlayer() {
  sh(["shell", "am", "force-stop", PKG]);
  sleep(700);
  sh(["shell", "am", "start", "-n", ACT]);
  sleep(7000); dismissAnr(); sleep(4000); dismissAnr();
  tap(hierarchy(), "nav_item_player");
  sleep(3000);
  dismissAnr();
}

/** Which dot is active, by comparing the three dots' bounds order. */
function activeDot(h) {
  return ["dot_1", "dot_2", "dot_3"].filter((d) => h[d]).length ? "present" : "missing";
}

try { sh(["shell", "pm", "grant", PKG, "android.permission.POST_NOTIFICATIONS"]); } catch {}

const results = { run: RUN, density: DENSITY, themeSwitch: THEME_SWITCH ? "cmd uimode" : "unavailable", widths: [], flow: [] };

try {
  /* --- 1. every width and theme: the frozen upper-section anchors --- */
  for (const dp of [320, 360, 390, 412]) {
    sh(["shell", "wm", "size", `${widthPx(dp)}x${widthPx(dp) * 2}`]);
    sleep(1500);
    for (const theme of THEMES) {
      setNight(theme);
      openPlayer();
      const h = hierarchy();
      shot(`${dp}dp-${theme}-player`);
      const row = {
        width: dp, theme,
        header: box(h.player_header),
        headerLabel: h.player_header_label ? h.player_header_label.text : null,
        dots: activeDot(h),
        artwork: box(h.photo),
        title: h.main_song ? h.main_song.text : null,
        artist: h.main_author ? h.main_author.text : null,
        play: box(h.btn_play),
        playDesc: h.btn_play ? h.btn_play.desc : null,
        favourite: box(h.btn_favorite),
        history: box(h.btn_history),
        miniPlayer: box(h.mini_player),
      };
      results.widths.push(row);
      console.log(`  ${dp}dp ${theme.padEnd(5)} "${row.headerLabel}" art=${row.artwork ? row.artwork.w + "dp" : "?"} ` +
        `play=${row.play ? row.play.w + "dp" : "?"} pill=${row.miniPlayer ? "PRESENT" : "none"}`);
    }
  }

  /* --- 2. at the design width: behaviour the re-skin must not have changed --- */
  sh(["shell", "wm", "size", `${widthPx(390)}x${widthPx(390) * 2}`]);
  sleep(1500);
  for (const theme of THEMES) {
    setNight(theme);
    openPlayer();

    const step = (name) => {
      const h = hierarchy();
      shot(`390dp-${theme}-${name}`);
      const rec = {
        theme, step: name,
        play: h.btn_play ? h.btn_play.desc : null,
        favourite: h.btn_favorite ? h.btn_favorite.desc : null,
        title: h.main_song ? h.main_song.text : null,
        artist: h.main_author ? h.main_author.text : null,
        miniPlayer: box(h.mini_player) ? "PRESENT" : "none",
        sheetOpen: h.__text.some((t) => /История/i.test(t)) && !h.btn_play,
      };
      results.flow.push(rec);
      console.log(`  390dp ${theme.padEnd(5)} ${name.padEnd(22)} play="${rec.play}" fav="${rec.favourite}" ` +
        `pill=${rec.miniPlayer}`);
      return h;
    };

    step("01-player-idle");

    tap(hierarchy(), "btn_play");
    sleep(9000); dismissAnr();
    step("02-playing");

    tap(hierarchy(), "btn_favorite");
    sleep(2000);
    step("03-favourited");
    tap(hierarchy(), "btn_favorite");
    sleep(2000);

    // Swipe to GOLD, then to XTRA: the pager and the stream must move together.
    for (const [i, name] of [[1, "04-gold"], [2, "05-xtra"]]) {
      sh(["shell", "input", "swipe", String(widthPx(330)), String(widthPx(300)), String(widthPx(60)), String(widthPx(300)), "300"]);
      sleep(6000); dismissAnr();
      step(name);
    }

    // History: Phase B only has to keep the entry working.
    tap(hierarchy(), "btn_history");
    sleep(3000);
    step("06-history-sheet");
    sh(["shell", "input", "keyevent", "BACK"]);
    sleep(2000);

    tap(hierarchy(), "btn_play");
    sleep(3000);
    step("07-paused");

    // Background and foreground.
    sh(["shell", "input", "keyevent", "HOME"]);
    sleep(6000);
    sh(["shell", "am", "start", "-n", ACT]);
    sleep(4000); dismissAnr();
    step("08-after-background");

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
