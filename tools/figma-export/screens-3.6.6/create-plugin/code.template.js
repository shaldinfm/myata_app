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

function buildNode(spec, theme) {
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
  for (var i = 0; i < kids.length; i++) fr.appendChild(buildNode(kids[i], theme));
  return fr;
}

function buildScreen(screen, theme, x, y) {
  var root = figma.createFrame();
  root.name = frameName(screen, theme);
  root.x = x; root.y = y;
  root.resizeWithoutConstraints(screen.w, screen.h);
  root.fills = solid(resolve("background", theme));
  root.clipsContent = true;
  root.layoutMode = "NONE";
  for (var i = 0; i < screen.nodes.length; i++) root.appendChild(buildNode(screen.nodes[i], theme));
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
  var done = { created: 0, skipped: 0, pagesCreated: 0, notes: [], errors: [] };
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
        var frame = buildScreen(byId[plan.id], t.theme, plan.at.x, plan.at.y);
        page.appendChild(frame);
        done.created++;
      } catch (e) {
        done.errors.push(plan.name + ": " + (e && e.message ? e.message : String(e)));
      }
    }
  }

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
