/*
 * Radio Myata - font trial: Muller vs Montserrat vs Onest.
 *
 * Builds a side-by-side visual comparison of seven representative frames so the
 * owner can judge a font swap before anything is committed to the design or the
 * app.
 *
 * The frozen pages are never touched. Every frame is CLONED onto a brand new
 * page created by this run; the clones are what get restyled. Nothing is written
 * back to a canonical or proposal frame, and the new page is additive - re-running
 * makes another page rather than overwriting the previous one.
 *
 * The critical rule is that the frozen type scale wins, not the new font's
 * natural metrics:
 *
 *   - fontSize is never touched;
 *   - letterSpacing is never touched;
 *   - an explicit lineHeight is carried over verbatim - 90 of the 91 frozen text
 *     nodes already pin one in PIXELS, so the design is largely immune to a font
 *     with a taller line box;
 *   - an AUTO lineHeight would otherwise be redefined by the new font, so it is
 *     pinned first to the value Muller resolves it to. Muller's hhea is exactly
 *     1000/1000 units, so its AUTO line height is exactly 1.0 x fontSize. That is
 *     measured from the shipped binaries, not guessed. Montserrat would resolve
 *     the same node to 1.219x and Onest to 1.275x.
 *
 * Only one frozen node is AUTO: PLAYER > "PAUSE" at 26.77 Medium.
 */

figma.showUI(__html__, { width: 500, height: 640 });

// Weight mapping, as specified by the owner. Muller Heavy folds onto Black:
// both are weightClass 900 and the replacement families ship a single 900.
var STYLE_MAP = {
  Light:   "Light",     // 300 - TV only
  Regular: "Regular",   // 400
  Medium:  "Medium",    // 500
  Bold:    "Bold",      // 700
  Black:   "Black",     // 900
  Heavy:   "Black",     // 900 -> Black
};

var CANDIDATES = ["Montserrat", "Onest"];
var COLUMN_HYBRID = "HYBRID";

/*
 * Role-based typography for the hybrid trial.
 *
 * The owner's system is a role split, not a screen split:
 *
 *   MONTSERRAT = expressive / music / emphasis
 *   ONEST      = utility / navigation / reading
 *
 * So the classifier keys off what a node IS in the frozen structure - its own
 * name and its ancestry - never off which screen it happens to sit on. That is
 * why a heading in Settings comes out Montserrat even though Settings as a whole
 * reads better in Onest, and why the mini-player comes out Onest even on HOME
 * where the headings around it are Montserrat.
 *
 * Every rule returns the role it matched, so the trial can report why each node
 * was assigned rather than just what it got.
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
if (typeof globalThis !== "undefined") globalThis.__fontTrialClassify = classify;


// Muller hhea is (780 - -220 + 0) / 1000 = exactly 1.0 em, so Figma resolves an
// AUTO line height on Muller to exactly the font size.
var MULLER_AUTO_RATIO = 1.0;

var FRAMES = [
  { name: "HOME",              why: "BottomNav labels, headings, mini-player" },
  { name: "PLAYER",            why: "Black 24 now-playing title, history rows, the one AUTO line height" },
  { name: "COLLECTION",        why: "track rows and the widest button" },
  { name: "ABOUT US",          why: "long paragraph and the donation CTA" },
  { name: "history-content",   why: "Broadcast History - variable height, no ellipsis" },
  { name: "sleep-timer-custom", why: "custom time entry" },
  { name: "settings",          why: "Profile/Settings rows, buttons and labels" },
];

// The two longest Russian examples, injected into History rows on every column so
// the comparison is like-for-like and actually stressed.
var STRESS = [
  "КРАСНОЗНАМЁННАЯ ДИВИЗИЯ ИМЕНИ МОЕЙ БАБУШКИ",
  "Прогулка по воде под дождём в конце ноября",
];

var GAP = 120;

function log(text) { figma.ui.postMessage({ type: "status", text: text }); }

function describe(n) {
  var id = "?", nm = "?", ty = "?";
  try { id = n.id; } catch (e) {}
  try { nm = n.name; } catch (e) {}
  try { ty = n.type; } catch (e) {}
  return id + ' "' + nm + '" (' + ty + ")";
}

/* ------------------------------------------------------------ discovery -- */

function findFrame(name) {
  var pages = figma.root.children;
  for (var i = 0; i < pages.length; i++) {
    var hit = search(pages[i], name);
    if (hit) return { node: hit, page: pages[i].name };
  }
  return null;
}

function search(node, name) {
  var kids;
  try { kids = node.children; } catch (e) { return null; }
  if (!kids) return null;
  for (var i = 0; i < kids.length; i++) {
    var k = kids[i];
    var nm = "";
    try { nm = k.name; } catch (e) {}
    var ty = "";
    try { ty = k.type; } catch (e) {}
    if (nm === name && (ty === "FRAME" || ty === "COMPONENT" || ty === "INSTANCE")) return k;
    var deeper = search(k, name);
    if (deeper) return deeper;
  }
  return null;
}

// Carries the ancestor path with each node: the role classifier keys off what a
// node is in the structure, which only the path can tell it.
function collectText(node, out, path, ancestors) {
  var ty = "", nm = "";
  try { ty = node.type; nm = node.name || ""; } catch (e) { return out; }
  var here = path ? path.concat(nm) : [nm];
  var chain = ancestors ? ancestors.concat([node]) : [node];
  if (ty === "TEXT") {
    // Nearest Button ancestor, so the fit pass can hold the frozen geometry.
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

/* ------------------------------------------------------------- restyle -- */

/*
 * Resolve the target weight from whatever Figma reports.
 *
 * Muller is inconsistent about where the weight lives. Some cuts are family
 * "Muller" with style "Black"; others are family "Muller Black" with style
 * "Regular", because that is how the TTF's name table is built. Reading the
 * style first would map "Muller Black / Regular" to Regular and silently drop
 * the weight, so a weight named in the FAMILY wins whenever the style is the
 * generic "Regular".
 */
function resolveStyle(family, style) {
  var famWord = String(family || "").replace(/^Muller\s*/i, "").replace(/\s+/g, "");
  var byFamily = STYLE_MAP[famWord] || null;
  var byStyle = STYLE_MAP[style] || null;
  if (byFamily && (!byStyle || style === "Regular")) return byFamily;
  return byStyle || byFamily;
}

async function restyleNode(textNode, family, problems, notes, weightOverride) {
  if (!family) return;
  var segments;
  try {
    segments = textNode.getStyledTextSegments(["fontName", "fontSize", "lineHeight", "letterSpacing"]);
  } catch (error) {
    problems.push({ node: describe(textNode), property: "getStyledTextSegments", error: String(error.message || error) });
    return;
  }

  for (var i = 0; i < segments.length; i++) {
    var seg = segments[i];
    var src = seg.fontName;
    if (!src || typeof src === "symbol") continue;

    var style = resolveStyle(src.family, src.style);
    if (!style) {
      notes.push('unmapped weight "' + src.family + " / " + src.style + '" on ' + describe(textNode) + " - left as is");
      continue;
    }

    // A role may force a weight - button labels are set Medium regardless of the
    // Regular the frozen scale gives them.
    var target = { family: family, style: weightOverride || style };
    try {
      await figma.loadFontAsync(src);
      await figma.loadFontAsync(target);
    } catch (error) {
      problems.push({ node: describe(textNode), property: "loadFontAsync " + family + " " + style, error: String(error.message || error) });
      continue;
    }

    // Pin an AUTO line height BEFORE the swap, to the value Muller resolves it
    // to. After the swap the new font would define it instead, which is exactly
    // what the trial must not let happen.
    if (seg.lineHeight && seg.lineHeight.unit === "AUTO") {
      var pinned = Math.round(seg.fontSize * MULLER_AUTO_RATIO * 100) / 100;
      try {
        textNode.setRangeLineHeight(seg.start, seg.end, { unit: "PIXELS", value: pinned });
        notes.push("pinned AUTO line height to " + pinned + "px (Muller 1.0em) on " + describe(textNode));
      } catch (error) {
        problems.push({ node: describe(textNode), property: "setRangeLineHeight", error: String(error.message || error) });
      }
    }

    try {
      textNode.setRangeFontName(seg.start, seg.end, target);
    } catch (error) {
      problems.push({ node: describe(textNode), property: "setRangeFontName", error: String(error.message || error) });
    }
    // fontSize and letterSpacing are deliberately never written.
  }
}

async function injectStress(frame, problems, changed) {
  var entries = collectText(frame, [], null);

  // The frozen frame already carries one of the two examples, so only the
  // missing one is injected. Overwriting frozen copy that is already correct
  // would make the trial less faithful, not more.
  var present = {};
  for (var p = 0; p < entries.length; p++) {
    var cur = "";
    try { cur = entries[p].node.characters; } catch (e) {}
    for (var s2 = 0; s2 < STRESS.length; s2++) if (cur.indexOf(STRESS[s2]) >= 0) present[s2] = true;
  }
  var wanted = [];
  for (var s3 = 0; s3 < STRESS.length; s3++) if (!present[s3]) wanted.push(STRESS[s3]);
  if (!wanted.length) return 0;

  var texts = entries.map(function (e) { return e.node; });
  var picked = 0;
  for (var i = 0; i < texts.length && picked < wanted.length; i++) {
    var t = texts[i];
    var size, fname;
    try { size = t.fontSize; fname = t.fontName; } catch (e) { continue; }
    if (size !== 14 || typeof fname === "symbol") continue;
    var old = "";
    try { old = t.characters; } catch (e) {}
    // Skip section headers and anything already short-and-structural.
    if (!old || old.length < 6) continue;
    try {
      await figma.loadFontAsync(fname);
      t.characters = wanted[picked];
      changed.push({ node: describe(t), from: old, to: wanted[picked] });
      picked++;
    } catch (error) {
      problems.push({ node: describe(t), property: "characters", error: String(error.message || error) });
    }
  }
  return picked;
}

/* -------------------------------------------------------------- fitting -- */

/*
 * How many lines a text node is rendering. Every frozen node pins lineHeight in
 * PIXELS and the trial pins the single AUTO one before swapping, so this is
 * exact rather than inferred.
 */
function lineCountOf(node) {
  try {
    var lh = node.lineHeight;
    if (!lh || lh.unit !== "PIXELS" || !lh.value) return 1;
    return Math.max(1, Math.round(node.height / lh.value));
  } catch (e) { return 1; }
}

function widthOf(node) { try { return node.width; } catch (e) { return null; } }

async function setSize(node, size) {
  try {
    await figma.loadFontAsync(node.fontName);
    node.fontSize = size;
    return true;
  } catch (e) { return false; }
}

function trySet(node, prop, value) {
  try { node[prop] = value; return true; } catch (e) { return false; }
}

/*
 * Inner width available to the children of a horizontal auto-layout frame, and
 * whether they currently fit inside it.
 *
 * A SPACE_BETWEEN row absorbs growth by giving up gap, so the honest test is
 * whether the children's own widths plus padding still fit - not whether the
 * frozen gap survived.
 */
function containerFit(parent) {
  try {
    if (!parent || parent.layoutMode !== "HORIZONTAL") return null;
    var inner = parent.width - (parent.paddingLeft || 0) - (parent.paddingRight || 0);
    var sum = 0, kids = parent.children;
    for (var i = 0; i < kids.length; i++) sum += kids[i].width;
    var gaps = parent.primaryAxisAlignItems === "SPACE_BETWEEN"
      ? 0
      : (parent.itemSpacing || 0) * Math.max(0, kids.length - 1);
    return { inner: inner, used: sum + gaps, fits: sum + gaps <= inner + 0.5 };
  } catch (e) { return null; }
}

// Nearest ancestor that actually constrains width.
function constrainingAncestor(node, maxUp) {
  var cur = node, up = 0;
  try {
    while (cur && up < (maxUp || 5)) {
      cur = cur.parent;
      up++;
      if (!cur || !cur.width) continue;
      if (cur.layoutMode === "VERTICAL" || cur.layoutMode === "HORIZONTAL") return cur;
    }
  } catch (e) {}
  return null;
}

function innerWidthOf(frame) {
  try { return frame.width - (frame.paddingLeft || 0) - (frame.paddingRight || 0); }
  catch (e) { return null; }
}

/*
 * Restore the frozen INTENT after a swap, which is not the same as restoring the
 * frozen numbers.
 *
 * A frozen width is often just the box Muller's text happened to occupy rather
 * than a constraint anyone designed. Shrinking a 24px heading to protect such a
 * number trades real hierarchy for an accident. So growth is tried first and
 * shrinking is the last resort, in the owner's order:
 *
 *   1. keep the size and let the box grow, if the surrounding space allows it;
 *   2. a small layout adjustment - a fixed wrapper is released to hug;
 *   3. reduce fontSize, minimally, 1px at a time;
 *   4. a very small negative letterSpacing as the final trim.
 *
 * Every decision is measured against what Figma actually renders, because
 * kerning is applied by the rasteriser and not by any offline estimate.
 */
async function fitHybrid(entries, baselines, changes) {
  for (var i = 0; i < entries.length; i++) {
    var e = entries[i], b = baselines[i];
    if (!b) continue;

    /* ---- buttons: grow the button if its container has the room -------- */
    if ((e.role === "button/CTA" || e.role === "compact action button") && b.buttonWidth != null && e.button) {
      var now = widthOf(e.button);
      if (now == null || now <= b.buttonWidth + 0.5) continue;

      var holder = constrainingAncestor(e.button, 4);
      var avail = holder ? innerWidthOf(holder) : null;
      var startSize = e.appliedSize != null ? e.appliedSize : b.size;
      var rec = {
        kind: "button", frame: b.frame, text: b.text, size: startSize,
        frozenWidth: Math.round(b.buttonWidth * 10) / 10,
        available: avail == null ? null : Math.round(avail * 10) / 10,
        holder: holder ? holder.name : null
      };

      if (avail != null && now <= avail + 0.5) {
        // A fixed single-child wrapper would clip the button, so release it to
        // hug. That is the small layout adjustment, not a redesign.
        var wrapper = null;
        try { wrapper = e.button.parent; } catch (err) {}
        if (wrapper && /^button/i.test(wrapper.name || "") && widthOf(wrapper) < now - 0.5) {
          if (!trySet(wrapper, "layoutSizingHorizontal", "HUG")) {
            try { wrapper.resize(now, wrapper.height); } catch (err) {}
          }
          rec.wrapperReleased = wrapper.name;
        }
        rec.solution = "grew";
        rec.finalSize = startSize;
        rec.nowWidth = Math.round(widthOf(e.button) * 10) / 10;
        rec.delta = Math.round((rec.nowWidth - rec.frozenWidth) * 10) / 10;
        rec.fits = true;
        rec.why = "container had the room, so the label keeps its frozen size and the button takes the extra width";
      } else {
        var applied = startSize;
        for (var step = 1; step <= 4; step++) {
          if (!(await setSize(e.node, startSize - step))) break;
          applied = startSize - step;
          var w = widthOf(e.button);
          if (avail == null ? w <= b.buttonWidth + 0.5 : w <= avail + 0.5) break;
        }
        rec.solution = "shrank";
        rec.finalSize = applied;
        rec.nowWidth = Math.round(widthOf(e.button) * 10) / 10;
        rec.delta = Math.round((rec.nowWidth - rec.frozenWidth) * 10) / 10;
        rec.fits = avail == null ? rec.nowWidth <= rec.frozenWidth + 0.5 : rec.nowWidth <= avail + 0.5;
        rec.why = "no horizontal room to grow into, so the label had to come down";
      }
      changes.push(rec);
      continue;
    }

    /* ---- headings: a one-line heading stays one line ------------------- */
    var isHeading = e.role === "heading" || e.role === "collection track title" || e.role === "player track metadata";
    if (!isHeading || b.lines !== 1) continue;
    if (lineCountOf(e.node) <= 1) continue;

    var rec2 = { kind: "heading", frame: b.frame, text: b.text, size: b.size,
                 frozenWidth: b.width == null ? null : Math.round(b.width * 10) / 10 };

    var box = null;
    try { box = e.node.parent; } catch (err) {}
    var prevAutoResize = null;
    try { prevAutoResize = e.node.textAutoResize; } catch (err) {}

    var grew = trySet(e.node, "textAutoResize", "WIDTH_AND_HEIGHT");
    if (grew && box && /^heading/i.test(box.name || "")) trySet(box, "layoutSizingHorizontal", "HUG");

    var row = box ? containerFit(box.parent) : null;
    var oneLine = lineCountOf(e.node) <= 1;

    if (grew && oneLine && (!row || row.fits)) {
      rec2.solution = "grew";
      rec2.finalSize = b.size;
      rec2.nowWidth = Math.round(widthOf(e.node) * 10) / 10;
      rec2.delta = rec2.frozenWidth == null ? null : Math.round((rec2.nowWidth - rec2.frozenWidth) * 10) / 10;
      rec2.rowUsed = row ? Math.round(row.used * 10) / 10 : null;
      rec2.rowInner = row ? Math.round(row.inner * 10) / 10 : null;
      rec2.lines = 1;
      rec2.fits = true;
      rec2.why = "the frozen width was Muller's text box, not a constraint - the row still fits, so the size is kept";
      changes.push(rec2);
      continue;
    }

    if (prevAutoResize) trySet(e.node, "textAutoResize", prevAutoResize);
    if (b.width != null) { try { e.node.resize(b.width, e.node.height); } catch (err) {} }

    var hApplied = b.size, ls = null;
    for (var hs = 1; hs <= 3; hs++) {
      if (!(await setSize(e.node, b.size - hs))) break;
      hApplied = b.size - hs;
      if (lineCountOf(e.node) <= 1) break;
    }
    if (lineCountOf(e.node) > 1) {
      for (var t = 1; t <= 4; t++) {
        ls = -0.1 * t;
        if (!trySet(e.node, "letterSpacing", { unit: "PIXELS", value: ls })) break;
        if (lineCountOf(e.node) <= 1) break;
      }
    }
    rec2.solution = "shrank";
    rec2.finalSize = hApplied;
    rec2.letterSpacing = ls;
    rec2.lines = lineCountOf(e.node);
    rec2.fits = rec2.lines <= 1;
    rec2.why = "growing the box would have collided with a neighbour, so the type came down instead";
    changes.push(rec2);
  }
}

/* ---------------------------------------------------------------- main -- */

async function checkFonts() {
  var avail = await figma.listAvailableFontsAsync();
  var have = {};
  for (var i = 0; i < avail.length; i++) {
    var f = avail[i].fontName;
    have[f.family + "|" + f.style] = true;
  }
  var report = [];
  var wanted = ["Light", "Regular", "Medium", "Bold", "Black"];
  for (var c = 0; c < CANDIDATES.length; c++) {
    var missing = [];
    for (var w = 0; w < wanted.length; w++) {
      if (!have[CANDIDATES[c] + "|" + wanted[w]]) missing.push(wanted[w]);
    }
    report.push({ family: CANDIDATES[c], missing: missing });
  }
  return report;
}

async function run(msg) {
  var problems = [], notes = [], stressChanges = [], rows = [];
  var roleTally = {}, ambiguous = [], seenAmbiguous = {}, fitChanges = [], sizeChanges = [];

  log("Loading pages…");
  if (typeof figma.loadAllPagesAsync === "function") await figma.loadAllPagesAsync();

  var fontReport = await checkFonts();
  var blocked = fontReport.filter(function (r) { return r.missing.indexOf("Regular") >= 0; });
  if (blocked.length) {
    figma.ui.postMessage({
      type: "error",
      text: "Missing font families in this file: " +
            blocked.map(function (b) { return b.family + " (" + b.missing.join(", ") + ")"; }).join("; ") +
            ".\nEnable them in Figma first - they are both on Google Fonts.",
    });
    return;
  }

  var stamp = new Date().toISOString().replace("T", " ").slice(0, 16);
  var page = figma.createPage();
  page.name = "FONT TRIAL " + stamp;
  log('Created page "' + page.name + '"');

  var columns = ["Muller (baseline)"].concat(CANDIDATES).concat([COLUMN_HYBRID]);
  var y = 0;

  for (var f = 0; f < FRAMES.length; f++) {
    var spec = FRAMES[f];
    var found = findFrame(spec.name);
    if (!found) {
      notes.push('frame "' + spec.name + '" not found in this document - skipped');
      continue;
    }
    log("Cloning " + spec.name + " (" + (f + 1) + "/" + FRAMES.length + ")…");

    var rowHeight = 0;
    var x = 0;
    var measured = [];

    for (var c = 0; c < columns.length; c++) {
      var clone = found.node.clone();
      page.appendChild(clone);
      clone.name = spec.name + " · " + columns[c];
      clone.x = x;
      clone.y = y;

      // Same stress strings on every column, so the columns stay comparable.
      if (spec.name === "history-content" && msg.stress) {
        await injectStress(clone, problems, c === 0 ? stressChanges : []);
      }

      if (c > 0) {
        var entries = collectText(clone, [], null);
        var hybrid = columns[c] === COLUMN_HYBRID;

        // Frozen geometry, captured before anything is written to the clone.
        var baselines = entries.map(function (e) {
          var out = { frame: spec.name, size: 0, text: "", lines: 1, width: null, buttonWidth: null };
          try { out.size = e.node.fontSize; out.text = String(e.node.characters).slice(0, 40); } catch (err) {}
          out.lines = lineCountOf(e.node);
          out.width = widthOf(e.node);
          if (e.button) { try { out.buttonWidth = e.button.width; } catch (err) {} }
          return out;
        });
        for (var t = 0; t < entries.length; t++) {
          var e = entries[t];
          var chars = "", size = 0;
          try { chars = e.node.characters; size = e.node.fontSize; } catch (err) {}

          var family, weightOverride = null, sizeOverride = null;
          var chars0size = size;
          if (hybrid) {
            var verdict = classify({ name: e.name, path: e.path, text: chars, size: size, frame: spec.name });
            family = verdict.family;
            weightOverride = verdict.weight;
            sizeOverride = verdict.size;
            e.role = verdict.role;
            roleTally[verdict.role] = roleTally[verdict.role] || { role: verdict.role, family: verdict.family, why: verdict.why, count: 0, samples: [] };
            roleTally[verdict.role].count++;
            if (roleTally[verdict.role].samples.length < 3) {
              roleTally[verdict.role].samples.push(spec.name + ": \"" + String(chars).slice(0, 28) + "\"");
            }
            if (verdict.ambiguous && !seenAmbiguous[verdict.role]) {
              seenAmbiguous[verdict.role] = true;
              ambiguous.push({ role: verdict.role, family: verdict.family, frame: spec.name,
                               sample: String(chars).slice(0, 40), note: verdict.ambiguous });
            }
          } else {
            family = CANDIDATES[c - 1];
          }

          await restyleNode(e.node, family, problems, c === 1 ? notes : [], weightOverride);

          if (hybrid && sizeOverride && sizeOverride !== chars0size) {
            if (await setSize(e.node, sizeOverride)) {
              e.appliedSize = sizeOverride;
              sizeChanges.push({ frame: spec.name, text: String(chars).slice(0, 40),
                                 role: e.role, from: chars0size, to: sizeOverride });
            }
          }
        }

        // Only the hybrid is corrected back onto the frozen geometry; the
        // single-family columns stay untouched so the raw effect stays visible.
        if (hybrid) await fitHybrid(entries, baselines, fitChanges);
      }

      measured.push({ column: columns[c], w: Math.round(clone.width * 100) / 100, h: Math.round(clone.height * 100) / 100 });
      x += clone.width + GAP;
      if (clone.height > rowHeight) rowHeight = clone.height;
    }

    var base = measured[0];
    rows.push({
      frame: spec.name,
      why: spec.why,
      page: found.page,
      columns: measured.map(function (m) {
        return {
          column: m.column,
          w: m.w,
          h: m.h,
          dh: Math.round((m.h - base.h) * 100) / 100,
          dw: Math.round((m.w - base.w) * 100) / 100,
        };
      }),
    });

    y += rowHeight + GAP * 2;
  }

  await figma.setCurrentPageAsync(page);

  figma.ui.postMessage({
    type: "result",
    page: page.name,
    fontReport: fontReport,
    rows: rows,
    notes: notes,
    problems: problems,
    stressChanges: stressChanges,
    roles: Object.keys(roleTally).map(function (k) { return roleTally[k]; }),
    ambiguous: ambiguous,
    fits: fitChanges,
    sizes: sizeChanges,
  });
}

figma.ui.onmessage = function (msg) {
  if (msg.type !== "run") return;
  run(msg).catch(function (error) {
    figma.ui.postMessage({ type: "error", text: String((error && error.stack) || error) });
  });
};
