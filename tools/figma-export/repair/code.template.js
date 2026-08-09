/*
 * Radio Myata - controlled canonical repair.
 *
 * Two modes, and only two:
 *
 *   DRY RUN  reads the document, resolves every planned mutation against the live
 *            nodes, and reports what it would do. It performs NO writes.
 *   APPLY    executes exactly the mutations that the last Dry Run marked READY -
 *            nothing else. It refuses to run without a Dry Run in this session,
 *            and re-verifies each node's current value immediately before writing,
 *            so anything edited in between is skipped rather than overwritten.
 *
 * The plan is data (repair-plan.json, embedded above as REPAIR_PLAN). The plugin
 * has no opinions of its own: it cannot invent a mutation that is not in the plan.
 *
 * Never touched: artwork, images, vector geometry, sub-pixel differences, frames
 * outside the two canonical pages, and anything Android.
 */

figma.showUI(__html__, { width: 520, height: 640 });

var verifiedPlan = null; // set by Dry Run, consumed by Apply

// ---------------- helpers ----------------

function hexToRgb(hex) {
  var h = String(hex).replace("#", "");
  return { r: parseInt(h.slice(0, 2), 16) / 255, g: parseInt(h.slice(2, 4), 16) / 255, b: parseInt(h.slice(4, 6), 16) / 255 };
}
function rgbToHex(c) {
  var to = function (v) { return Math.round(Math.max(0, Math.min(1, v)) * 255).toString(16).padStart(2, "0"); };
  return "#" + to(c.r) + to(c.g) + to(c.b);
}
function solidOf(node, prop) {
  var arr = node[prop];
  if (!Array.isArray(arr)) return null;
  for (var i = 0; i < arr.length; i++) if (arr[i].type === "SOLID" && arr[i].visible !== false) return arr[i];
  return null;
}
function log(msg) { figma.ui.postMessage({ type: "status", text: msg }); }

async function loadAllPages() {
  try { if (figma.loadAllPagesAsync) await figma.loadAllPagesAsync(); } catch (e) { /* legacy doc access */ }
}

async function getNode(id) {
  try { return await figma.getNodeByIdAsync(id); } catch (e) { return null; }
}

// Current value of a node for a given op, as a comparable string.
function currentValue(node, op) {
  switch (op) {
    case "setLayoutSizingHorizontal": return String(node.layoutSizingHorizontal);
    case "setConstraints": return JSON.stringify(node.constraints);
    case "setFontStyle": return node.fontName && node.fontName.family ? node.fontName.family + "/" + node.fontName.style : "MIXED";
    case "setFontFamily": return node.fontName && node.fontName.family ? node.fontName.family + "/" + node.fontName.style + "/" + node.fontSize : "MIXED";
    case "setVisible": return String(node.visible !== false);
    case "renameNode": return node.name;
    case "deleteNode": return node.removed ? "removed" : "present";
    case "setAutoLayoutHug": return (node.layoutMode || "NONE") + " / " + node.layoutSizingHorizontal + " / " + node.layoutSizingVertical;
    default: return "?";
  }
}

function expectedMatches(op, current, expect) {
  if (op === "deleteNode") return current === "present";
  if (op === "setFontFamily") return current.indexOf("Hanken") === 0;
  if (op === "setAutoLayoutHug") return current !== "HORIZONTAL / HUG / HUG";
  return current === expect;
}

// ---------------- dry run ----------------

async function dryRun() {
  await loadAllPages();
  var report = { mutations: [], bindings: [], variables: [], textStyles: [], counts: {} };

  // 1. structural + typography
  for (var i = 0; i < REPAIR_PLAN.mutations.length; i++) {
    var m = REPAIR_PLAN.mutations[i];
    var node = await getNode(m.id);
    var entry = { kind: "mutation", group: m.group, theme: m.theme, op: m.op, id: m.id, path: m.path, expect: m.expect, value: m.value, reason: m.reason };
    if (!node) { entry.status = "SKIP_MISSING"; entry.current = "(node not found)"; }
    else {
      entry.current = currentValue(node, m.op);
      entry.status = expectedMatches(m.op, entry.current, m.expect) ? "READY" : "SKIP_ALREADY_OK_OR_CHANGED";
    }
    report.mutations.push(entry);
  }

  // 2. semantic variables
  var collections = await figma.variables.getLocalVariableCollectionsAsync();
  var existing = null;
  for (var c = 0; c < collections.length; c++) if (collections[c].name === REPAIR_PLAN.collectionName) existing = collections[c];
  var tokenNames = Object.keys(REPAIR_PLAN.tokens);
  for (var t = 0; t < tokenNames.length; t++) {
    var name = tokenNames[t], tok = REPAIR_PLAN.tokens[name];
    report.variables.push({
      kind: "variable", name: name, dark: tok.dark, light: tok.light,
      bound: tok.bind, status: existing ? "COLLECTION_EXISTS_WILL_REUSE" : "WILL_CREATE"
    });
  }

  // 3. bindings
  for (var b = 0; b < REPAIR_PLAN.tokenBindings.length; b++) {
    var bind = REPAIR_PLAN.tokenBindings[b];
    for (var side = 0; side < 2; side++) {
      var s = side === 0 ? bind.dark : bind.light;
      var theme = side === 0 ? "dark" : "light";
      var n = await getNode(s.id);
      var e = { kind: "binding", token: bind.token, prop: bind.prop, theme: theme, id: s.id, path: s.path, nodeType: bind.nodeType, nodeName: bind.nodeName, expect: s.current };
      if (!n) { e.status = "SKIP_MISSING"; e.current = "(node not found)"; }
      else {
        var paint = solidOf(n, bind.prop);
        e.current = paint ? rgbToHex(paint.color) : "(no solid paint)";
        e.status = paint && e.current === s.current ? "READY" : "SKIP_CHANGED";
      }
      report.bindings.push(e);
    }
  }

  // 4. text styles
  var localStyles = await figma.getLocalTextStylesAsync();
  for (var ts = 0; ts < REPAIR_PLAN.textStyles.length; ts++) {
    var st = REPAIR_PLAN.textStyles[ts];
    var found = false;
    for (var q = 0; q < localStyles.length; q++) if (localStyles[q].name === st.name) found = true;
    report.textStyles.push({ kind: "textStyle", name: st.name, spec: st.family + " " + st.style + " " + st.size + "/" + st.lineHeight, sample: st.sample, status: found ? "EXISTS_WILL_REUSE" : "WILL_CREATE" });
  }

  report.counts = {
    mutationsReady: report.mutations.filter(function (x) { return x.status === "READY"; }).length,
    mutationsSkipped: report.mutations.filter(function (x) { return x.status !== "READY"; }).length,
    bindingsReady: report.bindings.filter(function (x) { return x.status === "READY"; }).length,
    bindingsSkipped: report.bindings.filter(function (x) { return x.status !== "READY"; }).length,
    variables: report.variables.length,
    textStyles: report.textStyles.length
  };

  verifiedPlan = report;
  return report;
}

// ---------------- apply ----------------

async function ensureCollection() {
  var collections = await figma.variables.getLocalVariableCollectionsAsync();
  for (var i = 0; i < collections.length; i++) if (collections[i].name === REPAIR_PLAN.collectionName) return collections[i];
  var col = figma.variables.createVariableCollection(REPAIR_PLAN.collectionName);
  col.renameMode(col.modes[0].modeId, "Light");
  col.addMode("Dark");
  return col;
}

async function ensureVariables(col) {
  var modeLight = null, modeDark = null;
  for (var i = 0; i < col.modes.length; i++) {
    if (col.modes[i].name === "Light") modeLight = col.modes[i].modeId;
    if (col.modes[i].name === "Dark") modeDark = col.modes[i].modeId;
  }
  var existing = await figma.variables.getLocalVariablesAsync("COLOR");
  var map = {};
  var names = Object.keys(REPAIR_PLAN.tokens);
  for (var t = 0; t < names.length; t++) {
    var name = names[t], tok = REPAIR_PLAN.tokens[name], variable = null;
    for (var e = 0; e < existing.length; e++) if (existing[e].name === name && existing[e].variableCollectionId === col.id) variable = existing[e];
    if (!variable) variable = figma.variables.createVariable(name, col, "COLOR");
    if (modeLight) variable.setValueForMode(modeLight, hexToRgb(tok.light));
    if (modeDark) variable.setValueForMode(modeDark, hexToRgb(tok.dark));
    map[name] = variable;
  }
  return map;
}

async function apply() {
  if (!verifiedPlan) throw new Error("Run Dry Run first. Apply only executes what a Dry Run in this session marked READY.");
  await loadAllPages();
  var done = { mutations: 0, bindings: 0, variables: 0, textStyles: 0, skipped: [] };

  // 1. structural + typography
  for (var i = 0; i < verifiedPlan.mutations.length; i++) {
    var m = verifiedPlan.mutations[i];
    if (m.status !== "READY") continue;
    var node = await getNode(m.id);
    if (!node) { done.skipped.push(m.id + " vanished"); continue; }
    // re-verify immediately before writing
    if (!expectedMatches(m.op, currentValue(node, m.op), m.expect)) { done.skipped.push(m.id + " changed since dry run"); continue; }

    if (m.op === "setLayoutSizingHorizontal") node.layoutSizingHorizontal = m.value;
    else if (m.op === "setConstraints") node.constraints = JSON.parse(m.value);
    else if (m.op === "setVisible") node.visible = (m.value === "true");
    else if (m.op === "renameNode") node.name = m.value;
    else if (m.op === "deleteNode") node.remove();
    else if (m.op === "setAutoLayoutHug") {
      node.layoutMode = "HORIZONTAL";
      node.layoutSizingHorizontal = "HUG";
      node.layoutSizingVertical = "HUG";
    } else if (m.op === "setFontStyle" || m.op === "setFontFamily") {
      var target = m.op === "setFontStyle"
        ? { family: "Muller", style: m.value.split("/")[1] }
        : { family: "Muller", style: "Regular" };
      try { await figma.loadFontAsync(target); } catch (e) { done.skipped.push(m.id + " font unavailable: " + target.family + " " + target.style); continue; }
      node.fontName = target;
    }
    done.mutations++;
  }

  // 2. variables
  var col = await ensureCollection();
  var vars = await ensureVariables(col);
  done.variables = Object.keys(vars).length;

  // 3. bindings
  for (var b = 0; b < verifiedPlan.bindings.length; b++) {
    var e = verifiedPlan.bindings[b];
    if (e.status !== "READY") continue;
    var n = await getNode(e.id);
    if (!n) { done.skipped.push(e.id + " vanished"); continue; }
    var paint = solidOf(n, e.prop);
    if (!paint || rgbToHex(paint.color) !== e.expect) { done.skipped.push(e.id + " colour changed since dry run"); continue; }
    var variable = vars[e.token];
    if (!variable) { done.skipped.push(e.id + " no variable " + e.token); continue; }
    var bound = figma.variables.setBoundVariableForPaint(paint, "color", variable);
    var arr = n[e.prop].slice();
    for (var k = 0; k < arr.length; k++) if (arr[k] === paint || (arr[k].type === "SOLID" && rgbToHex(arr[k].color) === e.expect)) { arr[k] = bound; break; }
    n[e.prop] = arr;
    done.bindings++;
  }

  // 4. text styles
  var localStyles = await figma.getLocalTextStylesAsync();
  for (var t = 0; t < REPAIR_PLAN.textStyles.length; t++) {
    var st = REPAIR_PLAN.textStyles[t];
    var found = null;
    for (var q = 0; q < localStyles.length; q++) if (localStyles[q].name === st.name) found = localStyles[q];
    if (found) continue;
    try { await figma.loadFontAsync({ family: st.family, style: st.style }); }
    catch (err) { done.skipped.push("text style " + st.name + ": font unavailable"); continue; }
    var style = figma.createTextStyle();
    style.name = st.name;
    style.fontName = { family: st.family, style: st.style };
    style.fontSize = st.size;
    style.lineHeight = { unit: "PIXELS", value: st.lineHeight };
    done.textStyles++;
  }

  return done;
}

// ---------------- message pump ----------------

figma.ui.onmessage = async function (msg) {
  if (!msg) return;
  try {
    if (msg.type === "dryrun") {
      log("Reading document…");
      var report = await dryRun();
      figma.ui.postMessage({ type: "dryrun-result", report: report });
    } else if (msg.type === "apply") {
      log("Applying…");
      var done = await apply();
      figma.ui.postMessage({ type: "apply-result", done: done });
    }
  } catch (error) {
    figma.ui.postMessage({ type: "error", text: String((error && error.stack) || error) });
  }
};
