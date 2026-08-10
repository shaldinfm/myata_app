/*
 * Radio Myata - BottomNav icon vectors (read-only)
 *
 * The canonical snapshot exporter records a VECTOR's name, size and fills but
 * never its geometry, so the frozen bottom-navigation icons cannot be rebuilt
 * from anything committed to the repo. This plugin reads `vectorPaths` off the
 * exact icon nodes and emits both the raw geometry and ready-to-paste Android
 * VectorDrawable XML.
 *
 * It is strictly read-only: it resolves nodes by id and reads properties. There
 * is no create, no mutation and no network access.
 *
 * Each icon is read in more than one place on purpose - active and inactive, and
 * light and dark - so the output answers a question the metadata could not: does
 * the geometry actually change between states, or only the fill colour?
 */

figma.showUI(__html__, { width: 460, height: 560 });

// Node ids come from the frozen canonical pages. Active is identified by the
// #00723d pill content colour, inactive by #42474e (light) / #b3c4d1 (dark).
var TARGETS = [
  { icon: "home",       state: "active",   theme: "light", page: "HOME",             id: "2393:1632" },
  { icon: "home",       state: "inactive", theme: "light", page: "PLAYER",           id: "2396:30917" },
  { icon: "home",       state: "active",   theme: "dark",  page: "HOME_dark",        id: "2444:10354" },

  { icon: "player",     state: "active",   theme: "light", page: "PLAYER",           id: "2396:30922" },
  { icon: "player",     state: "inactive", theme: "light", page: "HOME",             id: "2393:1637" },
  { icon: "player",     state: "active",   theme: "dark",  page: "PLAYER_dark",      id: "2444:18363" },

  { icon: "collection", state: "active",   theme: "light", page: "COLLECTION",       id: "2407:31515" },
  { icon: "collection", state: "inactive", theme: "light", page: "HOME",             id: "2393:1642" },
  { icon: "collection", state: "active",   theme: "dark",  page: "COLLECTION_dark",  id: "2444:18443" },

  { icon: "about",      state: "active",   theme: "light", page: "ABOUT US",         id: "2417:95" },
  { icon: "about",      state: "inactive", theme: "light", page: "HOME",             id: "2393:1647" },
  { icon: "about",      state: "active",   theme: "dark",  page: "ABOUT US_dark",    id: "2444:18666" }
];

// Android drawable each icon is destined for, so the output names itself.
var DRAWABLE = {
  home:       "ic_nav_home",
  player:     "ic_nav_player",
  collection: "ic_nav_collection",
  about:      "ic_nav_about"
};

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

function readNode(node) {
  var paths = [];
  if (node.vectorPaths && node.vectorPaths.length) {
    for (var i = 0; i < node.vectorPaths.length; i++) {
      paths.push({
        windingRule: node.vectorPaths[i].windingRule,
        data: node.vectorPaths[i].data
      });
    }
  }
  return {
    name: node.name,
    type: node.type,
    width: round(node.width),
    height: round(node.height),
    fill: solidFill(node.fills),
    stroke: solidFill(node.strokes),
    strokeWeight: node.strokeWeight === figma.mixed ? "MIXED" : round(node.strokeWeight),
    strokeAlign: node.strokeAlign || null,
    strokeCap: typeof node.strokeCap === "string" ? node.strokeCap : null,
    strokeJoin: typeof node.strokeJoin === "string" ? node.strokeJoin : null,
    vectorPaths: paths
  };
}

/*
 * Android VectorDrawable. The viewport is the node's own size so the path data
 * needs no transform, and the colour is left as a resource reference: the bar
 * tints its icons at runtime, so a baked-in fill would fight the active state.
 *
 * fillType is only emitted for EVENODD. It needs API 24 on a platform
 * VectorDrawable, and this project's minSdk is 21 - the UI flags that case
 * rather than emitting XML that silently renders wrong on old devices.
 */
function vectorDrawable(icon, read) {
  var lines = [];
  lines.push("<?xml version=\"1.0\" encoding=\"utf-8\"?>");
  lines.push("<!-- Frozen 3.6.6 canonical BottomNav icon: " + icon + ". Extracted, not redrawn. -->");
  lines.push("<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"");
  lines.push("    android:width=\"" + read.width + "dp\"");
  lines.push("    android:height=\"" + read.height + "dp\"");
  lines.push("    android:viewportWidth=\"" + read.width + "\"");
  lines.push("    android:viewportHeight=\"" + read.height + "\">");
  for (var i = 0; i < read.vectorPaths.length; i++) {
    var p = read.vectorPaths[i];
    lines.push("    <path");
    lines.push("        android:pathData=\"" + p.data + "\"");
    if (read.stroke && !read.fill) {
      lines.push("        android:strokeColor=\"#FFFFFF\"");
      lines.push("        android:strokeWidth=\"" + read.strokeWeight + "\"");
      if (read.strokeCap) lines.push("        android:strokeLineCap=\"" + read.strokeCap.toLowerCase() + "\"");
      if (read.strokeJoin) lines.push("        android:strokeLineJoin=\"" + read.strokeJoin.toLowerCase() + "\"");
    } else {
      lines.push("        android:fillColor=\"#FFFFFF\"");
      if (p.windingRule === "EVENODD") lines.push("        android:fillType=\"evenOdd\"");
    }
    lines.push("        />");
  }
  lines.push("</vector>");
  return lines.join("\n");
}

function samePaths(a, b) {
  if (!a || !b || a.length !== b.length) return false;
  for (var i = 0; i < a.length; i++) {
    if (a[i].data !== b[i].data || a[i].windingRule !== b[i].windingRule) return false;
  }
  return true;
}

figma.ui.onmessage = function (msg) {
  if (msg.type !== "extract") return;

  figma.ui.postMessage({ type: "status", text: "Resolving " + TARGETS.length + " icon nodes…" });

  var reads = [];
  var missing = [];

  // The targets are spread over several pages. Under dynamic page loading an id
  // on an unopened page resolves to null, which would look like a missing node
  // rather than an unloaded one.
  var ready = typeof figma.loadAllPagesAsync === "function"
    ? figma.loadAllPagesAsync()
    : Promise.resolve();

  function step(i) {
    if (i >= TARGETS.length) return finish();
    var t = TARGETS[i];
    return figma.getNodeByIdAsync(t.id).then(function (node) {
      if (!node) {
        missing.push(t.id + " (" + t.icon + "/" + t.state + "/" + t.theme + ")");
      } else if (node.type !== "VECTOR") {
        missing.push(t.id + " is " + node.type + ", expected VECTOR");
      } else {
        var r = readNode(node);
        if (!r.vectorPaths.length) missing.push(t.id + " has no vectorPaths");
        reads.push({ target: t, read: r });
      }
      return step(i + 1);
    });
  }

  function finish() {
    // Does geometry actually differ by state or theme, or is it colour only?
    var byIcon = {};
    var drawables = {};
    var notes = [];

    for (var i = 0; i < reads.length; i++) {
      var icon = reads[i].target.icon;
      if (!byIcon[icon]) byIcon[icon] = [];
      byIcon[icon].push(reads[i]);
    }

    Object.keys(byIcon).forEach(function (icon) {
      var group = byIcon[icon];
      var base = group[0];
      var stateDiffers = false;
      var themeDiffers = false;

      for (var j = 1; j < group.length; j++) {
        if (samePaths(base.read.vectorPaths, group[j].read.vectorPaths)) continue;
        if (group[j].target.theme !== base.target.theme) themeDiffers = true;
        else stateDiffers = true;
      }

      if (stateDiffers) {
        notes.push(icon + ": geometry DIFFERS between active and inactive - two drawables needed.");
      } else {
        notes.push(icon + ": one geometry for both states; only the fill colour changes.");
      }
      if (themeDiffers) notes.push(icon + ": geometry differs between light and dark - unexpected, review before use.");

      var evenOdd = base.read.vectorPaths.some(function (p) { return p.windingRule === "EVENODD"; });
      if (evenOdd) {
        notes.push(icon + ": uses EVENODD winding. android:fillType needs API 24 and minSdk here is 21 - " +
                          "load it through AppCompat (app:srcCompat) or the shape renders wrong on API 21-23.");
      }

      drawables[DRAWABLE[icon] + ".xml"] = vectorDrawable(icon, base.read);
    });

    var payload = {
      generatedAt: new Date().toISOString(),
      document: figma.root.name,
      note: "Read-only extraction of the frozen canonical BottomNav icon geometry. " +
            "Colours are deliberately left as #FFFFFF placeholders: the bar tints icons at runtime.",
      missing: missing,
      findings: notes,
      icons: reads.map(function (r) {
        return {
          icon: r.target.icon,
          state: r.target.state,
          theme: r.target.theme,
          page: r.target.page,
          nodeId: r.target.id,
          drawable: DRAWABLE[r.target.icon],
          geometry: r.read
        };
      }),
      drawables: drawables
    };

    figma.ui.postMessage({ type: "result", payload: payload, missing: missing, findings: notes });
  }

  ready.then(function () { return step(0); }).catch(function (error) {
    figma.ui.postMessage({ type: "error", text: String((error && error.stack) || error) });
  });
};
