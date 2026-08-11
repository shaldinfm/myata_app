/*
 * Radio Myata - typography migration (writes to the live 3.6.6 design)
 *
 * Applies the approved Montserrat + Onest role-based typography to the active
 * design source, together with the approved heading and button geometry
 * corrections. Nothing else is touched: colours, icons, content, structure and
 * unrelated layout are left exactly as they are.
 *
 * This plugin WRITES to the real pages, which is what makes it different from
 * the trial. Three things guard that:
 *
 *   - it runs as a DRY RUN unless "Apply" is explicitly chosen, and the dry run
 *     reports every change it would make;
 *   - on apply it duplicates each target page first, so the pre-migration state
 *     survives as "<page> (pre-typography)" and the whole thing is revertible by
 *     deleting the migrated page and renaming the backup;
 *   - pages outside the target list are never opened for writing, including the
 *     FONT TRIAL pages this tool's sibling produces.
 *
 * The rule block below is generated from the frozen trial classifier by
 * build.mjs - it is never edited here, so the migration cannot drift from what
 * was approved visually.
 */

figma.showUI(__html__, { width: 520, height: 660 });

var STYLE_MAP = {
  Light:   "Light",
  Regular: "Regular",
  Medium:  "Medium",
  Bold:    "Bold",
  Black:   "Black",
  Heavy:   "Black",
};

// Muller's hhea is exactly 1000/1000 units, so Figma resolves an AUTO line
// height on it to exactly the font size. Pinning uses that measured fact.
var MULLER_AUTO_RATIO = 1.0;

// Target pages, by id with a name fallback. Ids come from the committed
// exports; names are matched loosely because the dark page uses an em dash.
var TARGET_PAGES = [
  { id: "2388:366",  name: "CURRENT ANDROID UI - LIGHT" },
  { id: "2436:531",  name: "CURRENT ANDROID UI — DARK" },
  { id: "2517:1936", name: "3.6.6 PROPOSALS - LIGHT" },
  { id: "2517:2903", name: "3.6.6 PROPOSALS - DARK" },
];

var BACKUP_SUFFIX = " (pre-typography)";

/*__RULES__*/

/* ------------------------------------------------------------- helpers -- */

function log(text) { figma.ui.postMessage({ type: "status", text: text }); }

function describe(node) {
  var id = "?", nm = "?";
  try { id = node.id; nm = node.name; } catch (e) {}
  return id + ' "' + nm + '"';
}

function trySet(node, prop, value) {
  try { node[prop] = value; return true; } catch (e) { return false; }
}

function widthOf(node) { try { return node.width; } catch (e) { return null; } }

function lineCountOf(node) {
  try {
    var lh = node.lineHeight;
    if (!lh || lh.unit !== "PIXELS" || !lh.value) return 1;
    return Math.max(1, Math.round(node.height / lh.value));
  } catch (e) { return 1; }
}

async function setSize(node, size) {
  try { await figma.loadFontAsync(node.fontName); node.fontSize = size; return true; }
  catch (e) { return false; }
}

function resolveStyle(family, style) {
  var famWord = String(family || "").replace(/^Muller\s*/i, "").replace(/\s+/g, "");
  var byFamily = STYLE_MAP[famWord] || null;
  var byStyle = STYLE_MAP[style] || null;
  if (byFamily && (!byStyle || style === "Regular")) return byFamily;
  return byStyle || byFamily;
}

function collectText(node, out, path, ancestors) {
  var ty = "", nm = "";
  try { ty = node.type; nm = node.name || ""; } catch (e) { return out; }
  var here = path ? path.concat(nm) : [nm];
  var chain = ancestors ? ancestors.concat([node]) : [node];
  if (ty === "TEXT") {
    var btn = null;
    for (var a = chain.length - 2; a >= 0; a--) {
      var an = "";
      try { an = chain[a].name || ""; } catch (e) {}
      if (/^button/i.test(an)) { btn = chain[a]; break; }
    }
    out.push({ node: node, path: here.join(" > "), name: nm, button: btn });
    return out;
  }
  var kids;
  try { kids = node.children; } catch (e) { return out; }
  if (kids) for (var i = 0; i < kids.length; i++) collectText(kids[i], out, here, chain);
  return out;
}

function containerFit(parent) {
  try {
    if (!parent || parent.layoutMode !== "HORIZONTAL") return null;
    var inner = parent.width - (parent.paddingLeft || 0) - (parent.paddingRight || 0);
    var sum = 0, kids = parent.children;
    for (var i = 0; i < kids.length; i++) sum += kids[i].width;
    var gaps = parent.primaryAxisAlignItems === "SPACE_BETWEEN"
      ? 0 : (parent.itemSpacing || 0) * Math.max(0, kids.length - 1);
    return { inner: inner, used: sum + gaps, fits: sum + gaps <= inner + 0.5 };
  } catch (e) { return null; }
}

/*
 * The nearest ancestor that genuinely constrains width.
 *
 * A button is often wrapped in its own single-child frame - "Button:align-flex-
 * start" - which is an auto-layout frame sized to the OLD label. Treating that
 * as the constraint is circular: it says the button has no room to grow because
 * it is exactly as wide as the button used to be, and the label gets shrunk to
 * fit a box that only exists to hold the label. Wrappers named after the button
 * are skipped so the real container - the card, the column - is found instead.
 */
function constrainingAncestor(node, maxUp) {
  var cur = node, up = 0;
  try {
    while (cur && up < (maxUp || 6)) {
      cur = cur.parent; up++;
      if (!cur || !cur.width) continue;
      if (/^button/i.test(cur.name || "")) continue;
      if (cur.layoutMode === "VERTICAL" || cur.layoutMode === "HORIZONTAL") return cur;
    }
  } catch (e) {}
  return null;
}

function innerWidthOf(frame) {
  try { return frame.width - (frame.paddingLeft || 0) - (frame.paddingRight || 0); }
  catch (e) { return null; }
}

/* ----------------------------------------------------------- migration -- */

async function restyleNode(textNode, family, weightOverride, problems) {
  var segments;
  try {
    segments = textNode.getStyledTextSegments(["fontName", "fontSize", "lineHeight", "letterSpacing"]);
  } catch (error) {
    problems.push({ node: describe(textNode), property: "getStyledTextSegments", error: String(error.message || error) });
    return false;
  }
  var touched = false;
  for (var i = 0; i < segments.length; i++) {
    var seg = segments[i], src = seg.fontName;
    if (!src || typeof src === "symbol") continue;
    var style = resolveStyle(src.family, src.style);
    if (!style) { problems.push({ node: describe(textNode), property: "weight", error: 'unmapped "' + src.family + " / " + src.style + '"' }); continue; }
    var target = { family: family, style: weightOverride || style };
    try {
      await figma.loadFontAsync(src);
      await figma.loadFontAsync(target);
    } catch (error) {
      problems.push({ node: describe(textNode), property: "loadFontAsync " + target.family + " " + target.style, error: String(error.message || error) });
      continue;
    }
    if (seg.lineHeight && seg.lineHeight.unit === "AUTO") {
      var pinned = Math.round(seg.fontSize * MULLER_AUTO_RATIO * 100) / 100;
      try { textNode.setRangeLineHeight(seg.start, seg.end, { unit: "PIXELS", value: pinned }); } catch (e) {}
    }
    try { textNode.setRangeFontName(seg.start, seg.end, target); touched = true; }
    catch (error) { problems.push({ node: describe(textNode), property: "setRangeFontName", error: String(error.message || error) }); }
  }
  return touched;
}

// Growth first, exactly as approved: keep the size and let the box grow when the
// surrounding space allows it; shrink only when growth would genuinely collide.
async function fitNode(e, base, changes) {
  if ((e.role === "button/CTA" || e.role === "compact action button") && base.buttonWidth != null && e.button) {
    var now = widthOf(e.button);
    if (now == null || now <= base.buttonWidth + 0.5) return;
    var startSize = e.appliedSize != null ? e.appliedSize : base.size;
    var holder = constrainingAncestor(e.button, 4);
    var avail = holder ? innerWidthOf(holder) : null;
    var rec = { kind: "button", frame: base.frame, page: base.page, text: base.text, size: startSize,
                frozenWidth: Math.round(base.buttonWidth * 10) / 10,
                available: avail == null ? null : Math.round(avail * 10) / 10 };
    if (avail != null && now <= avail + 0.5) {
      var wrapper = null;
      try { wrapper = e.button.parent; } catch (err) {}
      if (wrapper && /^button/i.test(wrapper.name || "") && widthOf(wrapper) < now - 0.5) {
        if (!trySet(wrapper, "layoutSizingHorizontal", "HUG")) {
          try { wrapper.resize(now, wrapper.height); } catch (err) {}
        }
        rec.wrapperReleased = true;
      }
      rec.solution = "grew"; rec.finalSize = startSize;
    } else {
      var applied = startSize;
      for (var s = 1; s <= 4; s++) {
        if (!(await setSize(e.node, startSize - s))) break;
        applied = startSize - s;
        var w = widthOf(e.button);
        if (avail == null ? w <= base.buttonWidth + 0.5 : w <= avail + 0.5) break;
      }
      rec.solution = "shrank"; rec.finalSize = applied;
    }
    rec.nowWidth = Math.round(widthOf(e.button) * 10) / 10;
    rec.delta = Math.round((rec.nowWidth - rec.frozenWidth) * 10) / 10;
    rec.fits = avail == null ? rec.nowWidth <= rec.frozenWidth + 0.5 : rec.nowWidth <= avail + 0.5;
    changes.push(rec);
    return;
  }

  var isHeading = e.role === "heading" || e.role === "card heading" ||
                  e.role === "collection track title" || e.role === "player track metadata";
  if (!isHeading || base.lines !== 1 || lineCountOf(e.node) <= 1) return;

  var rec2 = { kind: "heading", frame: base.frame, page: base.page, text: base.text, size: base.size,
               frozenWidth: base.width == null ? null : Math.round(base.width * 10) / 10 };
  var box = null;
  try { box = e.node.parent; } catch (err) {}
  var prevAuto = null;
  try { prevAuto = e.node.textAutoResize; } catch (err) {}

  var grew = trySet(e.node, "textAutoResize", "WIDTH_AND_HEIGHT");
  if (grew && box && /^heading/i.test(box.name || "")) trySet(box, "layoutSizingHorizontal", "HUG");
  var row = box ? containerFit(box.parent) : null;

  if (grew && lineCountOf(e.node) <= 1 && (!row || row.fits)) {
    rec2.solution = "grew";
    rec2.finalSize = base.size;
    rec2.nowWidth = Math.round(widthOf(e.node) * 10) / 10;
    rec2.delta = rec2.frozenWidth == null ? null : Math.round((rec2.nowWidth - rec2.frozenWidth) * 10) / 10;
    rec2.lines = 1; rec2.fits = true;
    changes.push(rec2);
    return;
  }

  if (prevAuto) trySet(e.node, "textAutoResize", prevAuto);
  if (base.width != null) { try { e.node.resize(base.width, e.node.height); } catch (err) {} }
  var applied2 = base.size, ls = null;
  for (var h = 1; h <= 3; h++) {
    if (!(await setSize(e.node, base.size - h))) break;
    applied2 = base.size - h;
    if (lineCountOf(e.node) <= 1) break;
  }
  if (lineCountOf(e.node) > 1) {
    for (var t = 1; t <= 4; t++) {
      ls = -0.1 * t;
      if (!trySet(e.node, "letterSpacing", { unit: "PIXELS", value: ls })) break;
      if (lineCountOf(e.node) <= 1) break;
    }
  }
  rec2.solution = "shrank"; rec2.finalSize = applied2; rec2.letterSpacing = ls;
  rec2.lines = lineCountOf(e.node); rec2.fits = rec2.lines <= 1;
  changes.push(rec2);
}

async function run(msg) {
  var apply = msg.mode === "apply";
  var problems = [], fits = [], sizeChanges = [], weightChanges = [], familyTally = {}, roleTally = {}, pageReport = [];

  log("Loading pages…");
  if (typeof figma.loadAllPagesAsync === "function") await figma.loadAllPagesAsync();

  var pages = figma.root.children;
  var targets = [];
  for (var i = 0; i < TARGET_PAGES.length; i++) {
    var want = TARGET_PAGES[i], hit = null;
    for (var p = 0; p < pages.length; p++) {
      if (pages[p].id === want.id) { hit = pages[p]; break; }
    }
    if (!hit) {
      var norm = want.name.replace(/[—–-]/g, "-").toLowerCase();
      for (var q = 0; q < pages.length; q++) {
        if (String(pages[q].name).replace(/[—–-]/g, "-").toLowerCase() === norm) { hit = pages[q]; break; }
      }
    }
    if (hit) targets.push({ page: hit, matchedBy: hit.id === want.id ? "id" : "name" });
    else problems.push({ node: want.name, property: "page", error: "not found - skipped" });
  }

  if (!targets.length) {
    figma.ui.postMessage({ type: "error", text: "None of the four target pages were found in this document." });
    return;
  }

  for (var t = 0; t < targets.length; t++) {
    var page = targets[t].page;
    log((apply ? "Migrating " : "Checking ") + page.name + " (" + (t + 1) + "/" + targets.length + ")…");

    if (apply && msg.backup) {
      var already = false;
      for (var b = 0; b < pages.length; b++) if (pages[b].name === page.name + BACKUP_SUFFIX) already = true;
      if (!already) {
        try {
          var copy = page.clone();
          copy.name = page.name + BACKUP_SUFFIX;
        } catch (error) {
          problems.push({ node: page.name, property: "backup", error: String(error.message || error) });
        }
      }
    }

    var pageCounts = { page: page.name, matchedBy: targets[t].matchedBy, frames: 0, nodes: 0, changed: 0, byFamily: {} };

    for (var f = 0; f < page.children.length; f++) {
      var frame = page.children[f];
      pageCounts.frames++;
      var entries = collectText(frame, [], null, null);

      var baselines = entries.map(function (e) {
        var out = { frame: frame.name, page: page.name, size: 0, text: "", style: "", family: "",
                    lines: 1, width: null, buttonWidth: null };
        try {
          out.size = e.node.fontSize;
          out.text = String(e.node.characters).slice(0, 40);
          if (e.node.fontName && typeof e.node.fontName !== "symbol") {
            out.family = e.node.fontName.family; out.style = e.node.fontName.style;
          }
        } catch (err) {}
        out.lines = lineCountOf(e.node);
        out.width = widthOf(e.node);
        if (e.button) { try { out.buttonWidth = e.button.width; } catch (err) {} }
        return out;
      });

      for (var n = 0; n < entries.length; n++) {
        var e = entries[n], base = baselines[n];
        pageCounts.nodes++;
        var verdict = classify({ name: e.name, path: e.path, text: base.text, size: base.size, frame: frame.name });
        e.role = verdict.role;

        familyTally[verdict.family] = (familyTally[verdict.family] || 0) + 1;
        roleTally[verdict.role] = (roleTally[verdict.role] || 0) + 1;
        pageCounts.byFamily[verdict.family] = (pageCounts.byFamily[verdict.family] || 0) + 1;

        var targetStyle = verdict.weight || resolveStyle(base.family, base.style);
        var familyChanges = base.family !== verdict.family;
        var weightChangesHere = targetStyle && targetStyle !== base.style;
        var sizeChangesHere = verdict.size && verdict.size !== base.size;
        if (familyChanges || weightChangesHere || sizeChangesHere) pageCounts.changed++;

        if (weightChangesHere) weightChanges.push({ page: page.name, frame: frame.name, text: base.text, from: base.style, to: targetStyle, role: verdict.role });
        if (sizeChangesHere) sizeChanges.push({ page: page.name, frame: frame.name, text: base.text, from: base.size, to: verdict.size, role: verdict.role });

        if (!apply) continue;

        await restyleNode(e.node, verdict.family, verdict.weight, problems);
        if (verdict.size && verdict.size !== base.size) {
          if (await setSize(e.node, verdict.size)) e.appliedSize = verdict.size;
        }
      }

      if (apply) for (var g = 0; g < entries.length; g++) await fitNode(entries[g], baselines[g], fits);
    }
    pageReport.push(pageCounts);
  }

  figma.ui.postMessage({
    type: "result",
    mode: apply ? "applied" : "dry-run",
    pages: pageReport,
    families: familyTally,
    roles: roleTally,
    sizes: sizeChanges,
    weights: weightChanges,
    fits: fits,
    problems: problems,
  });
}

figma.ui.onmessage = function (msg) {
  if (msg.type !== "run") return;
  run(msg).catch(function (error) {
    figma.ui.postMessage({ type: "error", text: String((error && error.stack) || error) });
  });
};
