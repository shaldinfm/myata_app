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

/*__RULES__*/

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
