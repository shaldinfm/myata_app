/*
 * Donation and external-link contract for "О нас", checked on a real device.
 *
 *   node tools/qa/phone/verify-donation-entry.mjs
 *
 * The app no longer runs a donation screen. "Поддержать эфир" hands the whole
 * payment to YooMoney, which is where the amount is typed, and the social tiles
 * are plain external links. So what is worth checking is no longer "does our
 * form submit" but "does each control leave the app at the right URL, and does
 * the retired flow stay retired":
 *
 *   1. the bar still has exactly four items, and none of them is Donate;
 *   2. "О нас" carries a visible donation CTA;
 *   3. tapping it opens https://yoomoney.ru/to/410015757768507 outside the app;
 *   4. DonateFragment does not open, and the legacy ActPayment WebView flow is
 *      never entered;
 *   5. the Boosty tile still opens https://boosty.to/myata;
 *   6. the Я.Музыка tile opens https://links.radiomyata.ru/playlists/;
 *   7. Back returns to "О нас" rather than Home or the launcher.
 *
 * 1, 2 and 7 are layout-sensitive, so they run in Light and Dark at 320, 360,
 * 390 and 412dp, recording nav geometry against the frozen 390dp design. 3, 5
 * and 6 are not: an Intent destination does not vary with width or night mode,
 * and each one costs a browser launch. They run once, at the end, on the
 * restored display. Restores display size and night mode afterwards.
 */
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const OUT = path.join(here, "donation-entry");
fs.mkdirSync(OUT, { recursive: true });

const ADB = process.env.ADB || "adb";

// The old default named one emulator and failed confusingly on any other. Fall
// back to the only attached device when PHONE_SERIAL is not set.
function defaultSerial() {
  const out = execFileSync(ADB, ["devices"], { encoding: "utf8" });
  const devices = out.split("\n").slice(1).map((l) => l.trim().split(/\s+/)).filter((p) => p[1] === "device").map((p) => p[0]);
  if (devices.length === 1) return devices[0];
  if (devices.length === 0) throw new Error("no device attached; start an emulator or set PHONE_SERIAL");
  throw new Error(`${devices.length} devices attached (${devices.join(", ")}); set PHONE_SERIAL`);
}

const SERIAL = process.env.PHONE_SERIAL || defaultSerial();
const PKG = "dlinemedia.radioplayer.myata";
const ACT = `${PKG}/com.example.musicplayerapp.MainActivity`;
const DENSITY = 420;

// The contract. Every one of these is asserted in full - host and path - not by
// host alone, so a tile pointed at the right site but the wrong page fails.
const DONATE_URL = "https://yoomoney.ru/to/410015757768507";
const BOOSTY_URL = "https://boosty.to/myata";
const YANDEX_URL = "https://links.radiomyata.ru/playlists/";

const sh = (a) => execFileSync(ADB, ["-s", SERIAL, ...a], { encoding: "utf8", maxBuffer: 64 * 1024 * 1024 });
const shBin = (a) => execFileSync(ADB, ["-s", SERIAL, ...a], { encoding: "buffer", maxBuffer: 64 * 1024 * 1024 });
const sleep = (ms) => execFileSync(process.execPath, ["-e", `setTimeout(()=>{},${ms})`], { timeout: ms + 5000 });
const px = (dp) => Math.round((dp * DENSITY) / 160);
const dp = (p) => (p * 160) / DENSITY;

function dump() {
  for (let i = 0; i < 4; i++) {
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
  const out = [];
  for (const m of xml.matchAll(/<node\b([^>]*?)\/?>/g)) {
    const a = Object.fromEntries([...m[1].matchAll(/([\w-]+)="([^"]*)"/g)].map((x) => [x[1], x[2]]));
    const b = /\[(\d+),(\d+)\]\[(\d+),(\d+)\]/.exec(a.bounds || "");
    out.push({
      id: (a["resource-id"] || "").replace(PKG + ":id/", ""),
      cls: a.class || "",
      text: a.text || "",
      desc: a["content-desc"] || "",
      clickable: a.clickable === "true",
      box: b ? { x: +b[1], y: +b[2], x2: +b[3], y2: +b[4] } : null,
    });
  }
  return out;
}

const byId = (ns, id) => ns.find((n) => n.id === id);
const tap = (n) => sh(["shell", "input", "tap", String(Math.round((n.box.x + n.box.x2) / 2)), String(Math.round((n.box.y + n.box.y2) / 2))]);
const back = () => sh(["shell", "input", "keyevent", "BACK"]);
const shot = (name) => fs.writeFileSync(path.join(OUT, name + ".png"), shBin(["exec-out", "screencap", "-p"]));

function dismissAnr() {
  for (let i = 0; i < 3; i++) {
    const xml = dump();
    if (!/isn't responding/i.test(xml)) return;
    const m = /text="Wait"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/.exec(xml);
    if (m) sh(["shell", "input", "tap", String((+m[1] + +m[3]) / 2), String((+m[2] + +m[4]) / 2)]);
    else back();
    sleep(900);
  }
}

// Content below the fold needs scrolling into view on shorter screens. Give up
// rather than loop forever if it never appears.
function scrollTo(ids) {
  const want = [].concat(ids);
  for (let i = 0; i < 7; i++) {
    const ns = nodes(dump());
    for (const id of want) {
      const n = byId(ns, id);
      if (n && n.box && n.box.y2 > 0) return n;
    }
    sh(["shell", "input", "swipe", "540", "1600", "540", "900", "300"]);
    sleep(700);
  }
  return null;
}

try { sh(["shell", "pm", "grant", PKG, "android.permission.POST_NOTIFICATIONS"]); } catch {}

const results = [];
const failures = [];
const fail = (ctx, msg) => { failures.push(`${ctx}: ${msg}`); console.log(`     FAIL  ${msg}`); };

/*
 * What URL did the app just hand out?
 *
 * Three places can say, and they are consulted in this order:
 *
 *   - `dumpsys activity activities`, which holds the resolved intent on both
 *     API 24 and API 36 and is the only one of the three that is full on both;
 *   - logcat, where modern platforms print `capturedLink=`;
 *   - the address bar of whatever browser came forward, read off the screen.
 *
 * Any one of them is proof. Taking the union rather than picking one keeps this
 * working across API levels that expose different subsets - API 24 pops an
 * "Open with" chooser and never reaches an address bar - instead of encoding one
 * platform's diagnostics as the contract.
 *
 * The one thing that must NOT count is the START line logcat prints, which
 * elides the path ("dat=https://yoomoney.ru/..."). Left in, it looks like a
 * successful read of the wrong URL and fails the run for a page it never
 * actually disagreed with, so truncated candidates are dropped rather than
 * compared.
 */
function findUrls(haystack) {
  return [...haystack.matchAll(/https?:\/\/[^\s"'<>\\)]+/g)]
    .map((m) => m[0])
    .filter((u) => !u.endsWith("..."))
    .map((u) => u.replace(/[.,;]+$/, ""));
}

function externalUrl(expected) {
  const host = new URL(expected).host;
  const sources = [];
  try { sources.push(["dumpsys", sh(["shell", "dumpsys", "activity", "activities"])]); } catch {}
  try { sources.push(["logcat", sh(["logcat", "-d"])]); } catch {}
  try {
    // The address bar is text like "yoomoney.ru/to/410015757768507" - scheme
    // stripped by the browser - so it is matched on host+path, not as a URL.
    const ui = nodes(dump()).map((n) => `${n.text} ${n.desc}`).join("\n");
    sources.push(["screen", ui]);
  } catch {}

  const want = expected.replace(/^https?:\/\//, "").replace(/\/$/, "");
  for (const [where, text] of sources) {
    if (where === "screen") {
      if (text.includes(want)) return { url: expected, where, exact: true };
      const near = text.split(/\s+/).find((w) => w.includes(host));
      if (near) return { url: near, where, exact: false };
      continue;
    }
    const hits = findUrls(text).filter((u) => u.includes(host));
    const exact = hits.find((u) => u.replace(/^https?:\/\//, "").replace(/\/$/, "") === want);
    if (exact) return { url: exact, where, exact: true };
    if (hits.length) return { url: hits[hits.length - 1], where, exact: false };
  }
  return { url: null, where: null, exact: false };
}

// The retired flow, by its own fingerprints: DonateFragment's amount form in the
// view tree, and the ActPayment tag its WebView logged from onPageFinished.
function legacyDonationTraces(ns) {
  const formIds = ["sum100", "sum200", "sum500", "sum1000", "sum0_group", "sum0_input", "sendBtn", "send_btn"];
  const onScreen = formIds.filter((id) => byId(ns, id));
  let webview = [];
  try { webview = [...sh(["logcat", "-d", "-s", "ActPayment:*"]).matchAll(/WebPage finished (\S+)/g)].map((x) => x[1]); } catch {}
  return { onScreen, webview };
}

// Opens `n`, waits for something outside the app to come forward, and reports
// the URL. Leaves the browser behind so the caller can press Back.
function followLink(ctx, label, n, expected, rec) {
  try { sh(["logcat", "-c"]); } catch {}
  tap(n);
  let found = { url: null };
  for (let i = 0; i < 12 && !found.url; i++) {
    sleep(2500);
    found = externalUrl(expected);
  }
  rec[label] = { url: found.url, evidence: found.where, exact: found.exact };
  if (!found.url) fail(ctx, `${label}: tapping it opened nothing that reached ${expected}`);
  else if (!found.exact) fail(ctx, `${label}: opened ${found.url}, expected ${expected}`);

  const traces = legacyDonationTraces(nodes(dump()));
  if (traces.onScreen.length) fail(ctx, `${label}: the retired donation form appeared (${traces.onScreen.join(",")})`);
  if (traces.webview.length) fail(ctx, `${label}: the legacy ActPayment WebView flow was entered (${traces.webview.join(" ")})`);
  rec[label].legacyForm = traces.onScreen;
  rec[label].legacyWebView = traces.webview;
  return found;
}

function openAbout() {
  sh(["shell", "am", "force-stop", PKG]);
  sleep(600);
  sh(["shell", "am", "start", "-n", ACT]);
  sleep(6500);
  dismissAnr();
  sleep(3500);
  dismissAnr();
  const info = byId(nodes(dump()), "nav_item_info");
  if (!info) return false;
  tap(info);
  sleep(2500);
  return true;
}

try {
  // ---- A. layout-sensitive checks, swept ---------------------------------
  for (const width of [320, 360, 390, 412]) {
    sh(["shell", "wm", "size", `${px(width)}x2400`]);
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
      dismissAnr();
      sleep(3500);
      dismissAnr();

      const rec = { width, theme };

      // ---- 1. the bar: exactly four items, no Donate ----------------------
      let ns = nodes(dump());
      const navIds = ["nav_item_home", "nav_item_player", "nav_item_favorites", "nav_item_info"];
      const items = ns.filter((n) => /^nav_item_/.test(n.id));
      rec.navCount = items.length;
      if (items.length !== 4) fail(ctx, `bar has ${items.length} items, expected 4`);
      if (ns.some((n) => /donate/i.test(n.id) && /^nav_/.test(n.id))) fail(ctx, "a Donate item is present in the bar");

      // geometry, in dp, for the frozen-design comparison
      rec.navGeometry = navIds.map((id) => {
        const n = byId(ns, id);
        if (!n || !n.box) return { id, missing: true };
        return {
          id,
          x: +dp(n.box.x).toFixed(2),
          w: +dp(n.box.x2 - n.box.x).toFixed(2),
          centre: +dp((n.box.x + n.box.x2) / 2).toFixed(2),
        };
      });
      const labels = ns.filter((n) => /_label$/.test(n.id)).map((n) => n.text);
      rec.labels = labels;
      if (labels.length !== 4) fail(ctx, `expected 4 nav labels, saw ${labels.length}: ${labels.join(", ")}`);

      // ---- 2. О нас carries a visible CTA ---------------------------------
      const info = byId(ns, "nav_item_info");
      if (!info) { fail(ctx, "no О нас item to tap"); results.push(rec); continue; }
      tap(info);
      sleep(2500);
      shot(`${ctx}-1-about`);

      const cta = scrollTo("donate_cta");
      rec.ctaVisible = !!cta;
      if (!cta) { fail(ctx, "no donation CTA on О нас - donation is unreachable"); results.push(rec); continue; }
      rec.ctaText = cta.text;
      rec.ctaClickable = cta.clickable;
      if (!cta.clickable) fail(ctx, "donation CTA is not clickable");
      if (cta.text !== "Поддержать эфир") fail(ctx, `CTA copy is "${cta.text}", expected "Поддержать эфир"`);
      shot(`${ctx}-2-cta`);

      // ---- 3. the tiles are present, and are the ones they claim to be ----
      // Identity as well as presence: the Я.Музыка destination changed and its
      // art and label did not, so a swapped tile has to fail here.
      const tiles = { boosty: "Boosty", yandex: "Яндекс Музыка", threads: "Threads" };
      rec.tiles = {};
      for (const [id, desc] of Object.entries(tiles)) {
        const t = scrollTo(id);
        rec.tiles[id] = t ? { desc: t.desc, clickable: t.clickable } : null;
        if (!t) fail(ctx, `no ${id} tile on О нас`);
        else if (t.desc !== desc) fail(ctx, `${id} tile reads "${t.desc}", expected "${desc}"`);
        else if (!t.clickable) fail(ctx, `${id} tile is not clickable`);
      }
      shot(`${ctx}-3-tiles`);

      results.push(rec);
      console.log(`     nav=${rec.navCount} cta=${rec.ctaVisible} tiles=${Object.values(rec.tiles).filter(Boolean).length}/3`);
    }
  }
} finally {
  try { sh(["shell", "wm", "size", "reset"]); } catch {}
  try { sh(["shell", "cmd", "uimode", "night", "no"]); } catch {}
}

// ---- B. where the three links actually go -------------------------------
// Once, on the restored display. Each tap leaves the app for a browser, so this
// is the slow half; running it per configuration would multiply the cost by
// eight to re-prove a destination that cannot vary by configuration.
sleep(1500);
const links = { ctx: "links" };
const ctx = "links";
console.log(`\n  ${ctx}`);
if (!openAbout()) {
  fail(ctx, "could not reach О нас");
} else {
  const cases = [
    ["donateCta", "donate_cta", DONATE_URL],
    ["boostyTile", "boosty", BOOSTY_URL],
    ["yandexTile", "yandex", YANDEX_URL],
  ];
  for (const [label, id, expected] of cases) {
    const n = scrollTo(id);
    if (!n) { fail(ctx, `${label}: control not found`); continue; }
    followLink(ctx, label, n, expected, links);
    shot(`links-${label}`);
    const got = links[label];
    console.log(`     ${label.padEnd(11)} -> ${got.url || "nothing"} ${got.exact ? "OK" : "MISMATCH"} (${got.evidence || "-"})`);

    // ---- 7. Back returns to О нас -------------------------------------
    back();
    sleep(3000);
    let ns = nodes(dump());
    if (!byId(ns, "donate_cta") && !ns.some((n) => /^nav_item_/.test(n.id))) {
      back();
      sleep(2500);
      ns = nodes(dump());
    }
    const backOnAbout = (!!byId(ns, "donate_cta") || !!byId(ns, "description")) && ns.some((n) => /^nav_item_/.test(n.id));
    links[label].backToAbout = backOnAbout;
    if (!backOnAbout) fail(ctx, `${label}: Back did not return to О нас`);
  }
}
results.push(links);

// Frozen 3.6.6 HOME > BottomNavBar, 390x76: content-sized items at fixed
// offsets. Centres are what a user reads as "the icons are in the right place".
const FROZEN_390 = { nav_item_home: 63.0, nav_item_player: 151.0, nav_item_favorites: 246.5, nav_item_info: 340.0 };
console.log("\n  390dp nav geometry vs frozen canonical");
for (const r of results.filter((r) => r.width === 390)) {
  for (const g of r.navGeometry || []) {
    if (g.missing) continue;
    const d = +(g.centre - FROZEN_390[g.id]).toFixed(2);
    console.log(`    ${r.theme.padEnd(5)} ${g.id.padEnd(19)} centre ${String(g.centre).padStart(7)}dp  frozen ${String(FROZEN_390[g.id]).padStart(6)}dp  delta ${d > 0 ? "+" : ""}${d}dp`);
  }
}

fs.writeFileSync(path.join(OUT, "metadata.json"), JSON.stringify({ results, failures }, null, 2) + "\n");
console.log(`\n  ${failures.length === 0 ? "PASS - О нас opens the right external destinations and the retired donation screen stays retired" : `FAIL - ${failures.length} problem(s)`}`);
for (const f of failures) console.log("    " + f);
console.log(`  -> ${path.relative(process.cwd(), OUT)}`);
process.exit(failures.length === 0 ? 0 : 1);
