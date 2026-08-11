/*
 * Radio Myata - targeted repair: content CTA label size
 *
 * Repairs exactly one defect from the typography migration: content CTAs that
 * were shrunk to 19 or 20px instead of being grown at 22px, because the earlier
 * fit pass mistook a button's own wrapper for its width constraint.
 *
 * It is deliberately the narrowest tool that can do the job:
 *
 *   - only the two canonical pages are opened. The proposal pages are never
 *     touched, so the owner's manual one-line correction cannot be disturbed;
 *   - only text nodes the frozen classifier calls "button/CTA" are eligible.
 *     A compact action - the 21px kind - is skipped by role, and again by size;
 *   - only sizes 19 and 20 are repaired. 21px and 22px are left alone, so
 *     running twice changes nothing the second time;
 *   - size only ever goes UP, to exactly 22. Nothing is ever shrunk;
 *   - family, weight, text, lineHeight, letterSpacing, fills, effects and every
 *     other property are never written.
 *
 * Geometry grows only when the larger label needs it, and only by releasing a
 * fixed wrapper to hug. No page is created or cloned.
 */

figma.showUI(__html__, { width: 540, height: 660 });

// Only these two pages. Proposals are intentionally absent.
var TARGET_PAGES = [
  { id: "2388:366", name: "CURRENT ANDROID UI - LIGHT" },
  { id: "2436:531", name: "CURRENT ANDROID UI — DARK" },
];

var CONTRACT_SIZE = 22;          // what a content CTA must be
var REPAIRABLE = [19, 20];       // the only sizes this tool will change
var ELIGIBLE_ROLE = "button/CTA";

/*
 * ---------------------------------------------------------------------------
 * GENERATED - do not edit here.
 *
 * Lifted verbatim from tools/figma-export/font-trial/code.js by build.mjs, so
 * the migration can only ever apply the classifier that was approved visually.
 * Change the rules there and re-run the build.
 * ---------------------------------------------------------------------------
 */
var MONTSERRAT = "Montserrat";
var ONEST = "Onest";

// Ordered. First match wins, so container rules that must beat the generic
// heading/button rules come first.
var RULES = [
  // --- utility containers, highest precedence -----------------------------
  {
    role: "navigation",
    family: ONEST,
    why: "BottomNav - owner: looks better in Onest",
    test: function (ctx) { return /bottomnav/i.test(ctx.path); },
  },
  {
    role: "mini-player",
    family: ONEST,
    why: "mini-player - owner: looks better in Onest",
    test: function (ctx) { return /mini\s*player/i.test(ctx.path); },
  },

  // --- expressive roles ------------------------------------------------
  // Container-specific rules come BEFORE the generic heading/button ones.
  // The Player title and the Collection titles ARE Heading nodes, so a
  // generic heading rule would match first and report them as headings.
  // Same family either way, but the role name is what the owner reviews.
{
    role: "player track metadata",
    family: MONTSERRAT,
    why: "full Player now-playing - owner: looks better in Montserrat",
    test: function (ctx) { return /track\s*info/i.test(ctx.path); },
  },
{
    role: "history timestamp",
    family: ONEST,
    // Kept as its own role for reporting, though it now resolves to the same
    // family as the rest of the row: once track-list content moved to Onest the
    // history row stopped mixing families, so no reader sees a seam here.
    why: "supporting label inside a history row - Onest, as is the rest of the row",
    test: function (ctx) { return /history\s*item/i.test(ctx.path) && /^\s*\d{1,2}:\d{2}\s*$/.test(ctx.text); },
  },
{
    role: "history track metadata",
    // Owner-revised: track-list content reads better in Onest. The whole
    // Broadcast History row is now one family - title, artist and timestamp -
    // so the row no longer mixes families at all.
    family: ONEST,
    why: "Broadcast History track list - owner: Onest for track-list content",
    test: function (ctx) { return /history\s*item/i.test(ctx.path); },
  },
{
    role: "collection track title",
    // Owner-revised: Onest, matching the artist line beneath it. Hierarchy is
    // carried by size and weight, which are untouched, not by family.
    family: ONEST,
    why: "Collection song title - owner: Onest for track-list content",
    // Collection titles are Heading 3 nodes, so this rule MUST stay ahead of the
    // generic heading rule - otherwise they would follow the heading family.
    // Equally it must stay scoped to Track Item, so the screen heading
    // "Моя коллекция" is never caught here.
    test: function (ctx) { return /track\s*item/i.test(ctx.path) && ctx.size >= 16; },
  },
{
    role: "collection secondary",
    family: ONEST,
    why: "artist / helper line beside a Collection title - owner asked to test Onest here",
    test: function (ctx) { return /track\s*item/i.test(ctx.path); },
  },
  {
    role: "card heading",
    family: MONTSERRAT,
    // Owner: these read better at Medium than at Regular. Structural, not
    // text-matched - Heading 3 at 24px is exactly the two card headings in
    // ABOUT US. The other Heading 3 nodes are 16px (Collection titles and the
    // empty-state line) and are untouched.
    weight: "Medium",
    why: "card heading - owner: Medium 500 rather than Regular",
    test: function (ctx) { return /(^|>\s*)heading\s*3/i.test(ctx.path) && ctx.size === 24; },
  },
{
    role: "heading",
    family: MONTSERRAT,
    why: "screen or section heading",
    // Only real Heading nodes. A 14px list caption in Settings is chrome, not a
    // section heading, and is handled by the utility default below.
    test: function (ctx) { return /(^|>\s*)heading\s*\d*/i.test(ctx.path) || /^heading\s*\d*$/i.test(ctx.name); },
  },
  {
    role: "compact action button",
    family: MONTSERRAT,
    weight: "Medium",
    size: 21,
    /*
     * A compact action is a dialog, bottom-sheet or utility-screen button -
     * Установить, Отмена, Отправить and their kind. They sit in dense surfaces
     * where 22px crowds the button, so they take 21px.
     *
     * The larger content CTAs are deliberately excluded: Поддержать эфир,
     * Экспортировать список, Показать ещё and the rest live on content screens
     * and already read correctly at 22px. Surface is what separates them, not
     * label length - which is why this keys off the sheet or the utility frame
     * rather than off the text.
     *
     * Only 22px labels are touched, so the small 12px chips keep their size.
     */
    why: "compact dialog / bottom-sheet / utility action - Montserrat Medium 21px",
    test: function (ctx) {
      var inSheet = /bottom\s*sheet|dialog|modal/i.test(ctx.path);
      var utilityFrame = /^(settings|profile|auth|report|sleep-timer|history-(empty|error)|find-track|collection-(track-sheet|overflow))/i.test(ctx.frame);
      var isButton = /(^|>\s*)button/i.test(ctx.path) || /^button/i.test(ctx.name);
      return isButton && (inSheet || utilityFrame) && ctx.size === 22;
    },
  },
{
    role: "button/CTA",
    family: MONTSERRAT,
    // Action labels carry more presence at Medium than at Regular, and the
    // frozen scale sets them Regular only because that is Muller's habit.
    weight: "Medium",
    why: "button or call to action - Montserrat Medium 500",
    test: function (ctx) { return /(^|>\s*)button/i.test(ctx.path) || /^button/i.test(ctx.name); },
  },
  // --- flagged judgement calls --------------------------------------------
  {
    role: "player transport label",
    family: ONEST,
    // Owner-resolved: Onest. A transport control is UI, not content, even though
    // it sits inside the expressive Player.
    why: "playback control - navigation/utility UI, not content - owner-resolved",
    test: function (ctx) { return /player\s*section/i.test(ctx.path) && /^(PAUSE|PLAY|STOP)$/i.test(String(ctx.text).trim()); },
  },
  {
    role: "inline action / CTA link",
    family: MONTSERRAT,
    weight: "Medium",
    // Owner-resolved: Montserrat. The split the owner asked for is between a
    // small actionable affordance - which is action typography, like a button -
    // and a link that is really part of the reading experience.
    //
    // A hyperlink set INSIDE a paragraph never reaches this rule: it is a styled
    // range within a body text node, so it inherits that node's Onest. Only a
    // standalone Link node is classified here, and length is what separates a
    // terse affordance ("все >") from link text that reads as body copy.
    why: "small standalone actionable link - action typography - owner-resolved",
    test: function (ctx) {
      return (/(^|>\s*)link/i.test(ctx.path) || /^link$/i.test(ctx.name)) &&
             String(ctx.text).trim().length <= 24;
    },
  },
  {
    role: "body link",
    family: ONEST,
    why: "link long enough to read as body copy - reading typography",
    test: function (ctx) { return /(^|>\s*)link/i.test(ctx.path) || /^link$/i.test(ctx.name); },
  },
  {
    role: "settings group caption",
    family: ONEST,
    // Owner-resolved: Onest. A list grouping caption is supporting UI, not an
    // expressive section heading, even though it reads like one.
    why: "list grouping caption - supporting UI, not an expressive heading - owner-resolved",
    test: function (ctx) {
      // A direct child of the frame is a grouping caption; anything nested is a
      // row label. Depth 2 = "<frame> > <node>".
      var depth = String(ctx.path).split(">").length;
      return /^(settings|profile|auth|report|sleep-timer)/i.test(ctx.frame) &&
             ctx.size <= 14 && depth === 2 &&
             /^[А-ЯЁA-Z][а-яёa-z\s.]+$/.test(String(ctx.text).trim());
    },
  },
];

var DEFAULT_RULE = {
  role: "body / helper",
  family: ONEST,
  why: "body, paragraph, form or secondary text - owner: Onest for reading",
};

/*
 * ctx = { name, path, text, size, frame }
 *   path  - " > "-joined ancestor names, frame first, node last
 *   frame - the top-level frame name
 */
function classify(ctx) {
  for (var i = 0; i < RULES.length; i++) {
    var r = RULES[i];
    try {
      if (r.test(ctx)) return { role: r.role, family: r.family, weight: r.weight || null, size: r.size || null, why: r.why, ambiguous: r.ambiguous || null };
    } catch (e) { /* a rule must never break the run */ }
  }
  return { role: DEFAULT_RULE.role, family: DEFAULT_RULE.family, weight: null, size: null, why: DEFAULT_RULE.why, ambiguous: null };
}

// Test hook: the mock harness imports this file and calls the classifier
// directly, so the rules can be unit-checked without a running Figma.

/* ------------------------------------------------------------- helpers -- */

function log(text) { figma.ui.postMessage({ type: "status", text: text }); }

function safe(node, prop, fallback) {
  try { var v = node[prop]; return v === undefined ? fallback : v; }
  catch (e) { return fallback; }
}

function trySet(node, prop, value) {
  try { node[prop] = value; return true; } catch (e) { return false; }
}

function round(n) { return n == null ? null : Math.round(n * 10) / 10; }

/*
 * The nearest ancestor that genuinely constrains width.
 *
 * A button's own wrapper is sized to the label it currently holds, so treating
 * it as the constraint is circular - that is precisely the bug this tool exists
 * to repair. Wrappers named after the button are skipped.
 */
function constrainingAncestor(node) {
  var cur = node, up = 0;
  try {
    while (cur && up < 6) {
      cur = cur.parent; up++;
      if (!cur || !cur.width) continue;
      if (/^button/i.test(safe(cur, "name", ""))) continue;
      var mode = safe(cur, "layoutMode", "NONE");
      if (mode === "VERTICAL" || mode === "HORIZONTAL") return cur;
    }
  } catch (e) {}
  return null;
}

function innerWidth(frame) {
  var w = safe(frame, "width", null);
  if (w == null) return null;
  return w - (safe(frame, "paddingLeft", 0) || 0) - (safe(frame, "paddingRight", 0) || 0);
}

// Text nodes with their ancestor path and nearest Button ancestor.
function collectText(node, out, path, chain) {
  var type = safe(node, "type", "?");
  var name = safe(node, "name", "");
  var here = path.concat(name);
  var ancestors = chain.concat([node]);
  if (type === "TEXT") {
    var btn = null;
    for (var a = ancestors.length - 2; a >= 0; a--) {
      if (/^button/i.test(safe(ancestors[a], "name", ""))) { btn = ancestors[a]; break; }
    }
    out.push({ node: node, path: here.join(" > "), name: name, button: btn });
    return out;
  }
  var kids = safe(node, "children", null);
  if (kids) for (var i = 0; i < kids.length; i++) collectText(kids[i], out, here, ancestors);
  return out;
}

/* ---------------------------------------------------------------- main -- */

async function run(msg) {
  var apply = msg.mode === "apply";
  var planned = [], skipped = [], problems = [];

  log("Loading pages…");
  if (typeof figma.loadAllPagesAsync === "function") await figma.loadAllPagesAsync();

  var allPages = figma.root.children;

  for (var t = 0; t < TARGET_PAGES.length; t++) {
    var want = TARGET_PAGES[t];
    var page = null;
    for (var p = 0; p < allPages.length; p++) {
      if (safe(allPages[p], "id", null) === want.id) { page = allPages[p]; break; }
    }
    if (!page) {
      problems.push({ node: want.name, property: "page", error: "not found by id " + want.id + " - skipped" });
      continue;
    }
    // A clone would have a different id, so an id match already rules that out;
    // this is belt and braces against a renamed original.
    if (/\(pre-typography\)/i.test(safe(page, "name", ""))) {
      problems.push({ node: want.name, property: "page", error: "resolved to a (pre-typography) clone - refusing" });
      continue;
    }

    log((apply ? "Repairing " : "Checking ") + safe(page, "name", "?") + "…");

    var frames = safe(page, "children", []);
    for (var f = 0; f < frames.length; f++) {
      var frame = frames[f];
      var frameName = safe(frame, "name", "?");
      var entries = collectText(frame, [], [], []);

      for (var i = 0; i < entries.length; i++) {
        var e = entries[i];
        var size = safe(e.node, "fontSize", null);
        var fn = safe(e.node, "fontName", null);
        var family = fn && typeof fn !== "symbol" ? fn.family : "MIXED";
        var style = fn && typeof fn !== "symbol" ? fn.style : "MIXED";
        var chars = String(safe(e.node, "characters", "")).replace(/\n/g, " ");

        if (typeof size !== "number") continue;
        if (REPAIRABLE.indexOf(size) === -1) continue;   // 21 and 22 are never touched

        // Classified at the contract's reference size, so the node is judged as
        // the role it is meant to be - not as whatever the defect made it.
        var verdict = classify({ name: e.name, path: e.path, text: chars, size: CONTRACT_SIZE, frame: frameName });

        if (verdict.role !== ELIGIBLE_ROLE) {
          skipped.push({ frame: frameName, text: chars.slice(0, 40), size: size, role: verdict.role, why: "not a content CTA" });
          continue;
        }
        if (family !== "Montserrat" || style !== "Medium") {
          skipped.push({ frame: frameName, text: chars.slice(0, 40), size: size, role: verdict.role,
                         why: "unexpected typography " + family + " " + style + " - left for review" });
          continue;
        }

        var btnBefore = e.button ? round(safe(e.button, "width", null)) : null;
        var rec = {
          page: safe(page, "name", "?"), frame: frameName, text: chars.slice(0, 40),
          from: size, to: CONTRACT_SIZE, role: verdict.role,
          buttonBefore: btnBefore, buttonAfter: null, wrapperReleased: false,
          holder: null, available: null,
        };

        var holder = e.button ? constrainingAncestor(e.button) : null;
        rec.holder = holder ? safe(holder, "name", "?") : null;
        rec.available = holder ? round(innerWidth(holder)) : null;

        if (!apply) { planned.push(rec); continue; }

        // --- the only write: font size up to 22 ---------------------------
        try {
          await figma.loadFontAsync(fn);
          e.node.fontSize = CONTRACT_SIZE;
        } catch (error) {
          problems.push({ node: frameName + ' / "' + chars.slice(0, 24) + '"', property: "fontSize",
                          error: String(error.message || error) });
          continue;
        }

        // --- geometry, only if the larger label needs it -------------------
        if (e.button) {
          var now = safe(e.button, "width", null);
          var wrapper = safe(e.button, "parent", null);
          if (wrapper && /^button/i.test(safe(wrapper, "name", "")) &&
              safe(wrapper, "width", 0) < now - 0.5) {
            if (!trySet(wrapper, "layoutSizingHorizontal", "HUG")) {
              try { wrapper.resize(now, wrapper.height); } catch (err) {}
            }
            rec.wrapperReleased = true;
            rec.wrapper = safe(wrapper, "name", "?");
          }
          rec.buttonAfter = round(safe(e.button, "width", null));
          rec.overflows = rec.available != null && rec.buttonAfter > rec.available + 0.5;
        }
        planned.push(rec);
      }
    }
  }

  figma.ui.postMessage({
    type: "result",
    mode: apply ? "applied" : "dry-run",
    changes: planned,
    skipped: skipped,
    problems: problems,
  });
}

figma.ui.onmessage = function (msg) {
  if (msg.type !== "run") return;
  run(msg).catch(function (error) {
    figma.ui.postMessage({ type: "error", text: String((error && error.stack) || error) });
  });
};
