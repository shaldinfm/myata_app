/* eslint-disable */
/*
 * Radio Myata - create 3.6.6 proposal frames.
 *
 * Safety model, same as the canonical repair plugin:
 *   - Dry Run performs no writes at all.
 *   - Apply runs only what Dry Run marked READY, in this session.
 *   - Every frame comes from SCREEN_SPEC, which is inlined at build time. The
 *     plugin cannot discover its own targets and never reads a node id.
 *   - It writes to two dedicated proposal pages and REFUSES to touch a
 *     canonical page, so the exported baselines stay comparable.
 *   - Re-running skips screens that already exist by name, so a partial Apply
 *     is safe to resume.
 */

var THEMES = ["light", "dark"];
var CANONICAL_PAGES = ["CURRENT ANDROID UI — DARK", "CURRENT ANDROID UI - LIGHT"];
var FONT_FAMILY = "Muller";
var FONT_STYLES = ["Regular", "Medium", "Bold"];
var GRID_GAP_X = 80, GRID_GAP_Y = 120, GRID_WRAP = 3200;

var dryRunReport = null;   // set by dryRun(), consumed by apply()
var missingAssets = [];    // asset keys that resolved to an empty slot during apply()

/* ---------- helpers ---------- */

function hexToRgb(hex) {
  return {
    r: parseInt(hex.substr(1, 2), 16) / 255,
    g: parseInt(hex.substr(3, 2), 16) / 255,
    b: parseInt(hex.substr(5, 2), 16) / 255
  };
}

function resolve(token, theme) {
  if (token == null) return null;
  if (token.charAt(0) === "#") return token;
  var t = SCREEN_SPEC.tokens[token];
  if (!t) throw new Error("unknown token: " + token);
  return t[theme];
}

function solid(hex) { return [{ type: "SOLID", color: hexToRgb(hex), opacity: 1 }]; }

function allPages() {
  if (typeof figma.loadAllPagesAsync === "function") return figma.loadAllPagesAsync().then(function () { return figma.root.children; });
  return Promise.resolve(figma.root.children);
}

function findPage(pages, name) {
  for (var i = 0; i < pages.length; i++) if (pages[i].name === name) return pages[i];
  return null;
}

function isCanonical(name) {
  for (var i = 0; i < CANONICAL_PAGES.length; i++) if (CANONICAL_PAGES[i] === name) return true;
  return false;
}

function existingFrameNames(page) {
  var set = {};
  if (!page) return set;
  for (var i = 0; i < page.children.length; i++) set[page.children[i].name] = true;
  return set;
}

function frameName(screen, theme) {
  return screen.id + (theme === "dark" ? "_dark" : "");
}

/* ---------- node construction ---------- */

function applyBox(node, spec, theme) {
  node.name = spec.n;
  node.x = spec.x;
  node.y = spec.y;
  if (node.resizeWithoutConstraints) node.resizeWithoutConstraints(Math.max(spec.w, 0.01), Math.max(spec.h, 0.01));
  else node.resize(Math.max(spec.w, 0.01), Math.max(spec.h, 0.01));

  var f = resolve(spec.fill, theme);
  node.fills = f ? solid(f) : [];

  var s = resolve(spec.stroke, theme);
  if (s && spec.sw) {
    node.strokes = solid(s);
    node.strokeWeight = spec.sw;
    node.strokeAlign = "INSIDE";
  } else {
    node.strokes = [];
  }

  if (spec.r) node.cornerRadius = Math.min(spec.r, Math.min(spec.w, spec.h) / 2);
  if (spec.opacity != null) node.opacity = spec.opacity;
}

/* Replace every solid fill and stroke in a cloned subtree, which is what makes a
 * brand mark monochrome. Geometry is never touched beyond the uniform resize. */
function retint(node, hex) {
  var paint = solid(hex);
  try { if ("fills" in node && node.fills !== figma.mixed && node.fills.length) node.fills = paint; } catch (e) {}
  try { if ("strokes" in node && node.strokes.length) node.strokes = paint; } catch (e) {}
  if ("children" in node) for (var i = 0; i < node.children.length; i++) retint(node.children[i], hex);
}

/* An ASSET is a reference to a real node in this file. We clone that node - we
 * never author artwork for it. If the registry has no id, we leave a named empty
 * slot so the gap is obvious in the file rather than filled with a guess. */
async function buildAsset(spec, theme) {
  var reg = SCREEN_SPEC.assets[spec.key];
  var id = reg ? reg[theme === "dark" ? "darkNodeId" : "lightNodeId"] : null;
  var tint = resolve(spec.tint, theme);

  if (!id) {
    var slot = figma.createFrame();
    slot.name = "Asset slot / " + spec.key + " (PENDING)";
    slot.x = spec.x; slot.y = spec.y;
    slot.resizeWithoutConstraints(spec.w, spec.h);
    slot.fills = [];
    slot.strokes = solid(tint);
    slot.strokeWeight = 1;
    slot.dashPattern = [3, 3];
    slot.cornerRadius = 4;
    missingAssets.push(spec.key);
    return slot;
  }

  var src = await figma.getNodeByIdAsync(id);
  if (!src || !src.clone) {
    var bad = figma.createFrame();
    bad.name = "Asset slot / " + spec.key + " (NODE " + id + " NOT FOUND)";
    bad.x = spec.x; bad.y = spec.y;
    bad.resizeWithoutConstraints(spec.w, spec.h);
    bad.fills = [];
    bad.strokes = solid(tint);
    bad.strokeWeight = 1;
    bad.dashPattern = [3, 3];
    missingAssets.push(spec.key + " (id " + id + " not found)");
    return bad;
  }

  var c = src.clone();
  c.name = spec.n;
  if (c.rescale && c.width > 0) c.rescale(spec.w / c.width);
  c.x = spec.x; c.y = spec.y;
  retint(c, tint);
  return c;
}

async function buildNode(spec, theme) {
  if (spec.t === "ASSET") return buildAsset(spec, theme);

  if (spec.t === "TEXT") {
    var t = figma.createText();
    t.name = spec.n;
    t.fontName = { family: FONT_FAMILY, style: spec.text.font };
    t.characters = spec.text.s;
    t.fontSize = spec.text.size;
    t.lineHeight = { unit: "PIXELS", value: spec.text.lh };
    t.textAlignHorizontal = spec.text.align;
    t.textAutoResize = "NONE";
    t.x = spec.x; t.y = spec.y;
    t.resizeWithoutConstraints(Math.max(spec.w, 1), Math.max(spec.h, 1));
    var c = resolve(spec.fill, theme);
    t.fills = c ? solid(c) : [];
    return t;
  }

  if (spec.t === "VECTOR") {
    var v = figma.createVector();
    v.name = spec.n;
    v.x = spec.x; v.y = spec.y;
    v.resizeWithoutConstraints(Math.max(spec.w, 1), Math.max(spec.h, 1));
    v.vectorPaths = [{ windingRule: "NONE", data: spec.path }];
    var vf = resolve(spec.fill, theme), vs = resolve(spec.stroke, theme);
    v.fills = vf ? solid(vf) : [];
    if (vs) { v.strokes = solid(vs); v.strokeWeight = spec.sw; v.strokeCap = spec.cap || "ROUND"; v.strokeJoin = "ROUND"; }
    else v.strokes = [];
    return v;
  }

  if (spec.t === "ELLIPSE") {
    var e = figma.createEllipse();
    applyBox(e, spec, theme);
    return e;
  }

  var fr = figma.createFrame();
  applyBox(fr, spec, theme);
  fr.clipsContent = false;
  fr.layoutMode = "NONE";
  var kids = spec.ch || [];
  for (var i = 0; i < kids.length; i++) fr.appendChild(await buildNode(kids[i], theme));
  return fr;
}

async function buildScreen(screen, theme, x, y) {
  var root = figma.createFrame();
  root.name = frameName(screen, theme);
  root.x = x; root.y = y;
  root.resizeWithoutConstraints(screen.w, screen.h);
  root.fills = solid(resolve("background", theme));
  root.clipsContent = true;
  root.layoutMode = "NONE";
  for (var i = 0; i < screen.nodes.length; i++) root.appendChild(await buildNode(screen.nodes[i], theme));
  return root;
}

/* ---------- dry run ---------- */

function planLayout(screens) {
  var placed = [], x = 0, y = 0, rowH = 0;
  for (var i = 0; i < screens.length; i++) {
    var s = screens[i];
    if (x > 0 && x + s.w > GRID_WRAP) { x = 0; y += rowH + GRID_GAP_Y; rowH = 0; }
    placed.push({ id: s.id, x: x, y: y });
    x += s.w + GRID_GAP_X;
    if (s.h > rowH) rowH = s.h;
  }
  return placed;
}

async function dryRun() {
  var pages = await allPages();
  var report = { themes: [], warnings: [], fonts: [], counts: { create: 0, skip: 0 } };

  for (var fi = 0; fi < FONT_STYLES.length; fi++) {
    var fn = { family: FONT_FAMILY, style: FONT_STYLES[fi] };
    try { await figma.loadFontAsync(fn); report.fonts.push({ style: FONT_STYLES[fi], status: "AVAILABLE" }); }
    catch (e) { report.fonts.push({ style: FONT_STYLES[fi], status: "MISSING" }); report.warnings.push("font missing: " + FONT_FAMILY + " " + FONT_STYLES[fi]); }
  }

  // Verify every referenced asset node actually resolves, per theme, before anything is written.
  report.assets = [];
  for (var key in SCREEN_SPEC.assets) {
    if (!Object.prototype.hasOwnProperty.call(SCREEN_SPEC.assets, key)) continue;
    var reg = SCREEN_SPEC.assets[key];
    var row = { key: key, status: reg.status, resolved: {}, note: reg.note || "" };
    for (var ai = 0; ai < THEMES.length; ai++) {
      var th = THEMES[ai];
      var nid = reg[th === "dark" ? "darkNodeId" : "lightNodeId"];
      if (!nid) { row.resolved[th] = "NO_NODE"; continue; }
      var found = await figma.getNodeByIdAsync(nid);
      row.resolved[th] = found ? "OK" : "NOT_FOUND";
      if (!found) report.warnings.push("asset " + key + " (" + th + "): node " + nid + " not found in this file");
    }
    report.assets.push(row);
    if (reg.status === "PENDING_OWNER") report.blocked = (report.blocked || 0) + 1;
  }

  var layout = planLayout(SCREEN_SPEC.screens);

  for (var ti = 0; ti < THEMES.length; ti++) {
    var theme = THEMES[ti];
    var pageName = SCREEN_SPEC.figmaPages[theme];
    if (isCanonical(pageName)) { report.warnings.push("refusing: target page '" + pageName + "' is a canonical page"); continue; }

    var page = findPage(pages, pageName);
    var existing = existingFrameNames(page);
    var entry = { theme: theme, page: pageName, pageExists: !!page, screens: [] };

    for (var si = 0; si < SCREEN_SPEC.screens.length; si++) {
      var s = SCREEN_SPEC.screens[si];
      var name = frameName(s, theme);
      var status = existing[name] ? "ALREADY_EXISTS" : "WILL_CREATE";
      if (status === "WILL_CREATE") report.counts.create++; else report.counts.skip++;
      entry.screens.push({ id: s.id, group: s.group, title: s.title, name: name, w: s.w, h: s.h, status: status, at: layout[si] });
    }
    report.themes.push(entry);
  }

  dryRunReport = report;
  return report;
}

/* ---------- apply ---------- */

async function apply() {
  if (!dryRunReport) throw new Error("Run Dry Run first.");

  for (var fi = 0; fi < FONT_STYLES.length; fi++) await figma.loadFontAsync({ family: FONT_FAMILY, style: FONT_STYLES[fi] });

  var pages = await allPages();
  missingAssets = [];
  var done = { created: 0, skipped: 0, pagesCreated: 0, notes: [], errors: [], assetSlots: [] };
  var byId = {};
  for (var i = 0; i < SCREEN_SPEC.screens.length; i++) byId[SCREEN_SPEC.screens[i].id] = SCREEN_SPEC.screens[i];

  for (var ti = 0; ti < dryRunReport.themes.length; ti++) {
    var t = dryRunReport.themes[ti];
    if (isCanonical(t.page)) { done.errors.push("refused canonical page " + t.page); continue; }

    var page = findPage(pages, t.page);
    if (!page) { page = figma.createPage(); page.name = t.page; done.pagesCreated++; done.notes.push("created page " + t.page); }

    // Re-check on the live page rather than trusting the dry-run snapshot.
    var existing = existingFrameNames(page);

    for (var si = 0; si < t.screens.length; si++) {
      var plan = t.screens[si];
      if (plan.status !== "WILL_CREATE") { done.skipped++; continue; }
      if (existing[plan.name]) { done.skipped++; done.notes.push("already present, skipped: " + plan.name); continue; }

      try {
        var frame = await buildScreen(byId[plan.id], t.theme, plan.at.x, plan.at.y);
        page.appendChild(frame);
        done.created++;
      } catch (e) {
        done.errors.push(plan.name + ": " + (e && e.message ? e.message : String(e)));
      }
    }
  }

  var tally = {};
  for (var mi = 0; mi < missingAssets.length; mi++) tally[missingAssets[mi]] = (tally[missingAssets[mi]] || 0) + 1;
  for (var mk in tally) if (Object.prototype.hasOwnProperty.call(tally, mk))
    done.assetSlots.push(mk + " x" + tally[mk] + " left as empty named slots");

  dryRunReport = null;   // force a fresh dry run before any further write
  return done;
}

/* ---------- message plumbing ---------- */

figma.showUI(__html__, { width: 460, height: 620 });

figma.ui.onmessage = async function (msg) {
  try {
    if (msg.type === "dryrun") {
      figma.ui.postMessage({ type: "status", text: "Reading document…" });
      var r = await dryRun();
      figma.ui.postMessage({ type: "dryrun-result", report: r });
    } else if (msg.type === "apply") {
      figma.ui.postMessage({ type: "status", text: "Creating frames…" });
      var d = await apply();
      figma.ui.postMessage({ type: "apply-result", done: d });
    }
  } catch (e) {
    figma.ui.postMessage({ type: "error", text: (e && e.stack) ? e.stack : String(e) });
  }
};
