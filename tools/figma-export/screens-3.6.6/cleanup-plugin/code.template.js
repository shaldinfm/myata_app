/* eslint-disable */
/*
 * Radio Myata - bounded structural cleanup of the 3.6.6 proposal pages.
 *
 * The owner-edited pages are the source of truth. This plugin does not create,
 * regenerate or reposition anything: it only restores layout metadata that the
 * geometry already implies, drops three already-invisible leftovers, and renames
 * sixteen cells per theme.
 *
 * Safety model:
 *   - Dry Run performs no writes and re-verifies every precondition against the
 *     live node. If the file moved on since the snapshot, the mutation blocks.
 *   - Apply runs only what Dry Run marked READY, in this session.
 *   - Every target is addressed by node id from CLEANUP_PLAN. The plugin cannot
 *     discover its own targets.
 *   - It refuses to touch a canonical page.
 *   - Auto-layout writes are verified afterwards: every child's absolute position
 *     is measured before and after, and if anything moved the row is REVERTED.
 */

var APPROVED_GROUPS = {
  "history-row-autolayout": true,
  "history-text-hug": true,
  "text-box-hug": true,
  "lastfm-leftover": true,
  "avatar-naming": true
};

var dryRunReport = null;

/* ---------- helpers ---------- */

function pageKey(name) {
  return String(name).replace(/[‐-―−-]/g, "-").replace(/\s+/g, " ").trim().toUpperCase();
}
function isCanonicalName(name) { return /^CURRENT ANDROID UI\b/.test(pageKey(name)); }

function pageOf(node) {
  var n = node;
  while (n && n.type !== "PAGE") n = n.parent;
  return n;
}

var r2 = function (n) { return Math.round(n * 100) / 100; };
var near = function (a, b) { return Math.abs(a - b) <= 0.51; };

function allPages() {
  if (typeof figma.loadAllPagesAsync === "function") return figma.loadAllPagesAsync().then(function () { return figma.root.children; });
  return Promise.resolve(figma.root.children);
}

/* Absolute position of every descendant, so a layout change can be proven to
 * have moved nothing. */
function snapshotPositions(node) {
  var out = [];
  (function w(n) {
    if (n.absoluteBoundingBox)
      out.push({ id: n.id, name: n.name, x: n.absoluteBoundingBox.x, y: n.absoluteBoundingBox.y,
                 w: n.absoluteBoundingBox.width, h: n.absoluteBoundingBox.height });
    (n.children || []).forEach(w);
  })(node);
  return out;
}

function comparePositions(before, after) {
  var moved = [];
  var byId = {};
  for (var i = 0; i < after.length; i++) byId[after[i].id] = after[i];
  for (var j = 0; j < before.length; j++) {
    var b = before[j], a = byId[b.id];
    if (!a) { moved.push(b.name + " disappeared"); continue; }
    if (!near(a.x, b.x) || !near(a.y, b.y))
      moved.push(b.name + " moved " + r2(a.x - b.x) + "," + r2(a.y - b.y));
    if (!near(a.w, b.w) || !near(a.h, b.h))
      moved.push(b.name + " resized " + r2(a.w - b.w) + "x" + r2(a.h - b.h));
  }
  return moved;
}

/* ---------- verification ---------- */

async function verify(m) {
  if (!APPROVED_GROUPS[m.group]) return "group '" + m.group + "' is not in the approved scope";

  var node = await figma.getNodeByIdAsync(m.nodeId);
  if (!node) return "node " + m.nodeId + " not found";
  if (node.removed) return "node has been removed";

  var pg = pageOf(node);
  if (!pg) return "node is not on a page";
  if (isCanonicalName(pg.name)) return "REFUSING: node is on the canonical page '" + pg.name + "'";
  if (pageKey(pg.name) !== pageKey(m.page)) return "node is on page '" + pg.name + "', plan expected '" + m.page + "'";

  var c = m.check;

  if (c.type && node.type !== c.type) return "type is " + node.type + ", expected " + c.type;
  if (c.name !== undefined && node.name !== c.name) return "name is '" + node.name + "', expected '" + c.name + "'";
  if (c.namePrefix !== undefined && node.name.indexOf(c.namePrefix) !== 0) return "name '" + node.name + "' does not start with '" + c.namePrefix + "'";
  if (c.visible !== undefined && (node.visible !== false) !== (c.visible !== false)) return "visibility is " + node.visible + ", expected " + c.visible;
  if (c.width !== undefined && !near(node.width, c.width)) return "width is " + r2(node.width) + ", expected " + c.width;
  if (c.height !== undefined && !near(node.height, c.height)) return "height is " + r2(node.height) + ", expected " + c.height;
  if (c.x !== undefined && !near(node.x, c.x)) return "x is " + r2(node.x) + ", expected " + c.x;
  if (c.y !== undefined && !near(node.y, c.y)) return "y is " + r2(node.y) + ", expected " + c.y;
  if (c.layoutMode !== undefined && (node.layoutMode || "NONE") !== c.layoutMode) return "layoutMode is " + node.layoutMode + ", expected " + c.layoutMode;
  if (c.primaryAxisSizingMode !== undefined && node.primaryAxisSizingMode !== c.primaryAxisSizingMode)
    return "primaryAxisSizingMode is " + node.primaryAxisSizingMode + ", expected " + c.primaryAxisSizingMode;

  if (c.textAutoResize !== undefined) {
    if (node.textAutoResize !== c.textAutoResize) return "textAutoResize is " + node.textAutoResize + ", expected " + c.textAutoResize;
    if (c.characters !== undefined && node.characters !== c.characters) return "text is '" + node.characters + "', expected '" + c.characters + "'";
    if (c.textAlignVertical !== undefined && node.textAlignVertical !== c.textAlignVertical)
      return "textAlignVertical is " + node.textAlignVertical + ", expected " + c.textAlignVertical + " - growing the box would move the glyph";
    var lh = node.lineHeight;
    if (c.lineHeight !== undefined && (!lh || lh.unit !== "PIXELS" || !near(lh.value, c.lineHeight)))
      return "lineHeight is not " + c.lineHeight + "px";
  }

  if (c.children) {
    var kids = (node.children || []).slice().sort(function (a, b) { return a.x - b.x; });
    if (kids.length !== c.children.length) return "has " + kids.length + " children, expected " + c.children.length;
    for (var i = 0; i < c.children.length; i++) {
      var e = c.children[i], k = kids[i];
      if (k.name !== e.name) return "child " + i + " is '" + k.name + "', expected '" + e.name + "'";
      if (!near(k.x, e.x) || !near(k.y, e.y)) return "child '" + k.name + "' is at " + r2(k.x) + "," + r2(k.y) + ", expected " + e.x + "," + e.y;
      if (!near(k.width, e.w) || !near(k.height, e.h)) return "child '" + k.name + "' is " + r2(k.width) + "x" + r2(k.height) + ", expected " + e.w + "x" + e.h;
    }
  }

  if (c.siblingMarkVisible) {
    var parent = node.parent;
    var found = false;
    for (var s = 0; s < (parent.children || []).length; s++) {
      var sib = parent.children[s];
      if (sib.id !== node.id && sib.type === "VECTOR" && sib.visible !== false) found = true;
    }
    if (!found) return "no visible real mark beside it any more - refusing to delete the only record";
  }

  return null;
}

/* ---------- dry run ---------- */

async function dryRun() {
  await allPages();
  var report = { items: [], counts: { ready: 0, blocked: 0, byGroup: {} }, unexpected: [], pages: {} };

  for (var i = 0; i < CLEANUP_PLAN.mutations.length; i++) {
    var m = CLEANUP_PLAN.mutations[i];
    var why = null;
    try { why = await verify(m); } catch (e) { why = "verification threw: " + (e && e.message ? e.message : String(e)); }

    var status = why ? "BLOCKED" : "READY";
    if (why) report.counts.blocked++; else report.counts.ready++;
    report.counts.byGroup[m.group] = report.counts.byGroup[m.group] || { ready: 0, blocked: 0 };
    report.counts.byGroup[m.group][why ? "blocked" : "ready"]++;
    report.pages[m.page] = true;

    report.items.push({ id: m.id, group: m.group, page: m.page, frame: m.frame, node: m.node, path: m.path,
                        current: m.current, proposed: m.proposed, status: status, why: why,
                        pixelsMove: m.pixelsMove, wrapChange: m.wrapChange, visualChange: m.visualChange });
  }

  // Nothing outside the approved groups may be in the plan at all.
  for (var g in report.counts.byGroup)
    if (Object.prototype.hasOwnProperty.call(report.counts.byGroup, g) && !APPROVED_GROUPS[g])
      report.unexpected.push("plan contains unapproved group '" + g + "'");
  for (var p in report.pages)
    if (Object.prototype.hasOwnProperty.call(report.pages, p) && isCanonicalName(p))
      report.unexpected.push("plan targets canonical page '" + p + "'");

  report.declared = {
    pixelsMove: CLEANUP_PLAN.mutations.filter(function (m) { return m.pixelsMove; }).length,
    wrapChange: CLEANUP_PLAN.mutations.filter(function (m) { return m.wrapChange; }).length,
    visualChange: CLEANUP_PLAN.mutations.filter(function (m) { return m.visualChange; }).length
  };

  dryRunReport = report;
  return report;
}

/* ---------- apply ---------- */

function writeMutation(node, m) {
  var a = m.apply;
  if (a.op === "setAutoLayout") {
    node.layoutMode = a.layoutMode;
    node.itemSpacing = a.itemSpacing;
    node.paddingTop = a.padding.top; node.paddingRight = a.padding.right;
    node.paddingBottom = a.padding.bottom; node.paddingLeft = a.padding.left;
    node.primaryAxisAlignItems = a.primaryAxisAlignItems;
    node.counterAxisAlignItems = a.counterAxisAlignItems;
    node.primaryAxisSizingMode = a.primaryAxisSizingMode;
    node.counterAxisSizingMode = a.counterAxisSizingMode;
  } else if (a.op === "setSizing") {
    node.primaryAxisSizingMode = a.primaryAxisSizingMode;
  } else if (a.op === "setTextAutoResize") {
    node.textAutoResize = a.textAutoResize;
  } else if (a.op === "rename") {
    node.name = a.name;
  } else if (a.op === "remove") {
    node.remove();
  } else {
    throw new Error("unknown op '" + a.op + "'");
  }
}

async function apply() {
  if (!dryRunReport) throw new Error("Run Dry Run first.");
  if (dryRunReport.unexpected.length) throw new Error("Dry Run reported unexpected scope; refusing.");

  var byId = {};
  for (var i = 0; i < CLEANUP_PLAN.mutations.length; i++) byId[CLEANUP_PLAN.mutations[i].id] = CLEANUP_PLAN.mutations[i];

  var done = { applied: 0, skipped: 0, reverted: 0, failed: 0, moved: [], notes: [], errors: [], byGroup: {} };

  for (var j = 0; j < dryRunReport.items.length; j++) {
    var item = dryRunReport.items[j];
    if (item.status !== "READY") { done.skipped++; continue; }
    var m = byId[item.id];

    // re-verify immediately before writing; the document may have changed
    var why = await verify(m);
    if (why) { done.skipped++; done.notes.push(m.id + " skipped on re-check: " + why); continue; }

    var node = await figma.getNodeByIdAsync(m.nodeId);
    var isLayout = m.apply.op === "setAutoLayout" || m.apply.op === "setSizing";
    var before = isLayout ? snapshotPositions(node) : null;

    try {
      writeMutation(node, m);

      if (isLayout) {
        var moved = comparePositions(before, snapshotPositions(node));
        if (moved.length) {
          // The whole point of this pass is that nothing moves. Put it back.
          if (m.apply.op === "setAutoLayout") {
            node.layoutMode = "NONE";
            for (var b = 0; b < before.length; b++) {
              var rec = before[b];
              if (rec.id === node.id) continue;
              var child = await figma.getNodeByIdAsync(rec.id);
              if (child && child.parent === node) {
                child.x = rec.x - before[0].x;
                child.y = rec.y - before[0].y;
              }
            }
          } else {
            node.primaryAxisSizingMode = m.check.primaryAxisSizingMode;
          }
          done.reverted++;
          done.moved.push(m.id + " (" + m.frame + " · " + m.node + "): " + moved.slice(0, 3).join("; "));
          continue;
        }
      }

      done.applied++;
      done.byGroup[m.group] = (done.byGroup[m.group] || 0) + 1;
    } catch (e) {
      done.failed++;
      done.errors.push(m.id + ": " + (e && e.message ? e.message : String(e)));
    }
  }

  dryRunReport = null;
  return done;
}

/* ---------- plumbing ---------- */

figma.showUI(__html__, { width: 480, height: 640 });

figma.ui.onmessage = async function (msg) {
  try {
    if (msg.type === "dryrun") {
      figma.ui.postMessage({ type: "status", text: "Verifying every target against the live file…" });
      var r = await dryRun();
      figma.ui.postMessage({ type: "dryrun-result", report: r });
    } else if (msg.type === "apply") {
      figma.ui.postMessage({ type: "status", text: "Applying…" });
      var d = await apply();
      figma.ui.postMessage({ type: "apply-result", done: d });
    }
  } catch (e) {
    figma.ui.postMessage({ type: "error", text: (e && e.stack) ? e.stack : String(e) });
  }
};
