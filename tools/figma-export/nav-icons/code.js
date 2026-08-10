/*
 * Radio Myata - BottomNav icon vectors (read-only)
 *
 * The canonical snapshot exporter records a VECTOR's name, size and fills but
 * never its geometry, so the frozen bottom-navigation icons cannot be rebuilt
 * from anything committed to the repo. This plugin reads the geometry off the
 * icon nodes and emits ready-to-paste Android VectorDrawable XML.
 *
 * Strictly read-only: it loads pages, resolves nodes and reads properties. There
 * is no create, no mutation, no network access.
 *
 * Geometry comes from exportAsync(SVG) rather than from `vectorPaths`. SVG
 * export is exact, and unlike vectorPaths it also works when an icon is a
 * boolean operation, a group or a frame - which is what an owner-edited page can
 * easily contain. vectorPaths is still read when available, because its winding
 * rule maps onto android:fillType.
 */

figma.showUI(__html__, { width: 480, height: 600 });

// Node ids from the frozen canonical pages. Active carries the #00723d pill
// content colour; inactive carries #42474e (light) or #b3c4d1 (dark).
var TARGETS = [
  { icon: "home",       state: "active",   theme: "light", page: "HOME",            index: 1, id: "2393:1632" },
  { icon: "home",       state: "inactive", theme: "light", page: "PLAYER",          index: 1, id: "2396:30917" },
  { icon: "home",       state: "inactive", theme: "dark",  page: "HOME_dark",       index: 1, id: "2444:10354" },

  { icon: "player",     state: "active",   theme: "light", page: "PLAYER",          index: 2, id: "2396:30922" },
  { icon: "player",     state: "inactive", theme: "light", page: "HOME",            index: 2, id: "2393:1637" },
  { icon: "player",     state: "active",   theme: "dark",  page: "PLAYER_dark",     index: 2, id: "2444:18363" },

  { icon: "collection", state: "active",   theme: "light", page: "COLLECTION",      index: 3, id: "2407:31515" },
  { icon: "collection", state: "inactive", theme: "light", page: "HOME",            index: 3, id: "2393:1642" },
  { icon: "collection", state: "active",   theme: "dark",  page: "COLLECTION_dark", index: 3, id: "2444:18443" },

  { icon: "about",      state: "active",   theme: "light", page: "ABOUT US",        index: 4, id: "2417:95" },
  { icon: "about",      state: "inactive", theme: "light", page: "HOME",            index: 4, id: "2393:1647" },
  { icon: "about",      state: "active",   theme: "dark",  page: "ABOUT US_dark",   index: 4, id: "2444:18666" }
];

var DRAWABLE = {
  home:       "ic_nav_home",
  player:     "ic_nav_player",
  collection: "ic_nav_collection",
  about:      "ic_nav_about"
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
// failure. This walks a page to its BottomNavBar and takes the Nth item's first
// geometry-bearing descendant, which is how the ids were derived to begin with.
function findIconByStructure(pageName, index, problems) {
  var pages = figma.root.children;
  var page = null;
  for (var i = 0; i < pages.length; i++) {
    if (pages[i].name === pageName) { page = pages[i]; break; }
  }
  if (!page) {
    for (var j = 0; j < pages.length; j++) {
      var hit = findFrameNamed(pages[j], pageName);
      if (hit) { page = pages[j]; break; }
    }
  }
  if (!page) return null;

  var bar = findBarIn(page);
  if (!bar) return null;

  var children = null;
  var got = safe(bar, "children", problems);
  if (!got.ok || !got.value) return null;
  children = got.value;
  if (index > children.length) return null;

  return firstGeometry(children[index - 1]);
}

function findFrameNamed(node, name) {
  if (node.name === name) return node;
  var kids = null;
  try { kids = node.children; } catch (e) { return null; }
  if (!kids) return null;
  for (var i = 0; i < kids.length; i++) {
    var hit = findFrameNamed(kids[i], name);
    if (hit) return hit;
  }
  return null;
}

function findBarIn(node) {
  var name = "";
  try { name = node.name || ""; } catch (e) { name = ""; }
  if (/bottomnav/i.test(name)) return node;
  var kids = null;
  try { kids = node.children; } catch (e) { return null; }
  if (!kids) return null;
  for (var i = 0; i < kids.length; i++) {
    var hit = findBarIn(kids[i]);
    if (hit) return hit;
  }
  return null;
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
  lines.push("<!-- Frozen 3.6.6 canonical BottomNav icon: " + icon + ". Extracted from Figma, not redrawn. -->");
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
      node = findIconByStructure(t.page, t.index, problems);
      resolvedBy = node ? "structure (" + t.page + " > BottomNavBar > item " + t.index + ")" : "unresolved";
    }
    if (!node) {
      missing.push(t.icon + "/" + t.state + "/" + t.theme + ": id " + t.id +
                   " did not resolve, and no BottomNavBar item " + t.index + " was found on page \"" + t.page + "\"");
      continue;
    }

    log("Reading " + t.icon + " (" + t.state + ", " + t.theme + ") - " + describe(node));

    var svg = await exportSvg(node, problems);
    var parsed = parseSvg(svg);
    if (!parsed || !parsed.paths.length) {
      missing.push(t.icon + "/" + t.state + "/" + t.theme + ": " + describe(node) + " produced no SVG path data");
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
    var stateDiffers = false;
    var themeDiffers = false;

    for (var j = 1; j < group.length; j++) {
      if (samePaths(base.svg.paths, group[j].svg.paths)) continue;
      if (group[j].target.theme !== base.target.theme) themeDiffers = true;
      else stateDiffers = true;
    }

    notes.push(stateDiffers
      ? icon + ": geometry DIFFERS between active and inactive - two drawables needed."
      : icon + ": one geometry for both states; only the fill colour changes.");
    if (themeDiffers) notes.push(icon + ": geometry differs between light and dark - unexpected, review before use.");

    var evenOdd = base.svg.paths.some(function (p) { return p.fillRule === "evenodd"; });
    if (evenOdd) {
      notes.push(icon + ": uses even-odd winding. android:fillType needs API 24 and minSdk here is 21 - " +
                        "load it through AppCompat (app:srcCompat) or it renders wrong on API 21-23.");
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
    note: "Read-only extraction of the frozen canonical BottomNav icon geometry. Colours are #FFFFFF " +
          "placeholders: the bar tints its icons at runtime.",
    missing: missing,
    problems: problems,
    findings: notes,
    icons: reads.map(function (x) {
      return {
        icon: x.target.icon,
        state: x.target.state,
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
