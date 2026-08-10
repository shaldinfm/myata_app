/*
 * Validates the four canonical BottomNav icons on a real device.
 *
 *   node tools/qa/phone/verify-nav-icons.mjs
 *
 * The icons were extracted from the frozen Figma file rather than redrawn, so
 * what needs proving here is that they ship at the frozen size and that the
 * existing runtime tint still drives active and inactive correctly. Both are
 * measured, not eyeballed:
 *
 *   - each icon view's bounds match the frozen size (Home 16x18, rest 20x20);
 *   - the dominant colour inside each icon's box is the expected one for its
 *     state - #00723D active, #42474E light-inactive, #B3C4D1 dark-inactive;
 *   - every destination is visited, so all four active states are exercised;
 *   - nav item centres are re-measured against the frozen 390dp reference, so
 *     an icon change cannot silently move the bar.
 *
 * Runs at 320/360/390/412dp in Light and Dark. Restores display size and night
 * mode afterwards.
 */
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { decodePng, pixel, dist, hexToRgb } from "../lib/png.mjs";

const here = path.dirname(fileURLToPath(import.meta.url));
const OUT = path.join(here, "nav-icons");
fs.mkdirSync(OUT, { recursive: true });

const SERIAL = process.env.PHONE_SERIAL || "emulator-5556";
const ADB = process.env.ADB || "adb";
const PKG = "dlinemedia.radioplayer.myata";
const ACT = `${PKG}/com.example.musicplayerapp.MainActivity`;
const DENSITY = 420;

const sh = (a) => execFileSync(ADB, ["-s", SERIAL, ...a], { encoding: "utf8", maxBuffer: 64 * 1024 * 1024 });
const shBin = (a) => execFileSync(ADB, ["-s", SERIAL, ...a], { encoding: "buffer", maxBuffer: 64 * 1024 * 1024 });
const sleep = (ms) => execFileSync(process.execPath, ["-e", `setTimeout(()=>{},${ms})`], { timeout: ms + 5000 });
const toPx = (dp) => Math.round((dp * DENSITY) / 160);
const toDp = (px) => (px * 160) / DENSITY;

// Frozen canonical: icon sizes, item order, and 390dp item centres.
const ICONS = [
  { key: "home",       item: "nav_item_home",      view: "home_btn",      w: 16, h: 18, centre390: 63.0 },
  { key: "player",     item: "nav_item_player",    view: "player_btn",    w: 20, h: 20, centre390: 151.0 },
  { key: "collection", item: "nav_item_favorites", view: "favorites_btn", w: 20, h: 20, centre390: 246.5 },
  { key: "about",      item: "nav_item_info",      view: "info_btn",      w: 20, h: 20, centre390: 340.0 },
];

const ACTIVE = "#00723D";
const INACTIVE = { light: "#42474E", dark: "#B3C4D1" };
// Surfaces to ignore when looking for the icon's own colour.
const SURFACES = { light: ["#EDEEEF", "#FFCCFF", "#F8F9FA"], dark: ["#142D47", "#FFCCFF", "#0F253E"] };

function dump() {
  for (let i = 0; i < 5; i++) {
    try {
      sh(["shell", "uiautomator", "dump", "/sdcard/ui.xml"]);
      const xml = sh(["shell", "cat", "/sdcard/ui.xml"]);
      if (xml.includes("<node")) return xml;
    } catch {}
    sleep(700);
  }
  return "";
}

function nodes(xml) {
  const out = {};
  for (const m of xml.matchAll(/<node\b([^>]*?)\/?>/g)) {
    const a = Object.fromEntries([...m[1].matchAll(/([\w-]+)="([^"]*)"/g)].map((x) => [x[1], x[2]]));
    const id = (a["resource-id"] || "").replace(PKG + ":id/", "");
    if (!id) continue;
    const b = /\[(\d+),(\d+)\]\[(\d+),(\d+)\]/.exec(a.bounds || "");
    if (b) out[id] = { x: +b[1], y: +b[2], x2: +b[3], y2: +b[4] };
  }
  return out;
}

/*
 * The icon's own colour, as rendered. Anti-aliasing spreads the edge across many
 * shades, so this takes the most frequent colour that is not one of the bar
 * surfaces - which is the icon's solid interior.
 */
function dominantColour(img, box, theme) {
  const skip = SURFACES[theme].map(hexToRgb);
  const hist = new Map();
  for (let y = box.y; y < box.y2; y++) {
    for (let x = box.x; x < box.x2; x++) {
      if (x < 0 || y < 0 || x >= img.w || y >= img.h) continue;
      const p = pixel(img, x, y);
      if (skip.some((s) => dist(p, s) < 26)) continue;
      const k = `${p[0]},${p[1]},${p[2]}`;
      hist.set(k, (hist.get(k) || 0) + 1);
    }
  }
  let best = null, bestN = 0;
  for (const [k, n] of hist) if (n > bestN) { bestN = n; best = k; }
  if (!best) return { rgb: null, count: 0 };
  return { rgb: best.split(",").map(Number), count: bestN };
}

try { sh(["shell", "pm", "grant", PKG, "android.permission.POST_NOTIFICATIONS"]); } catch {}

const failures = [];
const results = [];
const fail = (m) => { failures.push(m); console.log("     FAIL  " + m); };

try {
  for (const width of [320, 360, 390, 412]) {
    sh(["shell", "wm", "size", `${toPx(width)}x2400`]);
    sleep(1400);
    for (const theme of ["light", "dark"]) {
      const ctx = `${width}dp-${theme}`;
      console.log(`\n  ${ctx}`);
      sh(["shell", "cmd", "uimode", "night", theme === "dark" ? "yes" : "no"]);
      sleep(1200);
      sh(["shell", "am", "force-stop", PKG]);
      sleep(600);
      sh(["shell", "am", "start", "-n", ACT]);
      sleep(6500);
      sleep(3500);

      for (const active of ICONS) {
        const ui = nodes(dump());
        const target = ui[active.item];
        if (!target) { fail(`${ctx}: ${active.item} not found`); continue; }
        sh(["shell", "input", "tap", String(Math.round((target.x + target.x2) / 2)), String(Math.round((target.y + target.y2) / 2))]);
        sleep(2600);

        const shot = path.join(OUT, `${ctx}-active-${active.key}.png`);
        fs.writeFileSync(shot, shBin(["exec-out", "screencap", "-p"]));
        const img = decodePng(shot);
        const ui2 = nodes(dump());

        for (const icon of ICONS) {
          const box = ui2[icon.view];
          if (!box) { fail(`${ctx}/active=${active.key}: ${icon.view} not found`); continue; }

          // size against the frozen icon
          const w = +toDp(box.x2 - box.x).toFixed(2);
          const h = +toDp(box.y2 - box.y).toFixed(2);
          if (Math.abs(w - icon.w) > 1 || Math.abs(h - icon.h) > 1) {
            fail(`${ctx}/active=${active.key}: ${icon.key} is ${w}x${h}dp, frozen is ${icon.w}x${icon.h}dp`);
          }

          // colour against the expected state
          const expectHex = icon.key === active.key ? ACTIVE : INACTIVE[theme];
          const expect = hexToRgb(expectHex);
          const got = dominantColour(img, box, theme);
          if (!got.rgb) {
            fail(`${ctx}/active=${active.key}: ${icon.key} has no icon pixels - drawable missing?`);
          } else if (dist(got.rgb, expect) > 20) {
            fail(`${ctx}/active=${active.key}: ${icon.key} is rgb(${got.rgb}) expected ${expectHex}`);
          }

          if (width === 390 && icon.key === active.key) {
            const item = ui2[icon.item];
            if (item) {
              const centre = +toDp((item.x + item.x2) / 2).toFixed(2);
              const delta = +(centre - icon.centre390).toFixed(2);
              if (Math.abs(delta) > 1.5) fail(`${ctx}: ${icon.key} centre ${centre}dp vs frozen ${icon.centre390}dp (${delta}dp)`);
              results.push({ width, theme, icon: icon.key, centre, frozen: icon.centre390, delta });
            }
          }
        }
        console.log(`     active=${active.key.padEnd(11)} four icons checked`);
      }
    }
  }
} finally {
  try { sh(["shell", "wm", "size", "reset"]); } catch {}
  try { sh(["shell", "cmd", "uimode", "night", "no"]); } catch {}
}

console.log("\n  390dp active-item centres vs frozen");
for (const r of results.filter((r) => r.theme === "light")) {
  console.log(`    ${r.icon.padEnd(11)} ${String(r.centre).padStart(7)}dp  frozen ${String(r.frozen).padStart(6)}dp  delta ${r.delta > 0 ? "+" : ""}${r.delta}dp`);
}

fs.writeFileSync(path.join(OUT, "metadata.json"), JSON.stringify({ results, failures }, null, 2) + "\n");
console.log(`\n  ${failures.length === 0 ? "PASS - all four canonical icons correct in every state" : `FAIL - ${failures.length} problem(s)`}`);
for (const f of failures) console.log("    " + f);
console.log(`  -> ${path.relative(process.cwd(), OUT)}`);
process.exit(failures.length === 0 ? 0 : 1);
