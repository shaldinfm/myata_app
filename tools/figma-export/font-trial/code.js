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

function collectText(node, out) {
  var ty = "";
  try { ty = node.type; } catch (e) { return out; }
  if (ty === "TEXT") { out.push(node); return out; }
  var kids;
  try { kids = node.children; } catch (e) { return out; }
  if (kids) for (var i = 0; i < kids.length; i++) collectText(kids[i], out);
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

async function restyleNode(textNode, family, problems, notes) {
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

    var target = { family: family, style: style };
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
  var texts = collectText(frame, []);
  var picked = 0;
  for (var i = 0; i < texts.length && picked < STRESS.length; i++) {
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
      t.characters = STRESS[picked];
      changed.push({ node: describe(t), from: old, to: STRESS[picked] });
      picked++;
    } catch (error) {
      problems.push({ node: describe(t), property: "characters", error: String(error.message || error) });
    }
  }
  return picked;
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

  var columns = ["Muller (baseline)"].concat(CANDIDATES);
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
        var texts = collectText(clone, []);
        for (var t = 0; t < texts.length; t++) {
          await restyleNode(texts[t], CANDIDATES[c - 1], problems, c === 1 ? notes : []);
        }
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
  });
}

figma.ui.onmessage = function (msg) {
  if (msg.type !== "run") return;
  run(msg).catch(function (error) {
    figma.ui.postMessage({ type: "error", text: String((error && error.stack) || error) });
  });
};
