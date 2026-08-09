/*
 * Radio Myata - controlled canonical repair.
 *
 *   DRY RUN  reads the document and reports what it would do, plus the current
 *            state of the semantic collection, its modes, variables and text
 *            styles. It performs NO writes.
 *   APPLY    executes only the entries the last Dry Run marked READY, re-verifying
 *            each node immediately before writing.
 *
 * Both modes are idempotent and recoverable. A run that failed halfway can be
 * re-run: anything already done reports as ALREADY_APPLIED and is not repeated.
 *
 * The plan is data (repair-plan.json, embedded above as REPAIR_PLAN). The plugin
 * cannot invent a mutation that is not in the plan.
 */

figma.showUI(__html__, { width: 560, height: 680 });

var verifiedPlan = null;

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
function errText(e) {
  if (!e) return "unknown error";
  return (e.message || String(e)) + (e.stack ? "\n" + String(e.stack).split("\n").slice(0, 3).join("\n") : "");
}
async function loadAllPages() {
  try { if (figma.loadAllPagesAsync) await figma.loadAllPagesAsync(); } catch (e) { /* legacy access */ }
}
async function getNode(id) {
  try { return await figma.getNodeByIdAsync(id); } catch (e) { return null; }
}

// ---------------- classification ----------------

function currentValue(node, op) {
  switch (op) {
    case "setLayoutSizingHorizontal": return String(node.layoutSizingHorizontal);
    case "setConstraints": return JSON.stringify({ horizontal: node.constraints.horizontal, vertical: node.constraints.vertical });
    case "setFontStyle": return node.fontName && node.fontName.family ? node.fontName.family + "/" + node.fontName.style : "MIXED";
    case "setFontFamily": return node.fontName && node.fontName.family ? node.fontName.family + "/" + node.fontName.style : "MIXED";
    case "setVisible": return String(node.visible !== false);
    case "renameNode": return node.name;
    case "deleteNode": return "present";
    case "setAutoLayoutHug": return (node.layoutMode || "NONE") + " / " + node.layoutSizingHorizontal + " / " + node.layoutSizingVertical;
    default: return "?";
  }
}

// Has this mutation already been done?
function isAlreadyApplied(op, current, m) {
  switch (op) {
    case "setLayoutSizingHorizontal": return current === m.value;
    case "setConstraints": {
      try {
        var a = JSON.parse(current), b = JSON.parse(m.value);
        return a.horizontal === b.horizontal && a.vertical === b.vertical;
      } catch (e) { return false; }
    }
    case "setFontStyle": return current === m.value;
    case "setFontFamily": return current.indexOf("Muller/") === 0;
    case "setVisible": return current === m.value;
    case "renameNode": return current === m.value;
    case "setAutoLayoutHug": return current === "HORIZONTAL / HUG / HUG";
    default: return false;
  }
}

// Is the node still in the state the plan expected before the change?
function isReady(op, current, m) {
  switch (op) {
    case "setFontFamily": return current.indexOf("Hanken") === 0;
    case "setConstraints": {
      try {
        var a = JSON.parse(current), b = JSON.parse(m.expect);
        return a.horizontal === b.horizontal && a.vertical === b.vertical;
      } catch (e) { return false; }
    }
    case "setAutoLayoutHug": return current !== "HORIZONTAL / HUG / HUG";
    case "deleteNode": return true; // node exists => still to delete
    default: return current === m.expect;
  }
}

function classify(node, m) {
  if (!node) return m.op === "deleteNode"
    ? { status: "ALREADY_APPLIED", current: "(node gone - deleted)" }
    : { status: "SKIP_MISSING", current: "(node not found)" };
  var cur = currentValue(node, m.op);
  if (m.op !== "deleteNode" && isAlreadyApplied(m.op, cur, m)) return { status: "ALREADY_APPLIED", current: cur };
  if (isReady(m.op, cur, m)) return { status: "READY", current: cur };
  return { status: "SKIP_CHANGED", current: cur };
}

// ---------------- semantic collection state (read-only) ----------------

async function readCollectionState() {
  var state = { exists: false, id: null, modes: [], modeLight: null, modeDark: null, variables: {}, missingVariables: [], canBind: false, note: "" };
  var collections = await figma.variables.getLocalVariableCollectionsAsync();
  var col = null;
  for (var i = 0; i < collections.length; i++) if (collections[i].name === REPAIR_PLAN.collectionName) col = collections[i];
  if (!col) { state.note = "Collection does not exist yet; it will be created."; return state; }

  state.exists = true;
  state.id = col.id;
  for (var m = 0; m < col.modes.length; m++) {
    state.modes.push(col.modes[m].name);
    if (col.modes[m].name === "Light") state.modeLight = col.modes[m].modeId;
    if (col.modes[m].name === "Dark") state.modeDark = col.modes[m].modeId;
  }

  var vars = await figma.variables.getLocalVariablesAsync("COLOR");
  var names = Object.keys(REPAIR_PLAN.tokens);
  for (var t = 0; t < names.length; t++) {
    var found = null;
    for (var v = 0; v < vars.length; v++) if (vars[v].name === names[t] && vars[v].variableCollectionId === col.id) found = vars[v];
    if (found) state.variables[names[t]] = { id: found.id, modes: Object.keys(found.valuesByMode).length };
    else state.missingVariables.push(names[t]);
  }

  state.canBind = !!(state.modeLight && state.modeDark);
  if (!state.modeDark) {
    state.note = "Single mode only. A Light/Dark variable pair needs a Figma plan that allows multiple modes, so the semantic phase is skipped by design " +
      "and the mapping lives in tools/figma-export/canonical/semantic-tokens.json instead. This is NOT an error.";
    state.emptyAndUnused = Object.keys(state.variables).length === 0;
  }
  return state;
}

// ---------------- dry run ----------------

async function dryRun() {
  await loadAllPages();
  var report = { mutations: [], bindings: [], variables: [], textStyles: [], collection: null, counts: {}, warnings: [] };

  report.collection = await readCollectionState();
  report.starterMode = !report.collection.canBind;
  if (report.collection.note) report.warnings.push(report.collection.note);
  if (report.collection.emptyAndUnused)
    report.warnings.push("The '" + REPAIR_PLAN.collectionName + "' collection is empty (0 variables) and nothing binds to it. " +
      "It is a leftover from the failed run and carries no value. Deleting it is offered as a separate, explicit action - it is never part of Apply.");

  for (var i = 0; i < REPAIR_PLAN.mutations.length; i++) {
    var m = REPAIR_PLAN.mutations[i];
    var node = await getNode(m.id);
    var c = classify(node, m);
    report.mutations.push({ group: m.group, theme: m.theme, op: m.op, id: m.id, path: m.path,
      expect: m.expect, value: m.value, reason: m.reason, current: c.current, status: c.status });
  }

  var names = Object.keys(REPAIR_PLAN.tokens);
  for (var t = 0; t < names.length; t++) {
    var name = names[t], tok = REPAIR_PLAN.tokens[name];
    var existing = report.collection.variables[name];
    report.variables.push({ name: name, dark: tok.dark, light: tok.light, bound: tok.bind,
      status: existing ? "ALREADY_APPLIED" : (report.starterMode ? "SKIPPED_PLAN_LIMIT" : "WILL_CREATE") });
  }

  // bindings: READY only if the collection can actually carry two modes
  for (var b = 0; b < REPAIR_PLAN.tokenBindings.length; b++) {
    var bind = REPAIR_PLAN.tokenBindings[b];
    for (var side = 0; side < 2; side++) {
      var s = side === 0 ? bind.dark : bind.light;
      var theme = side === 0 ? "dark" : "light";
      var n = await getNode(s.id);
      var e = { token: bind.token, prop: bind.prop, theme: theme, id: s.id, path: s.path, nodeName: bind.nodeName, expect: s.current };
      if (!n) { e.status = "SKIP_MISSING"; e.current = "(node not found)"; }
      else {
        var paint = solidOf(n, bind.prop);
        e.current = paint ? rgbToHex(paint.color) : "(no solid paint)";
        var boundTo = paint && paint.boundVariables && paint.boundVariables.color ? paint.boundVariables.color.id : null;
        var want = report.collection.variables[bind.token];
        if (boundTo && want && boundTo === want.id) e.status = "ALREADY_APPLIED";
        else if (report.starterMode) e.status = "SKIPPED_PLAN_LIMIT";
        else e.status = paint && e.current === s.current ? "READY" : "SKIP_CHANGED";
      }
      report.bindings.push(e);
    }
  }

  var localStyles = await figma.getLocalTextStylesAsync();
  for (var ts = 0; ts < REPAIR_PLAN.textStyles.length; ts++) {
    var st = REPAIR_PLAN.textStyles[ts];
    var found = false;
    for (var q = 0; q < localStyles.length; q++) if (localStyles[q].name === st.name) found = true;
    report.textStyles.push({ name: st.name, spec: st.family + " " + st.style + " " + st.size + "/" + st.lineHeight,
      sample: st.sample, status: found ? "ALREADY_APPLIED" : "WILL_CREATE" });
  }

  var count = function (arr, s) { var n = 0; for (var i = 0; i < arr.length; i++) if (arr[i].status === s) n++; return n; };
  report.counts = {
    mutationsReady: count(report.mutations, "READY"),
    mutationsApplied: count(report.mutations, "ALREADY_APPLIED"),
    mutationsChanged: count(report.mutations, "SKIP_CHANGED"),
    mutationsMissing: count(report.mutations, "SKIP_MISSING"),
    bindingsReady: count(report.bindings, "READY"),
    bindingsApplied: count(report.bindings, "ALREADY_APPLIED"),
    bindingsBlocked: count(report.bindings, "SKIPPED_PLAN_LIMIT"),
    bindingsOther: report.bindings.length - count(report.bindings, "READY") - count(report.bindings, "ALREADY_APPLIED") - count(report.bindings, "SKIPPED_PLAN_LIMIT"),
    variablesToCreate: count(report.variables, "WILL_CREATE"),
    variablesExisting: count(report.variables, "ALREADY_APPLIED"),
    stylesToCreate: count(report.textStyles, "WILL_CREATE"),
    stylesExisting: count(report.textStyles, "ALREADY_APPLIED")
  };

  verifiedPlan = report;
  return report;
}

// ---------------- apply ----------------

/*
 * Starter-compatible: this NEVER calls addMode(). A Light/Dark variable pair needs
 * a plan that allows multiple modes; without it the semantic phase is skipped
 * entirely and the mapping lives in canonical/semantic-tokens.json instead.
 *
 * Nothing is created here either - creating an unbound single-mode collection is
 * what produced the leftover we now have to clean up.
 */
async function inspectCollection(done) {
  var collections = await figma.variables.getLocalVariableCollectionsAsync();
  var col = null;
  for (var i = 0; i < collections.length; i++) if (collections[i].name === REPAIR_PLAN.collectionName) col = collections[i];
  if (!col) return { collection: null, modeLight: null, modeDark: null };

  var modeLight = null, modeDark = null;
  for (var m = 0; m < col.modes.length; m++) {
    if (col.modes[m].name === "Light") modeLight = col.modes[m].modeId;
    if (col.modes[m].name === "Dark") modeDark = col.modes[m].modeId;
  }
  return { collection: col, modeLight: modeLight, modeDark: modeDark };
}

// Explicit, separate action. Never part of Apply.
async function deleteEmptyCollection() {
  var collections = await figma.variables.getLocalVariableCollectionsAsync();
  var col = null;
  for (var i = 0; i < collections.length; i++) if (collections[i].name === REPAIR_PLAN.collectionName) col = collections[i];
  if (!col) return { ok: false, reason: "No collection named " + REPAIR_PLAN.collectionName + " exists." };

  var vars = await figma.variables.getLocalVariablesAsync("COLOR");
  var mine = 0;
  for (var v = 0; v < vars.length; v++) if (vars[v].variableCollectionId === col.id) mine++;
  if (mine > 0) return { ok: false, reason: "Refusing to delete: the collection holds " + mine + " variable(s). Only an empty collection is removed." };

  col.remove();
  return { ok: true, reason: "Deleted the empty collection " + REPAIR_PLAN.collectionName + "." };
}

async function ensureVariables(col, modeLight, modeDark, done) {
  var existing = await figma.variables.getLocalVariablesAsync("COLOR");
  var map = {};
  var names = Object.keys(REPAIR_PLAN.tokens);
  for (var t = 0; t < names.length; t++) {
    var name = names[t], tok = REPAIR_PLAN.tokens[name], variable = null;
    for (var e = 0; e < existing.length; e++) if (existing[e].name === name && existing[e].variableCollectionId === col.id) variable = existing[e];
    if (!variable) {
      try { variable = figma.variables.createVariable(name, col, "COLOR"); done.variables++; }
      catch (err) { done.skipped.push("createVariable('" + name + "'): " + errText(err)); continue; }
    } else {
      done.variablesReused++;
    }
    try {
      if (modeLight) variable.setValueForMode(modeLight, hexToRgb(tok.light));
      if (modeDark) variable.setValueForMode(modeDark, hexToRgb(tok.dark));
    } catch (err) { done.skipped.push("setValueForMode('" + name + "'): " + errText(err)); }
    map[name] = variable;
  }
  return map;
}

async function apply() {
  if (!verifiedPlan) throw new Error("Run Dry Run first. Apply only executes what a Dry Run in this session marked READY.");
  await loadAllPages();
  var done = { mutations: 0, mutationsSkipped: 0, variables: 0, variablesReused: 0, bindings: 0, textStyles: 0, skipped: [], notes: [], semanticSkipped: null, phase: "start" };

  // ---- phase 1: structural + typography ----
  done.phase = "structural";
  for (var i = 0; i < verifiedPlan.mutations.length; i++) {
    var m = verifiedPlan.mutations[i];
    if (m.status !== "READY") { done.mutationsSkipped++; continue; }
    try {
      var node = await getNode(m.id);
      var c = classify(node, m);
      if (c.status === "ALREADY_APPLIED") { done.mutationsSkipped++; continue; }
      if (c.status !== "READY") { done.skipped.push(m.op + " " + m.id + ": " + c.status + " (now " + c.current + ")"); continue; }

      if (m.op === "setLayoutSizingHorizontal") node.layoutSizingHorizontal = m.value;
      else if (m.op === "setConstraints") node.constraints = JSON.parse(m.value);
      else if (m.op === "setVisible") node.visible = (m.value === "true");
      else if (m.op === "renameNode") node.name = m.value;
      else if (m.op === "deleteNode") node.remove();
      else if (m.op === "setAutoLayoutHug") { node.layoutMode = "HORIZONTAL"; node.layoutSizingHorizontal = "HUG"; node.layoutSizingVertical = "HUG"; }
      else if (m.op === "setFontStyle" || m.op === "setFontFamily") {
        var target = { family: "Muller", style: m.op === "setFontStyle" ? m.value.split("/")[1] : "Regular" };
        try { await figma.loadFontAsync(target); }
        catch (e) { done.skipped.push(m.id + ": font unavailable " + target.family + " " + target.style + " - " + errText(e)); continue; }
        node.fontName = target;
      }
      done.mutations++;
    } catch (e) {
      done.skipped.push(m.op + " " + m.id + " threw: " + errText(e));
    }
  }

  // ---- phase 2: semantic variables (skipped unless two modes already exist) ----
  done.phase = "semantic";
  var col = null, modeLight = null, modeDark = null;
  try {
    var res = await inspectCollection(done);
    col = res.collection; modeLight = res.modeLight; modeDark = res.modeDark;
  } catch (e) { done.skipped.push("collection inspect: " + errText(e)); }

  var vars = null;
  if (!col || !modeLight || !modeDark) {
    done.semanticSkipped = "SKIPPED_PLAN_LIMIT - a Light/Dark variable pair needs a Figma plan that allows multiple modes. " +
      "The approved mapping lives in tools/figma-export/canonical/semantic-tokens.json and is the source of truth for Android. " +
      "Nothing was created, and no node was bound: binding to a single-mode variable would make both pages resolve to the same colour.";
    done.notes.push("Semantic phase skipped (Starter-compatible path). Text styles still run.");
  } else {
    done.phase = "variables";
    try { vars = await ensureVariables(col, modeLight, modeDark, done); }
    catch (e) { done.skipped.push("variables: " + errText(e)); vars = null; }

    if (vars) {
      done.phase = "bindings";
      for (var b = 0; b < verifiedPlan.bindings.length; b++) {
        var e2 = verifiedPlan.bindings[b];
        if (e2.status !== "READY") continue;
        try {
          var n = await getNode(e2.id);
          if (!n) { done.skipped.push(e2.id + " vanished"); continue; }
          var paint = solidOf(n, e2.prop);
          if (!paint) { done.skipped.push(e2.id + " has no solid paint"); continue; }
          if (paint.boundVariables && paint.boundVariables.color) continue;
          if (rgbToHex(paint.color) !== e2.expect) { done.skipped.push(e2.id + " colour changed since dry run"); continue; }
          var variable = vars[e2.token];
          if (!variable) { done.skipped.push(e2.id + ": no variable " + e2.token); continue; }
          var bound = figma.variables.setBoundVariableForPaint(paint, "color", variable);
          var arr = n[e2.prop].slice();
          for (var k = 0; k < arr.length; k++) if (arr[k] === paint) { arr[k] = bound; break; }
          n[e2.prop] = arr;
          done.bindings++;
        } catch (err) { done.skipped.push("binding " + e2.id + " threw: " + errText(err)); }
      }
    }
  }

  // ---- phase 3: text styles (independent of everything above) ----
  done.phase = "textStyles";
  var localStyles = await figma.getLocalTextStylesAsync();
  for (var t = 0; t < REPAIR_PLAN.textStyles.length; t++) {
    var st = REPAIR_PLAN.textStyles[t];
    var exists = false;
    for (var q = 0; q < localStyles.length; q++) if (localStyles[q].name === st.name) exists = true;
    if (exists) continue;
    try {
      await figma.loadFontAsync({ family: st.family, style: st.style });
      var style = figma.createTextStyle();
      style.name = st.name;
      style.fontName = { family: st.family, style: st.style };
      style.fontSize = st.size;
      style.lineHeight = { unit: "PIXELS", value: st.lineHeight };
      done.textStyles++;
    } catch (err) { done.skipped.push("text style " + st.name + ": " + errText(err)); }
  }

  done.phase = "done";
  return done;
}

// ---------------- message pump ----------------

figma.ui.onmessage = async function (msg) {
  if (!msg) return;
  if (msg.type === "dryrun") {
    try {
      figma.ui.postMessage({ type: "status", text: "Reading document…" });
      figma.ui.postMessage({ type: "dryrun-result", report: await dryRun() });
    } catch (e) {
      figma.ui.postMessage({ type: "error", text: "Dry Run failed: " + errText(e) });
    }
  } else if (msg.type === "delete-empty-collection") {
    try {
      figma.ui.postMessage({ type: "delete-result", result: await deleteEmptyCollection() });
    } catch (e) {
      figma.ui.postMessage({ type: "error", text: "Delete failed: " + errText(e) });
    }
  } else if (msg.type === "apply") {
    var phase = "unknown";
    try {
      figma.ui.postMessage({ type: "status", text: "Applying…" });
      var done = await apply();
      phase = done.phase;
      figma.ui.postMessage({ type: "apply-result", done: done });
    } catch (e) {
      figma.ui.postMessage({ type: "error", text: "Apply failed during phase '" + phase + "': " + errText(e) });
    }
  }
};
