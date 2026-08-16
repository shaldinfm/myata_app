/*
 * COLLECTION validation: the frozen re-skin in the running app.
 *
 *   node tools/qa/phone/capture-collection.mjs <api24|api36>
 *
 * CollectionLayoutTest already measures the frozen anchors, and measuring is the
 * stronger check. This covers what a measurement cannot reach:
 *
 *   the two states      empty and populated are driven by the database, so they
 *                       are reached here by adding and removing a favourite
 *                       through the real player, not by toggling a view;
 *   the export actions  they moved from two always-visible pills into the frozen
 *                       header overflow, so the menu has to open, carry both
 *                       rows, and reach the system file picker;
 *   the overflow gate   it is hidden while the collection is empty, which is what
 *                       the frozen empty frame does;
 *   the per-track sheet the FINAL row has one control, and the four service
 *                       actions and the removal it replaced are rows on the sheet
 *                       it opens - so the sheet has to open and carry all five;
 *   remove and undo     removal is the sheet's last row, and Отменить has to put
 *                       the same row back, not a fresh copy at the top;
 *   the Mini Player     COLLECTION is one of the screens the pill floats over, so
 *                       "hidden until a stream is started, visible afterwards and
 *                       while paused" is observable here;
 *   the clearance       the last row must be reachable and clear of the pill.
 *
 * Facts go to metadata.json; screenshots stay local (../.gitignore drops *.png)
 * because MainActivity picks one of ten window backgrounds at random per launch.
 */
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const RUN = (process.argv[2] || "").toLowerCase();
if (!/^api\d+$/.test(RUN)) { console.error("usage: capture-collection.mjs <api24|api36>"); process.exit(1); }

const here = path.dirname(fileURLToPath(import.meta.url));
const OUT = path.join(here, "collection", RUN);
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

const SDK = Number(sh(["shell", "getprop", "ro.build.version.sdk"]).trim());

// POST_NOTIFICATIONS only exists from API 33; asking for it below that makes pm
// print its whole help text over the run.
const grantNotifications = () => {
  if (SDK < 33) return;
  try { sh(["shell", "pm", "grant", PKG, "android.permission.POST_NOTIFICATIONS"]); } catch {}
};

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
    const out = { _texts: [], _bounds: [] };
    for (const m of xml.matchAll(/<node\b([^>]*?)\/?>/g)) {
      const a = Object.fromEntries([...m[1].matchAll(/([\w-]+)="([^"]*)"/g)].map((x) => [x[1], x[2]]));
      if (a.text) {
        out._texts.push(a.text);
        const bb = /\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]/.exec(a.bounds || "");
        if (bb) {
          out._bounds.push({
            text: a.text,
            cx: (Number(bb[1]) + Number(bb[3])) >> 1,
            cy: (Number(bb[2]) + Number(bb[4])) >> 1,
            top: { h: Number(bb[4]) - Number(bb[2]) },
          });
        }
      }
      const id = (a["resource-id"] || "").replace(PKG + ":id/", "");
      if (id && !out[id]) out[id] = { bounds: a.bounds, text: a.text || "", desc: a["content-desc"] || "" };
    }
    return out;
  } catch { return { _texts: [], _bounds: [] }; }
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

/**
 * Taps the node carrying [label].
 *
 * `last` picks the final match rather than the first. The API 24 document
 * picker draws its roots twice - once as the breadcrumb over the file list,
 * once as the drawer row that actually selects the root - and only the second
 * one does anything.
 */
function tapText(label, { last = false } = {}) {
  try {
    sh(["shell", "uiautomator", "dump", "/sdcard/ui.xml"]);
    const xml = sh(["shell", "cat", "/sdcard/ui.xml"]);
    const re = new RegExp(`text="${label}"[^>]*bounds="\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]"`, "g");
    const all = [...xml.matchAll(re)];
    const m = last ? all[all.length - 1] : all[0];
    if (!m) return false;
    sh(["shell", "input", "tap", String((+m[1] + +m[3]) >> 1), String((+m[2] + +m[4]) >> 1)]);
    return true;
  } catch { return false; }
}

/** Taps a point already known, with no dump of its own. See step 09. */
function tapAt(x, y) { sh(["shell", "input", "tap", String(x), String(y)]); }

/**
 * One-call hierarchy dump, for the steps that are racing something.
 *
 * `hierarchy()` writes /sdcard/ui.xml and then cats it back - two adb round
 * trips, which measured about two seconds on a busy emulator. That is most of a
 * Snackbar's 2750ms LENGTH_LONG before anything has been tapped. Dumping to
 * /dev/tty through exec-out is one trip. Not every image supports it, so this
 * falls back rather than failing.
 */
function fastDumpXml() {
  try {
    const out = sh(["exec-out", "uiautomator", "dump", "/dev/tty"]);
    if (/<hierarchy/.test(out)) return out;
  } catch {}
  sh(["shell", "uiautomator", "dump", "/sdcard/ui.xml"]);
  return sh(["shell", "cat", "/sdcard/ui.xml"]);
}

/** The centre of the first node carrying [label], case-insensitively. */
function centreOfText(xml, label) {
  for (const m of xml.matchAll(/text="([^"]*)"[^>]*bounds="\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]"/g)) {
    if (m[1].toLowerCase() === label.toLowerCase()) {
      return { text: m[1], cx: (+m[2] + +m[4]) >> 1, cy: (+m[3] + +m[5]) >> 1 };
    }
  }
  return null;
}

/**
 * The favorites table, straight out of Room. The app is debuggable, so `run-as`
 * reaches its private databases directory without root. Used by the undo step,
 * where "the row came back" is a weaker claim than "the same row came back".
 */
function readFavorites() {
  try {
    const out = sh(["shell",
      `run-as ${PKG} sqlite3 databases/myata_database "SELECT id||'|'||artist||'|'||track||'|'||stream||'|'||addedAt FROM favorites ORDER BY addedAt DESC;"`]);
    return out.split(/\r?\n/).filter(Boolean).map((l) => {
      const [id, artist, track, stream, addedAt] = l.split("|");
      return { id: Number(id), artist, track, stream, addedAt: Number(addedAt) };
    });
  } catch { return []; }
}

function tapDesc(desc) {
  try {
    sh(["shell", "uiautomator", "dump", "/sdcard/ui.xml"]);
    const xml = sh(["shell", "cat", "/sdcard/ui.xml"]);
    const re = new RegExp(`content-desc="${desc}"[^>]*bounds="\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]"`);
    const m = re.exec(xml);
    if (!m) return false;
    sh(["shell", "input", "tap", String((+m[1] + +m[3]) >> 1), String((+m[2] + +m[4]) >> 1)]);
    return true;
  } catch { return false; }
}

/**
 * Completes a save in the system document picker.
 *
 * Two pickers, two flows. API 36 opens on a writable folder and SAVE works
 * straight away. API 24 opens on "Recent", where SAVE is drawn but disabled -
 * there is nowhere to write - so a root has to be chosen from the drawer first.
 * The direct attempt is made first and the drawer used only if nothing landed,
 * which keeps one code path honest on both images.
 */
function saveInPicker(ext) {
  const SAVE = ["Save", "SAVE", "Сохранить", "СОХРАНИТЬ"];
  let saved = SAVE.some((l) => tapText(l));
  sleep(3500);
  dismissAnr();
  let r = saveResult(ext, saved);
  if (r.path) return r;

  // Dismiss the IME first: it covers the drawer list, and on this picker the
  // filename field takes focus on entry.
  if (/mInputShown=true/.test(sh(["shell", "dumpsys", "input_method"]))) {
    sh(["shell", "input", "keyevent", "BACK"]);
    sleep(1000);
  }
  // The drawer handle is the toolbar's leading icon. Its content-desc differs
  // between the two DocumentsUI versions, so the label is tried first and the
  // icon's position - the leading edge of the toolbar that holds the title -
  // used as the fallback.
  if (!["Show roots", "Open navigation drawer", "Show roots and devices"].some((d) => tapDesc(d))) {
    const bar = hierarchy()._bounds.find((b) => b.text === "Recent" || b.text === "Recent files");
    if (bar) sh(["shell", "input", "tap", String(Math.round(bar.top.h / 2)), String(bar.cy)]);
  }
  sleep(1800);
  ["Downloads", "Download", "Загрузки"].some((l) => tapText(l, { last: true }));
  sleep(2200);
  saved = SAVE.some((l) => tapText(l));
  sleep(3500);
  dismissAnr();
  return saveResult(ext, saved);
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


/**
 * Reads an exported file back off the device.
 *
 * The row count is the point: one line per favourite for TXT, and one header
 * plus one line per favourite for CSV. The BOM and the CRLF endings are checked
 * because they are what makes the file open correctly in Excel on Windows, and
 * they are the part of the export this migration must not have disturbed.
 */
function saveResult(ext, saved) {
  if (!saved) return { saved: false, path: null, rows: null };
  const NEWLINE = new RegExp("\r?\n");
  const CRLF = String.fromCharCode(13, 10);
  let found = "";
  try {
    found = sh(["shell", "find", "/sdcard", "-name", `myata_favorites*.${ext}`])
      .trim().split(NEWLINE).filter(Boolean).pop() || "";
  } catch { found = ""; }
  if (!found) {
    try {
      found = sh(["shell", "ls", "/sdcard/Download"]).split(NEWLINE)
        .map((l) => l.trim())
        .filter((l) => l.endsWith(`.${ext}`) && l.startsWith("myata_favorites"))
        .map((l) => `/sdcard/Download/${l}`).pop() || "";
    } catch { found = ""; }
  }
  if (!found) return { saved: true, path: null, rows: null };
  const b64 = sh(["shell", "base64", found]).replace(/\s+/g, "");
  const buf = Buffer.from(b64, "base64");
  const bom = buf.length >= 3 && buf[0] === 0xef && buf[1] === 0xbb && buf[2] === 0xbf;
  const text = buf.subarray(bom ? 3 : 0).toString("utf8");
  const rows = text.split(CRLF).filter((l) => l.length > 0);
  return {
    saved: true, path: found, bom,
    crlf: text.includes(CRLF),
    rows: rows.length,
    header: ext === "csv" ? rows[0] : undefined,
    first: rows[0],
  };
}

const shot = (n) => fs.writeFileSync(path.join(OUT, `${n}.png`), shBin(["exec-out", "screencap", "-p"]));

function launch({ wipe = false } = {}) {
  // force-stop takes the playback service with it, which is what makes the next
  // start a genuine cold launch with no session for the pill to find. `wipe`
  // also drops the Room database, so the empty state is genuinely empty rather
  // than whatever a previous run left behind.
  // pm clear also revokes the runtime permissions, and the app asks for
  // POST_NOTIFICATIONS on start - the dialog would sit over everything the run
  // is trying to read. Re-granting here is what keeps the wipe invisible.
  if (wipe) {
    sh(["shell", "pm", "clear", PKG]);
    sleep(1200);
    grantNotifications();
  }
  sh(["shell", "am", "force-stop", PKG]);
  sleep(700);
  sh(["shell", "am", "start", "-n", ACT]);
  sleep(7000); dismissAnr(); sleep(4000); dismissAnr();
}

const openCollection = () => { tap(hierarchy(), "nav_item_favorites"); sleep(2500); dismissAnr(); };

grantNotifications();

const results = {
  run: RUN, sdk: SDK, density: DENSITY,
  themeSwitch: THEME_SWITCH ? "cmd uimode" : "unavailable",
  widths: [], flow: [],
};

try {
  /* --- 1. every width and theme, empty collection, no session yet --- */
  for (const dp of [320, 360, 390, 412]) {
    sh(["shell", "wm", "size", `${widthPx(dp)}x${widthPx(dp) * 2}`]);
    sleep(1500);
    for (const theme of THEMES) {
      setNight(theme);
      launch({ wipe: true });
      openCollection();
      const h = hierarchy();
      shot(`${dp}dp-${theme}-collection-empty`);
      const row = {
        width: dp, theme,
        header: box(h.collection_header),
        title: box(h.title),
        subtitle: box(h.collection_subtitle),
        overflow: box(h.collection_overflow),
        emptyCard: box(h.empty_card),
        emptyTitle: box(h.empty_title),
        emptyBody: box(h.empty_body),
        miniPlayer: box(h.mini_player),
      };
      results.widths.push(row);
      console.log(`  ${dp}dp ${theme.padEnd(5)} header=${row.header ? row.header.h + "dp" : "?"} ` +
        `subtitle@${row.subtitle ? row.subtitle.y : "?"} card@${row.emptyCard ? row.emptyCard.y : "?"} ` +
        `${row.emptyCard ? row.emptyCard.w + "x" + row.emptyCard.h : ""} ` +
        `overflow=${row.overflow ? "PRESENT" : "hidden"} pill=${row.miniPlayer ? "PRESENT" : "none"}`);
    }
  }

  /* --- 2. at the design width: states, the pill gate and the export menu --- */
  sh(["shell", "wm", "size", `${widthPx(390)}x${widthPx(390) * 2}`]);
  sleep(1500);
  for (const theme of THEMES) {
    setNight(theme);
    launch({ wipe: true });

    const step = (name, extra = {}) => {
      const h = hierarchy();
      shot(`390dp-${theme}-${name}`);
      const rec = {
        theme, step: name,
        miniPlayer: box(h.mini_player),
        overflow: box(h.collection_overflow),
        emptyCard: box(h.empty_card),
        firstRow: box(h.tv_track),
        badge: box(h.badge_stream),
        services: box(h.music_services),
        delete: box(h.btn_delete),
        ...extra,
      };
      results.flow.push(rec);
      console.log(`  390dp ${theme.padEnd(5)} ${name.padEnd(28)} pill=${rec.miniPlayer ? "PRESENT" : "none"} ` +
        `overflow=${rec.overflow ? "PRESENT" : "hidden"} ` +
        `state=${rec.emptyCard ? "empty" : rec.firstRow ? "populated" : "?"}`);
      return h;
    };

    // Empty, and no session: the pill must not be up and the overflow must be
    // hidden, because there is nothing to export.
    openCollection();
    step("01-empty-no-session");

    // Start a stream from HOME. The pill appears, and COLLECTION keeps it.
    tap(hierarchy(), "nav_item_home");
    sleep(2000);
    tap(hierarchy(), "myata_stream_banner");
    sleep(9000);
    dismissAnr();
    openCollection();
    step("02-empty-with-pill");

    // Pausing must not take the pill down: a paused stream is still the user's.
    tap(hierarchy(), "mini_player_play_pause");
    sleep(2000);
    step("03-empty-pill-paused");

    // Favourite the current track from PLAYER, which is how the collection is
    // populated in the real app, then come back.
    tap(hierarchy(), "nav_item_player");
    sleep(3000);
    dismissAnr();
    tap(hierarchy(), "btn_favorite");
    sleep(1500);
    openCollection();
    const populated = step("04-populated");

    // The export actions, from the frozen header overflow.
    if (populated.collection_overflow) {
      tap(populated, "collection_overflow");
      sleep(1500);
      const menu = hierarchy();
      shot(`390dp-${theme}-05-overflow-menu`);
      results.flow.push({
        theme, step: "05-overflow-menu",
        rows: menu._texts.filter((t) => /^Экспорт в (TXT|CSV)$/.test(t)),
      });
      console.log(`  390dp ${theme.padEnd(5)} ${"05-overflow-menu".padEnd(28)} ` +
        `rows=${JSON.stringify(menu._texts.filter((t) => /^Экспорт/.test(t)))}`);

      // TXT reaches the system document picker, with the frozen filename.
      tapText("Экспорт в TXT");
      sleep(4000);
      const picker = hierarchy();
      shot(`390dp-${theme}-06-export-txt-picker`);
      results.flow.push({
        theme, step: "06-export-txt-picker",
        sawFilename: picker._texts.some((t) => /myata_favorites/.test(t)),
        texts: picker._texts.filter((t) => /myata_favorites|Сохранить|Save/.test(t)),
      });
      console.log(`  390dp ${theme.padEnd(5)} ${"06-export-txt-picker".padEnd(28)} ` +
        `filename=${results.flow.at(-1).sawFilename ? "myata_favorites.txt" : "NOT SEEN"}`);

      // Complete the save, then read the file back off the device. Reaching the
      // picker only proves the intent; this proves the bytes - the row count, the
      // UTF-8 BOM and the CRLF line endings the export has always written.
      sh(["shell", "rm", "-f", "/sdcard/Download/myata_favorites.txt"]);
      const file = saveInPicker("txt");
      results.flow.push({ theme, step: "06b-export-txt-file", ...file });
      console.log(`  390dp ${theme.padEnd(5)} ${"06b-export-txt-file".padEnd(28)} ` +
        `${file.path || "NOT WRITTEN"} rows=${file.rows} bom=${file.bom} crlf=${file.crlf}`);

      // And the same for CSV, whose formatting is the one with a header row.
      openCollection();
      tap(hierarchy(), "collection_overflow");
      sleep(1500);
      tapText("Экспорт в CSV");
      sleep(4000);
      sh(["shell", "rm", "-f", "/sdcard/Download/myata_favorites.csv"]);
      const csv = saveInPicker("csv");
      results.flow.push({ theme, step: "06c-export-csv-file", ...csv });
      console.log(`  390dp ${theme.padEnd(5)} ${"06c-export-csv-file".padEnd(28)} ` +
        `${csv.path || "NOT WRITTEN"} rows=${csv.rows} header=${csv.header}`);
    }

    // Back on COLLECTION whatever the picker left behind, so the last two steps
    // are measured on the screen they are about.
    for (let i = 0; i < 4 && !hierarchy().nav_item_favorites; i++) {
      sh(["shell", "input", "keyevent", "BACK"]);
      sleep(1200);
    }
    openCollection();

    // Scrolled to the end, the last row must clear the pill.
    sh(["shell", "input", "swipe", String(widthPx(195)), String(widthPx(500)), String(widthPx(195)), String(widthPx(200)), "400"]);
    sleep(1500);
    step("07-scrolled-to-end");

    // The FINAL row has one control and it opens the per-track sheet, which is
    // where the four service actions and the removal live now. The sheet has to
    // carry all five rows, in the owner-confirmed order.
    tap(hierarchy(), "btn_row_action");
    sleep(1800);
    const sheet = hierarchy();
    shot(`390dp-${theme}-08-track-sheet`);
    const SHEET_ROWS = ["Spotify", "Apple Music", "YouTube", "Яндекс Музыка", "Удалить из коллекции"];
    results.flow.push({
      theme, step: "08-track-sheet",
      rows: SHEET_ROWS.filter((r) => sheet._texts.includes(r)),
      missing: SHEET_ROWS.filter((r) => !sheet._texts.includes(r)),
    });
    console.log(`  390dp ${theme.padEnd(5)} ${"08-track-sheet".padEnd(28)} ` +
      `rows=${results.flow.at(-1).rows.length}/5 ` +
      `missing=${JSON.stringify(results.flow.at(-1).missing)}`);

    // Removal is the sheet's last row. It takes the screen back to the empty
    // state, the overflow goes with it, and the Snackbar offers Отменить.
    //
    // Everything this step needs comes out of ONE dump, and the tap is made
    // straight from that dump's bounds. A uiautomator dump costs about a second
    // and a half and a screencap about one, while LENGTH_LONG is 2750ms - so a
    // step that dumps, photographs and then dumps again to find the button is
    // reaching for the Snackbar a good two seconds after it has gone. The first
    // run of this did exactly that and reported the undo as broken when it was
    // the harness that was late. The picture is taken at step 11 instead, where
    // nothing has to be tapped in the window.
    // What the row is before it is removed, so the undo step can say whether the
    // SAME row came back rather than merely a row.
    const savedRow = readFavorites()[0];

    // One fast dump, then tap straight from its bounds. The elapsed time from
    // the removal to the tap is recorded next to the result: LENGTH_LONG is
    // 2750ms, so a miss has to be attributable to the harness being late rather
    // than left looking like a broken undo. The label is matched
    // case-insensitively - a Material button style can render it uppercased -
    // and the casing actually found is recorded, so a text transform cannot
    // pass silently either.
    tapText("Удалить из коллекции");
    // Timed from the removal itself, not from before it: tapText does its own
    // dump first, and counting that would overstate the elapsed time by seconds
    // and label a tap that landed comfortably inside the window as late.
    const t0 = Date.now();
    const xml = fastDumpXml();
    const undoNode = centreOfText(xml, "Отменить");
    if (undoNode) tapAt(undoNode.cx, undoNode.cy);
    const tapAtMs = Date.now() - t0;

    results.flow.push({
      theme, step: "09-removed-with-undo",
      sawSnackbar: /text="Трек удалён из коллекции"/.test(xml),
      sawUndo: Boolean(undoNode),
      undoCasing: undoNode && undoNode.text,
      emptyCard: /empty_card/.test(xml),
      overflowHidden: !/collection_overflow/.test(xml),
      tapAtMs, insideWindow: tapAtMs < 2750,
    });
    console.log(`  390dp ${theme.padEnd(5)} ${"09-removed-with-undo".padEnd(28)} ` +
      `snackbar=${results.flow.at(-1).sawSnackbar} undo=${JSON.stringify(results.flow.at(-1).undoCasing)} ` +
      `empty=${results.flow.at(-1).emptyCard} overflowHidden=${results.flow.at(-1).overflowHidden} ` +
      `tappedAt=${tapAtMs}ms ${results.flow.at(-1).insideWindow ? "(in window)" : "(LATE)"}`);

    sleep(2500);
    const undone = step("10-undone");
    // Read the restored row straight out of Room. The screen coming back is not
    // the claim being made - the claim is that it is the SAME row, and the field
    // that carries that is addedAt, because the list is ORDER BY addedAt DESC.
    // The autogenerated id is reassigned on re-insert and is recorded here so
    // that stays a measurement rather than a surprise.
    const restored = readFavorites();
    results.flow.push({
      theme, step: "10-undone-check",
      rowIsBack: Boolean(undone.collection_row || undone.tv_track),
      overflowBack: Boolean(undone.collection_overflow),
      addedAtPreserved: Boolean(savedRow && restored[0] && restored[0].addedAt === savedRow.addedAt),
      idBefore: savedRow && savedRow.id, idAfter: restored[0] && restored[0].id,
    });
    console.log(`  390dp ${theme.padEnd(5)} ${"10-undone-check".padEnd(28)} ` +
      `rowIsBack=${results.flow.at(-1).rowIsBack} overflowBack=${results.flow.at(-1).overflowBack} ` +
      `addedAtPreserved=${results.flow.at(-1).addedAtPreserved} ` +
      `id ${results.flow.at(-1).idBefore}->${results.flow.at(-1).idAfter}`);

    // Remove once more: this is where the Snackbar is photographed, because
    // nothing has to be tapped inside its window here. Then let it expire, so
    // the run ends on the empty state the next theme's pass expects.
    tap(hierarchy(), "btn_row_action");
    sleep(1500);
    tapText("Удалить из коллекции");
    sleep(500);
    shot(`390dp-${theme}-11-removed-with-undo`);
    sleep(6000);
    step("12-empty-again-after-remove");

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
