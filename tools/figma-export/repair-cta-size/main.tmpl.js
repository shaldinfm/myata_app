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

/*__RULES__*/

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
