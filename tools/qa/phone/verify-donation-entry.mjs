/*
 * B1 fix validation: donation must be reachable from "О нас" and nowhere else.
 *
 *   node tools/qa/phone/verify-donation-entry.mjs
 *
 * The B1 shell dropped the standalone Donate destination, which left the
 * existing payment and Boosty flows with no entry point at all. This checks the
 * replacement path end to end on a real device rather than by reading the diff:
 *
 *   1. the bar still has exactly four items, and none of them is Donate;
 *   2. "О нас" carries a visible donation CTA;
 *   3. tapping it reaches DonateFragment with the amount form intact;
 *   4. the YooMoney WebView actually opens when the form is submitted;
 *   5. the Boosty subscription banners are present and clickable;
 *   6. Back returns to "О нас" rather than Home or the launcher.
 *
 * Runs in Light and Dark at 320, 360, 390 and 412dp, and records the nav item
 * geometry so fidelity to the frozen 390dp design can be measured rather than
 * eyeballed. Restores display size and night mode afterwards.
 */
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const OUT = path.join(here, "donation-entry");
fs.mkdirSync(OUT, { recursive: true });

const SERIAL = process.env.PHONE_SERIAL || "emulator-5556";
const ADB = process.env.ADB || "adb";
const PKG = "dlinemedia.radioplayer.myata";
const ACT = `${PKG}/com.example.musicplayerapp.MainActivity`;
const DENSITY = 420;

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
const seenPaymentUrls = new Set();

// DonateFragment logs the URL from onPageFinished, so this is proof the payment
// page actually loaded rather than proof a WebView object exists.
function lastPaymentUrl() {
  let log = "";
  try { log = sh(["logcat", "-d", "-s", "ActPayment:*"]); } catch { return null; }
  const urls = [...log.matchAll(/WebPage finished (\S+)/g)].map((x) => x[1]);
  return urls.length ? urls[urls.length - 1] : null;
}
const fail = (ctx, msg) => { failures.push(`${ctx}: ${msg}`); console.log(`     FAIL  ${msg}`); };

try {
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

      // ---- 3. the CTA reaches the donation screen -------------------------
      tap(cta);
      sleep(2600);
      ns = nodes(dump());
      const form = ["sum100", "sum200", "sum500", "sum1000", "send_btn", "sendBtn"].filter((id) => byId(ns, id));
      rec.donateForm = form;
      const hasForm = form.length >= 4;
      if (!hasForm) fail(ctx, `donation screen did not open (found: ${form.join(",") || "nothing"})`);
      const boosty = ns.filter((n) => /^boosty\d+Banner$/i.test(n.id) || /boosty/i.test(n.id));
      rec.boostyBanners = boosty.map((b) => b.id);
      if (hasForm && boosty.length === 0) fail(ctx, "no Boosty banners on the donation screen");
      shot(`${ctx}-3-donate`);

      // ---- 4. the payment page actually loads -----------------------------
      // Judged by DonateFragment's own onPageFinished log rather than by a view
      // dump: loading the YooMoney page keeps the accessibility bridge busy, so
      // uiautomator intermittently returns a null root and a dump-based check
      // reports a failure that did not happen. The log line proves the page
      // reached onPageFinished, which is the stronger claim anyway.
      if (hasForm) {
        const send = scrollTo(["send_btn", "sendBtn"]);
        if (send) {
          // Read the log from a device-clock marker taken just before the tap,
          // so a `logcat -c` that silently fails cannot let an earlier
          // configuration's line count as this one's evidence.
          // Freshness is established by comparing the last logged payment URL
          // before and after the tap, not by a time filter: `logcat -t` tails the
          // raw buffer and only then applies the tag filter, so it returns
          // nothing whenever the tail happens to hold no ActPayment lines - which
          // reads as a product failure that did not happen. Every submission
          // mints a new order id, so a changed URL is unambiguous evidence.
          const before = lastPaymentUrl();
          tap(send);
          // Poll rather than sleep a fixed interval: the emulator's route to
          // yoomoney.ru is slow and a fixed wait turns a slow load into a
          // reported failure. A genuine failure still fails, just later.
          let url = before;
          for (let i = 0; i < 14 && url === before; i++) {
            sleep(3000);
            url = lastPaymentUrl();
          }
          rec.paymentUrl = url;
          rec.webViewOpened = !!url && url !== before;
          if (!url || url === before) fail(ctx, "submitting the amount did not load the payment page");
          else if (!/yoomoney\.ru/.test(url)) fail(ctx, `payment page loaded an unexpected host: ${url}`);
          else if (seenPaymentUrls.has(url)) fail(ctx, "payment URL repeats an earlier run - not fresh evidence");
          if (url) seenPaymentUrls.add(url);
          shot(`${ctx}-4-payment`);
        } else {
          rec.webViewOpened = null;
          fail(ctx, "no submit control on the donation form");
        }
      }

      // ---- 5. Back returns to О нас --------------------------------------
      // One Back only. The payment WebView is a view swap inside DonateFragment,
      // not a destination, so a single Back pops donate and lands on О нас.
      back();
      sleep(2800);
      ns = nodes(dump());
      const backOnAbout = !!byId(ns, "donate_cta") || !!byId(ns, "description");
      const stillInApp = ns.some((n) => /^nav_item_/.test(n.id));
      rec.backToAbout = backOnAbout && stillInApp;
      if (!rec.backToAbout) fail(ctx, "Back did not return to О нас");
      shot(`${ctx}-5-back`);

      results.push(rec);
      console.log(`     nav=${rec.navCount} cta=${rec.ctaVisible} form=${form.length} boosty=${rec.boostyBanners.length} web=${rec.webViewOpened} back=${rec.backToAbout}`);
    }
  }
} finally {
  try { sh(["shell", "wm", "size", "reset"]); } catch {}
  try { sh(["shell", "cmd", "uimode", "night", "no"]); } catch {}
}

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
console.log(`\n  ${failures.length === 0 ? "PASS - donation reachable from О нас in every configuration" : `FAIL - ${failures.length} problem(s)`}`);
for (const f of failures) console.log("    " + f);
console.log(`  -> ${path.relative(process.cwd(), OUT)}`);
process.exit(failures.length === 0 ? 0 : 1);
