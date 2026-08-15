/*
 * Radio Myata - PLAYER icon vectors (read-only)
 *
 * The canonical snapshot exporter records a VECTOR's name, size and fills but
 * never its geometry, so every icon on the frozen PLAYER screen reached the app
 * as an exact box around a reconstructed outline. This plugin reads the real
 * geometry off those nodes and emits ready-to-paste Android VectorDrawable XML,
 * so the reconstructions can be replaced with the literal paths.
 *
 * Same machinery as ../nav-icons, which did this for the bottom navigation:
 * geometry comes from exportAsync(SVG) rather than vectorPaths, because SVG
 * export is exact and also works when an icon is a boolean operation, a group or
 * a frame. vectorPaths is still read when available, for its winding rule.
 *
 * Strictly read-only: it loads pages, resolves nodes and reads properties. There
 * is no create, no mutation, no network access.
 *
 * Ids come from tools/figma-export/canonical/figma-canonical-{light,dark}-final.json
 * and are listed in docs/PLAYER-3.6.6.md. They are read off the *visible*
 * `Controls` row - each frozen page carries a second, hidden copy of that row
 * with stale values in it. If an id has gone stale, the structural fallback
 * walks the page to the same node by name instead.
 */

figma.showUI(__html__, { width: 480, height: 600 });

/*
 * icon   what it is
 * path   the node's name trail inside the page, for the fallback
 * nth    which match to take where a trail step repeats
 * id     the node id on that page
 *
 * `play/pause` carries ONE glyph node per page: the frozen frame shows a single
 * state, and there is no second node for the other one. Whichever it is comes
 * back here, 23.33 square either way, and the emitted drawable is named for what
 * the geometry turns out to be.
 */
var TARGETS = [
  { icon: "play_pause",     theme: "light", page: "PLAYER",      id: "2399:31221",
    path: ["Controls", "play/pause", "Container", "Icon"] },
  { icon: "play_pause",     theme: "dark",  page: "PLAYER_dark", id: "2444:18274",
    path: ["Controls", "play/pause", "Container", "Icon"] },

  { icon: "like",           theme: "light", page: "PLAYER",      id: "2399:31217",
    path: ["Controls", "like", "Container", "Icon"] },
  { icon: "like",           theme: "dark",  page: "PLAYER_dark", id: "2444:18270",
    path: ["Controls", "like", "Container", "Icon"] },

  { icon: "swipe_active",   theme: "light", page: "PLAYER",      id: "I2402:31398;58548:7288",
    path: ["swipe", "Shape Set", "shape"], nth: 1 },
  { icon: "swipe_active",   theme: "dark",  page: "PLAYER_dark", id: "I2444:18241;58548:7288",
    path: ["swipe", "Shape Set", "shape"], nth: 1 },

  { icon: "swipe_inactive", theme: "light", page: "PLAYER",      id: "I2402:31378;58548:7250",
    path: ["swipe", "Shape Set", "shape"], nth: 2 },
  { icon: "swipe_inactive", theme: "dark",  page: "PLAYER_dark", id: "I2444:18242;58548:7250",
    path: ["swipe", "Shape Set", "shape"], nth: 2 },

  /* Deferred in the app, exported anyway so the geometry is on hand when their
     phases arrive. */
  { icon: "dislike",        theme: "light", page: "PLAYER",      id: "2399:31224",
    path: ["Controls", "dislike", "Container", "Icon"] },
  { icon: "dislike",        theme: "dark",  page: "PLAYER_dark", id: "2444:18277",
    path: ["Controls", "dislike", "Container", "Icon"] },

  { icon: "overflow",       theme: "light", page: "PLAYER",      id: "2396:30741",
    path: ["Mobile Header (Subtle)", "Button:margin", "Button", "Container", "Icon"], nth: 2 },
  { icon: "overflow",       theme: "dark",  page: "PLAYER_dark", id: "2444:18239",
    path: ["Mobile Header (Subtle)", "Button:margin", "Button", "Container", "Icon"], nth: 2 }
];

var DRAWABLE = {
  play_pause:     "ic_player_play",
  like:           "ic_player_like",
  swipe_active:   "ic_player_swipe_active",
  swipe_inactive: "ic_player_swipe_inactive",
  dislike:        "ic_player_dislike",
  overflow:       "ic_player_overflow"
};

var GEOMETRY_TYPES = ["VECTOR", "BOOLEAN_OPERATION", "STAR", "POLYGON", "ELLIPSE", "RECTANGLE", "LINE", "FRAME", "GROUP", "COMPONENT", "INSTANCE"];

function log(text) {
  figma.ui.postMessage({ type: "status", text: text });
}

function describe(node) {
  // Deliberately only id/type/name: these stay readable even when richer
  // property access is refused, so an error report never fails while reporting.
  var name = "?";
  try { name = node.name; } catch (e) { name = "<name unreadable>"; }
  var type = "?";
  try { type = node.type; } catch (e) { type = "<type unreadable>"; }
  var id = "?";
  try { id = node.id; } catch (e) { id = "<id unreadable>"; }
  return id + " \"" + name + "\" (" + type + ")";
}

// Every property read goes through here, so a refusal names the node and the
// property instead of surfacing as a bare stack trace.
function safe(node, prop, problems) {
  try {
    return { ok: true, value: node[prop] };
  } catch (error) {
    problems.push({
      node: describe(node),
      property: prop,
      error: String((error && error.message) || error)
    });
    return { ok: false, value: null };
  }
}

function round(n) {
  return typeof n === "number" ? Math.round(n * 1000) / 1000 : n;
}

function hex(color) {
  function c(v) {
    var s = Math.round(v * 255).toString(16);
    return s.length === 1 ? "0" + s : s;
  }
  return "#" + c(color.r) + c(color.g) + c(color.b);
}

function solidFill(paints) {
  if (!paints || paints === figma.mixed || !paints.length) return null;
  for (var i = 0; i < paints.length; i++) {
    if (paints[i].type === "SOLID" && paints[i].visible !== false) return hex(paints[i].color);
  }
  return null;
}

/* ---------------------------------------------------------------- loading -- */

/*
 * Under dynamic page loading, getNodeByIdAsync resolves a node whose id, type
 * and name are readable while everything else throws on access. That is exactly
 * how the first version of this plugin failed: it treated loadAllPagesAsync as
 * optional and, when the API was absent, silently proceeded to read vectorPaths
 * off unloaded nodes. Loading is now mandatory and its mechanism is reported.
 */
async function loadEveryPage() {
  if (typeof figma.loadAllPagesAsync === "function") {
    await figma.loadAllPagesAsync();
    return "figma.loadAllPagesAsync()";
  }

  // Probing for the per-page loader is itself a property read, and on an
  // unloaded node a property read can throw - which is the very failure this
  // function exists to prevent. So every access here is guarded.
  var pages = [];
  try {
    pages = figma.root.children || [];
  } catch (error) {
    return "could not enumerate pages: " + String((error && error.message) || error);
  }

  var loaded = 0;
  var failed = 0;
  for (var i = 0; i < pages.length; i++) {
    try {
      if (typeof pages[i].loadAsync === "function") {
        await pages[i].loadAsync();
        loaded++;
      }
    } catch (error) {
      failed++;
    }
  }
  if (loaded) return "page.loadAsync() x" + loaded + (failed ? " (" + failed + " refused)" : "");
  return "no loading API available - document assumed already loaded";
}

/* -------------------------------------------------------------- discovery -- */

// Owner edits can renumber nodes, so a stale id must not be the single point of
// failure. This walks the named page down the node trail the canonical export
// records, taking the nth match where a trail step repeats - the swipe row has
// three `Shape Set`/`shape` children and the header two `Button:margin`.
function findIconByStructure(target, problems) {
  var page = findPage(target.page);
  if (!page) return null;

  var node = findFrameNamed(page, target.page) || page;
  for (var i = 0; i < target.path.length; i++) {
    var nth = (i === target.path.length - 1 || target.path[i] === "Shape Set" || target.path[i] === "Button:margin")
      ? (target.nth || 1) : 1;
    node = childNamed(node, target.path[i], nth, problems);
    if (!node) return null;
  }
  return firstGeometry(node) || node;
}

function findPage(name) {
  var pages = figma.root.children;
  for (var i = 0; i < pages.length; i++) if (pages[i].name === name) return pages[i];
  for (var j = 0; j < pages.length; j++) if (findFrameNamed(pages[j], name)) return pages[j];
  return null;
}

// The nth *visible* descendant with that name, depth first. Invisible ones are
// skipped on purpose: each frozen page carries a hidden, stale `Controls` row,
// and the header's leading Button is hidden too.
function childNamed(root, name, nth, problems) {
  var seen = 0;
  var found = null;
  (function walk(node) {
    if (found) return;
    var got = safe(node, "children", problems);
    if (!got.ok || !got.value) return;
    var kids = got.value;
    for (var i = 0; i < kids.length; i++) {
      var visible = true;
      try { visible = kids[i].visible !== false; } catch (e) { visible = true; }
      if (!visible) continue;
      var kidName = "";
      try { kidName = kids[i].name || ""; } catch (e) { kidName = ""; }
      if (kidName === name) {
        seen++;
        if (seen === nth) { found = kids[i]; return; }
      }
      walk(kids[i]);
      if (found) return;
    }
  })(root);
  return found;
}

function firstGeometry(node) {
  if (!node) return null;
  var type = "";
  try { type = node.type; } catch (e) { return null; }
  if (type === "VECTOR" || type === "BOOLEAN_OPERATION") return node;
  var kids = null;
  try { kids = node.children; } catch (e) { kids = null; }
  if (kids) {
    for (var i = 0; i < kids.length; i++) {
      var hit = firstGeometry(kids[i]);
      if (hit) return hit;
    }
  }
  return null;
}

/* ------------------------------------------------------------------- SVG -- */

function decodeUtf8(bytes) {
  if (typeof bytes === "string") return bytes;
  var s = "";
  for (var i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]);
  try { return decodeURIComponent(escape(s)); } catch (e) { return s; }
}

async function exportSvg(node, problems) {
  // SVG_STRING is not in every API version; SVG returns bytes and decodes to the
  // same document.
  try {
    return decodeUtf8(await node.exportAsync({ format: "SVG_STRING" }));
  } catch (first) {
    try {
      return decodeUtf8(await node.exportAsync({ format: "SVG" }));
    } catch (second) {
      problems.push({
        node: describe(node),
        property: "exportAsync(SVG)",
        error: String((second && second.message) || second) + " (SVG_STRING first failed with: " +
               String((first && first.message) || first) + ")"
      });
      return null;
    }
  }
}

function attr(tag, name) {
  var m = new RegExp(name + '="([^"]*)"').exec(tag);
  return m ? m[1] : null;
}

function parseSvg(svg) {
  if (!svg) return null;
  var open = /<svg\b[^>]*>/.exec(svg);
  var head = open ? open[0] : "";
  var viewBox = attr(head, "viewBox");
  var paths = [];
  var re = /<path\b[^>]*\/?>/g;
  var m;
  while ((m = re.exec(svg)) !== null) {
    var tag = m[0];
    var d = attr(tag, "d");
    if (!d) continue;
    paths.push({
      d: d,
      fill: attr(tag, "fill"),
      fillRule: attr(tag, "fill-rule") || attr(tag, "clip-rule"),
      stroke: attr(tag, "stroke"),
      strokeWidth: attr(tag, "stroke-width"),
      strokeLinecap: attr(tag, "stroke-linecap"),
      strokeLinejoin: attr(tag, "stroke-linejoin")
    });
  }
  return {
    viewBox: viewBox,
    width: attr(head, "width"),
    height: attr(head, "height"),
    paths: paths,
    hasGroupTransform: /<g\b[^>]*transform="/.test(svg),
    raw: svg
  };
}

/* --------------------------------------------------------- VectorDrawable -- */

/*
 * Colours are emitted as #FFFFFF placeholders on purpose: MainActivity tints the
 * icons at runtime for active and inactive, so a colour baked into the drawable
 * would fight that.
 */
function vectorDrawable(icon, parsed, size) {
  var vb = (parsed.viewBox || "").trim().split(/[\s,]+/);
  var vbW = vb.length === 4 ? parseFloat(vb[2]) : size.w;
  var vbH = vb.length === 4 ? parseFloat(vb[3]) : size.h;

  var lines = [];
  lines.push('<?xml version="1.0" encoding="utf-8"?>');
  lines.push("<!-- Frozen 3.6.6 canonical PLAYER icon: " + icon + ". Extracted from Figma, not redrawn. -->");
  lines.push('<vector xmlns:android="http://schemas.android.com/apk/res/android"');
  lines.push('    android:width="' + round(size.w) + 'dp"');
  lines.push('    android:height="' + round(size.h) + 'dp"');
  lines.push('    android:viewportWidth="' + round(vbW) + '"');
  lines.push('    android:viewportHeight="' + round(vbH) + '">');
  for (var i = 0; i < parsed.paths.length; i++) {
    var p = parsed.paths[i];
    lines.push("    <path");
    lines.push('        android:pathData="' + p.d + '"');
    if (p.stroke && p.stroke !== "none" && (!p.fill || p.fill === "none")) {
      lines.push('        android:strokeColor="#FFFFFF"');
      if (p.strokeWidth) lines.push('        android:strokeWidth="' + p.strokeWidth + '"');
      if (p.strokeLinecap) lines.push('        android:strokeLineCap="' + p.strokeLinecap + '"');
      if (p.strokeLinejoin) lines.push('        android:strokeLineJoin="' + p.strokeLinejoin + '"');
    } else {
      lines.push('        android:fillColor="#FFFFFF"');
      if (p.fillRule === "evenodd") lines.push('        android:fillType="evenOdd"');
    }
    lines.push("        />");
  }
  lines.push("</vector>");
  return lines.join("\n");
}

function samePaths(a, b) {
  if (!a || !b || a.length !== b.length) return false;
  for (var i = 0; i < a.length; i++) if (a[i].d !== b[i].d) return false;
  return true;
}

/* ------------------------------------------------------------------ main -- */

async function extract() {
  var problems = [];
  var missing = [];
  var reads = [];

  log("Loading pages…");
  var loadedVia = await loadEveryPage();
  log("Pages loaded via " + loadedVia + ". Reading " + TARGETS.length + " icon nodes…");

  for (var i = 0; i < TARGETS.length; i++) {
    var t = TARGETS[i];
    var resolvedBy = "id";
    var node = null;

    try {
      node = await figma.getNodeByIdAsync(t.id);
    } catch (error) {
      problems.push({ node: t.id, property: "getNodeByIdAsync", error: String((error && error.message) || error) });
      node = null;
    }

    // A stale id must not end the run: fall back to walking the page structure.
    if (node) {
      var typeRead = safe(node, "type", problems);
      if (!typeRead.ok || GEOMETRY_TYPES.indexOf(typeRead.value) === -1) node = null;
    }
    if (!node) {
      node = findIconByStructure(t, problems);
      resolvedBy = node ? "structure (" + t.page + " > " + t.path.join(" > ") + ")" : "unresolved";
    }
    if (!node) {
      missing.push(t.icon + "/" + t.theme + ": id " + t.id + " did not resolve, and the trail " +
                   t.path.join(" > ") + " was not found on page \"" + t.page + "\"");
      continue;
    }

    log("Reading " + t.icon + " (" + t.theme + ") - " + describe(node));

    var svg = await exportSvg(node, problems);
    var parsed = parseSvg(svg);
    if (!parsed || !parsed.paths.length) {
      missing.push(t.icon + "/" + t.theme + ": " + describe(node) + " produced no SVG path data");
      continue;
    }

    var w = safe(node, "width", problems);
    var h = safe(node, "height", problems);
    var fills = safe(node, "fills", problems);
    var strokes = safe(node, "strokes", problems);
    var strokeWeight = safe(node, "strokeWeight", problems);

    // Read for its winding rule; absent on boolean ops and groups, which is fine
    // because the SVG already carries fill-rule.
    var vectorPaths = [];
    var vp = safe(node, "vectorPaths", problems);
    if (vp.ok && vp.value && vp.value.length) {
      for (var k = 0; k < vp.value.length; k++) {
        vectorPaths.push({ windingRule: vp.value[k].windingRule, data: vp.value[k].data });
      }
    }

    reads.push({
      target: t,
      resolvedBy: resolvedBy,
      node: describe(node),
      size: { w: w.ok ? round(w.value) : null, h: h.ok ? round(h.value) : null },
      fill: fills.ok ? solidFill(fills.value) : null,
      stroke: strokes.ok ? solidFill(strokes.value) : null,
      strokeWeight: strokeWeight.ok && strokeWeight.value !== figma.mixed ? round(strokeWeight.value) : null,
      svg: parsed
    });
  }

  // --- findings -------------------------------------------------------------
  var byIcon = {};
  for (var r = 0; r < reads.length; r++) {
    var key = reads[r].target.icon;
    if (!byIcon[key]) byIcon[key] = [];
    byIcon[key].push(reads[r]);
  }

  var notes = [];
  var drawables = {};

  Object.keys(byIcon).forEach(function (icon) {
    var group = byIcon[icon];
    var base = group[0];
    var themeDiffers = false;

    for (var j = 1; j < group.length; j++) {
      if (!samePaths(base.svg.paths, group[j].svg.paths)) themeDiffers = true;
    }

    notes.push(themeDiffers
      ? icon + ": geometry DIFFERS between light and dark - unexpected for an icon, review before use."
      : icon + ": one geometry for both themes; only the fill colour changes.");

    var evenOdd = base.svg.paths.some(function (p) { return p.fillRule === "evenodd"; });
    if (evenOdd) {
      notes.push(icon + ": uses even-odd winding. android:fillType needs API 24; this project's minSdk is " +
                        "24, so it is supported natively - no AppCompat workaround required.");
    }
    if (base.svg.hasGroupTransform) {
      notes.push(icon + ": its SVG carries a group transform; check the drawable against the frame before trusting it.");
    }

  drawables[DRAWABLE[icon] + ".xml"] = vectorDrawable(icon, base.svg, {
      w: base.size.w || parseFloat(base.svg.width) || 24,
      h: base.size.h || parseFloat(base.svg.height) || 24
    });
  });

  return {
    generatedAt: new Date().toISOString(),
    document: figma.root.name,
    pagesLoadedVia: loadedVia,
    note: "Read-only extraction of the frozen canonical PLAYER icon geometry. Colours are #FFFFFF " +
          "placeholders: the screen tints its icons at runtime, by role and by state.",
    missing: missing,
    problems: problems,
    findings: notes,
    icons: reads.map(function (x) {
      return {
        icon: x.target.icon,
        theme: x.target.theme,
        page: x.target.page,
        expectedNodeId: x.target.id,
        resolvedBy: x.resolvedBy,
        node: x.node,
        size: x.size,
        fill: x.fill,
        stroke: x.stroke,
        strokeWeight: x.strokeWeight,
        viewBox: x.svg.viewBox,
        paths: x.svg.paths,
        svg: x.svg.raw
      };
    }),
    drawables: drawables
  };
}

figma.ui.onmessage = function (msg) {
  if (msg.type !== "extract") return;
  extract().then(function (payload) {
    figma.ui.postMessage({
      type: "result",
      payload: payload,
      missing: payload.missing,
      problems: payload.problems,
      findings: payload.findings,
      count: payload.icons.length,
      total: TARGETS.length
    });
  }).catch(function (error) {
    figma.ui.postMessage({
      type: "error",
      text: String((error && error.stack) || (error && error.message) || error)
    });
  });
};
