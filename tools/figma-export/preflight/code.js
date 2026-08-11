/*
 * Radio Myata - typography preflight (STRICTLY READ-ONLY)
 *
 * Answers, for the four ORIGINAL unsuffixed pages, the questions that decide
 * whether a targeted repair is safe or whether the migration has to be redone:
 *
 *   1. do the pages still exist under their original page ids;
 *   2. what typography is actually on them now - Muller vs Montserrat vs Onest;
 *   3. is their structure intact, in particular the UI KIT component masters on
 *      the dark page;
 *   4. which text nodes are off the approved contract, and at what size;
 *   5. is the owner's manual one-line correction present.
 *
 * It writes nothing. It creates no pages, clones nothing, and never calls a
 * mutating API - the only Figma calls are loadAllPagesAsync and property reads.
 * The `(pre-typography)` clones are deliberately ignored: cloning detached the
 * component masters, so they are no longer a trustworthy reference.
 *
 * The classifier below is generated from the frozen trial rules by build.mjs, so
 * the contract this checks against is the one that was approved.
 */

figma.showUI(__html__, { width: 560, height: 680 });

// Original page ids, from the pre-migration exports.
var TARGET_PAGES = [
  { id: "2388:366",  name: "CURRENT ANDROID UI - LIGHT" },
  { id: "2436:531",  name: "CURRENT ANDROID UI — DARK" },
  { id: "2517:1936", name: "3.6.6 PROPOSALS - LIGHT" },
  { id: "2517:2903", name: "3.6.6 PROPOSALS - DARK" },
];

var STYLE_MAP = {
  Light: "Light", Regular: "Regular", Medium: "Medium",
  Bold: "Bold", Black: "Black", Heavy: "Black",
};

// The owner's manual correction that must survive.
var MANUAL_FIX = { frame: "sleep-timer-menu-active", text: "Сообщить о проблеме" };

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

function resolveStyle(family, style) {
  var famWord = String(family || "").replace(/^Muller\s*/i, "").replace(/\s+/g, "");
  var byFamily = STYLE_MAP[famWord] || null;
  var byStyle = STYLE_MAP[style] || null;
  if (byFamily && (!byStyle || style === "Regular")) return byFamily;
  return byStyle || byFamily;
}

function lineCountOf(node) {
  var lh = safe(node, "lineHeight", null);
  var h = safe(node, "height", 0);
  if (!lh || lh.unit !== "PIXELS" || !lh.value) return null;
  return Math.max(1, Math.round(h / lh.value));
}

// Walk a page collecting everything the audit needs, in one pass.
function scan(page) {
  var out = {
    texts: [], componentCount: 0, instanceCount: 0,
    components: [], frames: [], byFamily: {}, uiKit: null,
  };

  function walk(node, frame, path, inUiKit) {
    var type = safe(node, "type", "?");
    var name = safe(node, "name", "?");
    var here = path.concat(name);
    var uiKit = inUiKit || /ui kit/i.test(name);

    if (type === "COMPONENT" || type === "COMPONENT_SET") {
      out.componentCount++;
      out.components.push({ name: name, id: safe(node, "id", "?"), frame: frame, uiKit: uiKit });
    } else if (type === "INSTANCE") {
      out.instanceCount++;
      var main = null;
      try { main = node.mainComponent ? node.mainComponent.id : null; } catch (e) { main = null; }
      out.components.push({ name: name, id: safe(node, "id", "?"), frame: frame, uiKit: uiKit, instance: true, mainComponent: main });
    }

    if (type === "TEXT") {
      var fn = safe(node, "fontName", null);
      var family = fn && typeof fn !== "symbol" ? fn.family : "MIXED";
      var style = fn && typeof fn !== "symbol" ? fn.style : "MIXED";
      var chars = String(safe(node, "characters", ""));
      var size = safe(node, "fontSize", 0);
      out.byFamily[family] = (out.byFamily[family] || 0) + 1;
      out.texts.push({
        frame: frame, name: name, path: here.join(" > "),
        text: chars.replace(/\n/g, " ").slice(0, 60),
        family: family, style: style,
        size: typeof size === "number" ? size : null,
        lines: lineCountOf(node),
        width: safe(node, "width", null),
        uiKit: uiKit,
      });
    }

    var kids = safe(node, "children", null);
    if (kids) for (var i = 0; i < kids.length; i++) walk(kids[i], frame, here, uiKit);
  }

  var children = safe(page, "children", []);
  for (var f = 0; f < children.length; f++) {
    var fr = children[f];
    var frName = safe(fr, "name", "?");
    out.frames.push(frName);
    walk(fr, frName, [], /ui kit/i.test(frName));
  }
  return out;
}

/* ---------------------------------------------------------------- main -- */

async function run() {
  log("Loading pages…");
  if (typeof figma.loadAllPagesAsync === "function") await figma.loadAllPagesAsync();

  var allPages = figma.root.children;
  var pageIndex = allPages.map(function (p) {
    return { id: safe(p, "id", "?"), name: safe(p, "name", "?") };
  });

  var report = { pages: [], strays: [], generatedAt: new Date().toISOString() };

  // Any leftover clones, so the owner can see what is still lying around.
  for (var s = 0; s < pageIndex.length; s++) {
    if (/\(pre-typography\)/i.test(pageIndex[s].name) || /^FONT TRIAL/i.test(pageIndex[s].name)) {
      report.strays.push(pageIndex[s]);
    }
  }

  for (var t = 0; t < TARGET_PAGES.length; t++) {
    var want = TARGET_PAGES[t];
    log("Auditing " + want.name + " (" + (t + 1) + "/" + TARGET_PAGES.length + ")…");

    var page = null, matchedBy = null;
    for (var p = 0; p < allPages.length; p++) {
      if (safe(allPages[p], "id", null) === want.id) { page = allPages[p]; matchedBy = "id"; break; }
    }
    if (!page) {
      var norm = want.name.replace(/[—–-]/g, "-").toLowerCase();
      for (var q = 0; q < allPages.length; q++) {
        var nm = String(safe(allPages[q], "name", "")).replace(/[—–-]/g, "-").toLowerCase();
        // The unsuffixed original only - never a clone.
        if (nm === norm) { page = allPages[q]; matchedBy = "name"; break; }
      }
    }

    if (!page) {
      report.pages.push({ expected: want, found: false });
      continue;
    }

    var data = scan(page);

    // --- typography state ------------------------------------------------
    var muller = 0, mont = 0, onest = 0, other = 0;
    Object.keys(data.byFamily).forEach(function (f) {
      var n = data.byFamily[f];
      if (/^muller/i.test(f)) muller += n;
      else if (/^montserrat/i.test(f)) mont += n;
      else if (/^onest/i.test(f)) onest += n;
      else other += n;
    });
    var state = muller === 0 && (mont + onest) > 0 ? "migrated"
              : muller > 0 && (mont + onest) === 0 ? "pre-migration (Muller)"
              : "MIXED - partially migrated";

    // --- contract conformance --------------------------------------------
    var violations = [];
    for (var i = 0; i < data.texts.length; i++) {
      var r = data.texts[i];
      if (r.family === "MIXED") { violations.push({ r: r, prop: "family", want: "single family", got: "MIXED" }); continue; }
      // Size-conditional rules read the pre-migration size, so a node already at
      // 21px must be probed at 22 or it stops matching its own rule.
      var probe = r.size === 21 ? 22 : r.size;
      var v = classify({ name: r.name, path: r.path, text: r.text, size: probe, frame: r.frame });
      if (r.family !== v.family) violations.push({ r: r, prop: "family", want: v.family, got: r.family, role: v.role });
      if (v.weight && r.style !== v.weight) violations.push({ r: r, prop: "weight", want: v.weight, got: r.style, role: v.role });
      if (v.size && r.size !== v.size) violations.push({ r: r, prop: "size", want: v.size + "px", got: r.size + "px", role: v.role });
      if (!v.size && (r.size === 19 || r.size === 20)) violations.push({ r: r, prop: "size", want: "22px (unchanged)", got: r.size + "px", role: v.role });
    }

    // --- the owner's manual correction ------------------------------------
    var manual = null;
    for (var m = 0; m < data.texts.length; m++) {
      var x = data.texts[m];
      if (x.frame.indexOf(MANUAL_FIX.frame) === 0 && x.text.indexOf(MANUAL_FIX.text) === 0) {
        manual = { frame: x.frame, text: x.text, family: x.family, style: x.style, size: x.size,
                   lines: x.lines, width: x.width, oneLine: x.lines === 1 };
        break;
      }
    }

    // --- structure ---------------------------------------------------------
    var uiKitComponents = data.components.filter(function (c) { return c.uiKit && !c.instance; });
    var uiKitInstances = data.components.filter(function (c) { return c.uiKit && c.instance; });

    report.pages.push({
      expected: want, found: true, matchedBy: matchedBy,
      actualId: safe(page, "id", "?"), actualName: safe(page, "name", "?"),
      frames: data.frames.length, frameNames: data.frames,
      textNodes: data.texts.length,
      families: { Muller: muller, Montserrat: mont, Onest: onest, other: other, raw: data.byFamily },
      state: state,
      components: { COMPONENT: data.componentCount, INSTANCE: data.instanceCount,
                    uiKitComponents: uiKitComponents.length, uiKitInstances: uiKitInstances.length,
                    uiKitNames: uiKitComponents.map(function (c) { return c.name; }).slice(0, 60) },
      violations: violations.map(function (v) {
        return { frame: v.r.frame, text: v.r.text, prop: v.prop, got: v.got, want: v.want,
                 role: v.role || null, size: v.r.size, family: v.r.family, style: v.r.style };
      }),
      manualFix: manual,
    });
  }

  figma.ui.postMessage({ type: "result", report: report });
}

figma.ui.onmessage = function (msg) {
  if (msg.type !== "run") return;
  run().catch(function (error) {
    figma.ui.postMessage({ type: "error", text: String((error && error.stack) || error) });
  });
};
