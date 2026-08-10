/*
 * Read-only audit of the LIVE 3.6.6 proposal pages.
 *
 *   node tools/figma-export/screens-3.6.6/audit-live.mjs \
 *        snapshots/proposals-light.json snapshots/proposals-dark.json
 *
 * The live pages are the source of truth. This script never proposes moving or
 * resizing anything: where the owner's pixels and the layout machinery disagree,
 * the pixels win and the finding says how to make the machinery agree with them.
 *
 * It reads raw exporter output, not the normalized baselines, because the
 * normalizer drops exactly the fields an audit needs - textAutoResize,
 * textTruncation, maxLines, layoutPositioning, locked, clipsContent.
 *
 * Writes AUDIT-REPORT.md and prints a summary. It reads files; it cannot touch Figma.
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const [, , lightPath, darkPath, ...rest] = process.argv;
if (!lightPath || !darkPath) {
  console.error("usage: node audit-live.mjs <proposals-light.json> <proposals-dark.json> [--out FILE]");
  process.exit(1);
}
const outArg = rest.indexOf("--out");
const outPath = outArg >= 0 ? rest[outArg + 1] : path.join(here, "AUDIT-REPORT.md");

const spec = JSON.parse(fs.readFileSync(path.join(here, "spec.json"), "utf8"));
const load = (p) => JSON.parse(fs.readFileSync(p, "utf8"));
const DOC = { light: load(lightPath), dark: load(darkPath) };

/* ---------- findings ---------- */

const findings = [];
// blocking - would break the Android build or lose content
// review   - needs an owner decision; nothing is changed without approval
// info     - expected or merely noted
const add = (severity, check, o) => findings.push({ severity, check, ...o });

/* ---------- helpers ---------- */

const walk = (node, fn, parent = null, depth = 0, trail = "", clipped = false) => {
  const p = trail ? trail + " > " + node.name : node.name;
  fn(node, parent, depth, p, clipped);
  const c = clipped || node.clipsContent === true;
  (node.children || []).forEach((k) => walk(k, fn, node, depth + 1, p, c));
};
const eachNode = (theme, fn) => (DOC[theme].frames || []).forEach((f) => walk(f, (n, p, d, t, clip) => fn(n, p, d, t, f, clip), null, 0, ""));
const isAuto = (n) => n.layout && n.layout.layoutMode && n.layout.layoutMode !== "NONE";
const lh = (n) => (n.text && n.text.lineHeight && typeof n.text.lineHeight.value === "number" ? n.text.lineHeight.value : null);
const baseName = (n) => n.replace(/_dark$/, "");
const num = (v) => (typeof v === "number" ? Math.round(v * 100) / 100 : v);

/* ---------- 1. coverage ---------- */

const expectedIds = spec.screens.map((s) => s.id);
const present = {};
for (const theme of ["light", "dark"]) {
  present[theme] = new Set((DOC[theme].frames || []).map((f) => baseName(f.name)));
}
for (const id of expectedIds) {
  for (const theme of ["light", "dark"]) {
    if (!present[theme].has(id))
      add("review", "coverage", { theme, frame: id, message: `screen '${id}' has no top-level frame on the ${theme} page`,
        proposal: "Either it was renamed during editing - say what to, and the audit will follow it - or it was never created." });
  }
}
for (const theme of ["light", "dark"]) {
  for (const name of present[theme])
    if (!expectedIds.includes(name))
      add("info", "coverage", { theme, frame: name, message: `extra top-level frame '${name}' not in the spec`,
        proposal: "Owner-added; noted only." });
}

/* ---------- 2. light/dark parity ---------- */

// The root frame carries a _dark suffix by design, so compare on the base name.
const sig = (n) => `${n.type}:${baseName(n.name)}`;
const treeSig = (n) => { const out = []; walk(n, (x, p, d) => out.push("  ".repeat(d) + sig(x))); return out; };

for (const id of expectedIds) {
  const L = (DOC.light.frames || []).find((f) => baseName(f.name) === id);
  const D = (DOC.dark.frames || []).find((f) => baseName(f.name) === id);
  if (!L || !D) continue;
  const a = treeSig(L), b = treeSig(D);
  if (a.length !== b.length)
    add("review", "parity", { frame: id, message: `node count differs: light ${a.length}, dark ${b.length}`,
      proposal: "Compare the two frames; a deliberate per-theme difference is fine, an accidental extra or missing layer is not." });
  const n = Math.min(a.length, b.length);
  const diffs = [];
  for (let i = 0; i < n && diffs.length < 4; i++) if (a[i] !== b[i]) diffs.push(`light "${a[i].trim()}" vs dark "${b[i].trim()}"`);
  if (diffs.length)
    add("review", "parity", { frame: id, message: `structure diverges: ${diffs.join("; ")}`,
      proposal: "Check whether the divergence is intentional." });
  // geometry parity on the frame itself
  if (num(L.width) !== num(D.width) || num(L.height) !== num(D.height))
    add("review", "parity", { frame: id, message: `frame size differs: light ${num(L.width)}x${num(L.height)}, dark ${num(D.width)}x${num(D.height)}`,
      proposal: "Sizes should match unless a theme genuinely needs more room." });
}

/* ---------- 3. text overflow, clipping, ellipsis ---------- */

for (const theme of ["light", "dark"]) {
  eachNode(theme, (n, parent, d, trail, frame, clipped) => {
    if (n.type !== "TEXT" || !n.text) return;
    const t = n.text;
    const where = { theme, frame: frame.name, path: trail };

    if (t.textTruncation === "ENDING")
      add("blocking", "text", { ...where, message: `"${String(t.characters).slice(0, 40)}" has truncation ENDING - Figma will render an ellipsis`,
        proposal: "Set Truncate text to none. This is content loss, not styling." });

    if (typeof t.maxLines === "number" && t.maxLines > 0)
      add("blocking", "text", { ...where, message: `"${String(t.characters).slice(0, 40)}" is capped at ${t.maxLines} line(s)`,
        proposal: "Remove the line cap so long titles stay readable." });

    // A box shorter than one line only actually loses pixels when something
    // above it clips; otherwise Figma lets the glyphs overflow and it is a
    // declared-height bug that will bite in Android rather than a visible one.
    const L = lh(n);
    if (t.textAutoResize === "NONE" && L && n.height + 0.5 < L)
      add(clipped ? "blocking" : "review", "text", { ...where,
        message: `box is ${num(n.height)}px against a ${L}px line height${clipped ? " and an ancestor clips - the glyphs are cut" : " (no ancestor clips, so it overflows rather than cutting)"}`,
        proposal: `Set vertical resizing to Hug, or raise the height to ${L}px. Both keep the current x/y.` });

    if (t.textAutoResize === "NONE" && String(t.characters).includes("\n"))
      add("review", "text", { ...where, message: `contains a hard line break but vertical resizing is fixed`,
        proposal: "Set vertical resizing to Hug so every line is shown." });

    if (parent && !isAuto(parent) && typeof parent.width === "number" && n.x + n.width > parent.width + 1)
      add("review", "text", { ...where, message: `extends ${num(n.x + n.width - parent.width)}px past the right edge of "${parent.name}"`,
        proposal: "Narrow the text box or widen the parent; do not move the text." });

    // Inside a vertical auto-layout the child's width is the cross axis, so a
    // child wider than its parent silently hangs out of the column.
    if (parent && isAuto(parent) && parent.layout.layoutMode === "VERTICAL" &&
        typeof parent.width === "number" && n.width > parent.width + 1)
      add("review", "text", { ...where, message: `is ${num(n.width)}px wide inside the ${num(parent.width)}px column "${parent.name}" - ${num(n.width - parent.width)}px hangs out`,
        proposal: `Set the text to Fill container, or widen "${parent.name}" to ${num(n.width)}. Text wraps at its own width, so the wrap point today is ${num(n.width)}, not the column width.` });
  });
}

/* ---------- 4. hidden, duplicate, locked ---------- */

for (const theme of ["light", "dark"]) {
  eachNode(theme, (n, parent, d, trail, frame) => {
    if (n.visible === false)
      add("review", "hidden", { theme, frame: frame.name, path: trail, message: `hidden node <${n.type}> ${num(n.width)}x${num(n.height)}`,
        proposal: "Delete it if it is a leftover; keep it only if it documents an alternate state." });
    if (n.locked === true)
      add("info", "locked", { theme, frame: frame.name, path: trail, message: "locked layer", proposal: "Noted only." });
  });

  eachNode(theme, (n, parent, d, trail, frame) => {
    const kids = n.children || [];
    const seen = new Map();
    for (const c of kids) {
      const k = `${c.type}|${c.name}|${num(c.x)}|${num(c.y)}|${num(c.width)}|${num(c.height)}`;
      if (seen.has(k))
        add("review", "duplicate", { theme, frame: frame.name, path: trail + " > " + c.name,
          message: `two identical siblings "${c.name}" <${c.type}> at ${num(c.x)},${num(c.y)} ${num(c.width)}x${num(c.height)}`,
          proposal: "Almost certainly an accidental duplicate. Delete one - it changes nothing visually." });
      seen.set(k, true);
    }
  });
}

/* ---------- 5. auto layout integrity and absolute positioning ---------- */

for (const theme of ["light", "dark"]) {
  eachNode(theme, (n, parent, d, trail, frame) => {
    if (parent && isAuto(parent) && n.layoutPositioning === "ABSOLUTE")
      add("review", "autolayout", { theme, frame: frame.name, path: trail,
        message: `absolutely positioned inside the auto-layout frame "${parent.name}"`,
        proposal: "Fine for a badge or overlay. If it is a flow item, the Android translation will not reproduce it - convert it back to a flow child." });

    if (!isAuto(n)) return;
    const l = n.layout, kids = (n.children || []).filter((c) => c.layoutPositioning !== "ABSOLUTE" && c.visible !== false);
    if (!kids.length) return;

    const horiz = l.layoutMode === "HORIZONTAL";
    const pad = l.padding || { top: 0, right: 0, bottom: 0, left: 0 };
    const content = kids.reduce((a, c) => a + (horiz ? c.width : c.height), 0) + (kids.length - 1) * (l.itemSpacing || 0);
    const along = content + (horiz ? pad.left + pad.right : pad.top + pad.bottom);
    const frameAlong = horiz ? n.width : n.height;
    const sizing = horiz ? l.primaryAxisSizingMode : l.primaryAxisSizingMode;

    if (sizing === "FIXED" && along > frameAlong + 1)
      add("review", "autolayout", { theme, frame: frame.name, path: trail,
        message: `content needs ${num(along)}px along the ${horiz ? "horizontal" : "vertical"} axis but the frame is fixed at ${num(frameAlong)}px`,
        proposal: "Set that axis to Hug, or grow the frame. Hug preserves the current child positions." });

    const grows = kids.filter((c) => c.layoutGrow === 1).length;
    if (sizing === "FIXED" && along + 1 < frameAlong && grows === 0)
      add("info", "autolayout", { theme, frame: frame.name, path: trail,
        message: `${num(frameAlong - along)}px of slack on a fixed axis with nothing set to grow`,
        proposal: "Harmless, but Hug or a growing child would make the intent explicit." });
  });
}

/* ---------- 6. constraints on absolutely-laid-out children ---------- */

for (const theme of ["light", "dark"]) {
  eachNode(theme, (n, parent, d, trail, frame) => {
    if (!parent || isAuto(parent) || !n.constraints || typeof parent.width !== "number") return;
    const c = n.constraints;
    const rightGap = parent.width - (n.x + n.width);
    const spansWidth = n.x <= 20 && rightGap <= 20 && n.width > parent.width * 0.6;
    const pinnedRight = rightGap <= 2 && n.x > parent.width * 0.5;

    if (spansWidth && c.horizontal === "MIN")
      add("review", "constraints", { theme, frame: frame.name, path: trail,
        message: `spans the parent width (left ${num(n.x)}, right ${num(rightGap)}) but is pinned Left only`,
        proposal: "Set horizontal constraint to Left and right. Pure metadata - no pixel moves." });

    if (pinnedRight && c.horizontal === "MIN")
      add("review", "constraints", { theme, frame: frame.name, path: trail,
        message: `sits ${num(rightGap)}px from the right edge but is pinned Left`,
        proposal: "Set horizontal constraint to Right so it survives a width change. No pixel moves." });
  });
}

/* ---------- 7. spacing consistency in hand-positioned stacks ---------- */

for (const theme of ["light", "dark"]) {
  eachNode(theme, (n, parent, d, trail, frame) => {
    if (isAuto(n)) return;
    const kids = (n.children || []).filter((c) => c.visible !== false && typeof c.y === "number");
    if (kids.length < 3) return;
    const rows = [...kids].sort((a, b) => a.y - b.y);
    const sameWidth = rows.every((r) => Math.abs(r.width - rows[0].width) < 1);
    if (!sameWidth) return;
    const gaps = [];
    for (let i = 1; i < rows.length; i++) gaps.push(num(rows[i].y - (rows[i - 1].y + rows[i - 1].height)));
    const uniq = [...new Set(gaps)];
    if (uniq.length > 1 && Math.max(...uniq) - Math.min(...uniq) > 1)
      add("review", "spacing", { theme, frame: frame.name, path: trail,
        message: `${rows.length} same-width children with uneven gaps: ${gaps.join(", ")}`,
        proposal: `Converting "${n.name}" to a vertical auto-layout with gap ${Math.min(...uniq)} would regularise it, but that MOVES children. Needs approval, or leave as is and note the intent.` });
    else if (uniq.length === 1 && rows.length >= 4)
      add("info", "spacing", { theme, frame: frame.name, path: trail,
        message: `${rows.length} evenly spaced children (gap ${uniq[0]}), positioned absolutely`,
        proposal: `A vertical auto-layout with gap ${uniq[0]} would reproduce the exact same pixels and make it robust. Zero pixel change - safe to apply on approval.` });
  });
}

/* ---------- 8. bottom sheets ---------- */

for (const theme of ["light", "dark"]) {
  for (const frame of DOC[theme].frames || []) {
    walk(frame, (n, parent, d, trail) => {
      // The sheet's own parts are named "Bottom Sheet / drag handle" etc, so
      // match the container only.
      if (!/^Bottom Sheet \//.test(n.name)) return;
      if (/^Bottom Sheet \/ (drag handle|title|subtitle)$/.test(n.name)) return;
      const where = { theme, frame: frame.name, path: trail };
      const r = n.cornerRadius;

      if (typeof r === "number") {
        if (r === 0) add("blocking", "sheet", { ...where, message: "sheet has square corners", proposal: "Restore a 28px radius on the top corners." });
        else add("info", "sheet", { ...where, message: `uniform corner radius ${r} (top and bottom)`,
          proposal: "On Android a sheet anchored to the bottom shows only its top corners, so a uniform radius is harmless. Top-only (28/28/0/0) is closer to the real thing." });
      } else if (r && typeof r === "object") {
        add("info", "sheet", { ...where, message: `corner radius ${r.topLeft}/${r.topRight}/${r.bottomRight}/${r.bottomLeft}`, proposal: "Top-only rounding is correct." });
      }

      if (n.clipsContent === false)
        add("review", "sheet", { ...where, message: "clipsContent is off",
          proposal: "Turn it on so children cannot paint over the rounded corners. No geometry change." });

      // anything opaque covering the sheet with square corners squares off the corners
      for (const c of n.children || []) {
        const covers = Math.abs(c.width - n.width) < 2 && Math.abs(c.height - n.height) < 2 && Math.abs(c.x) < 2 && Math.abs(c.y) < 2;
        const opaque = (c.fills || []).some((f) => f.type === "SOLID" && f.visible !== false);
        const square = !c.cornerRadius || c.cornerRadius === 0;
        if (covers && opaque && square)
          add("blocking", "sheet", { ...where, message: `child "${c.name}" covers the whole sheet with square corners and an opaque fill`,
            proposal: "Give it the same radius as the sheet, or delete it and put the fill on the sheet frame. The corners are currently not transparent." });
      }

      const handle = (n.children || []).find((c) => /handle/i.test(c.name));
      if (!handle) add("review", "sheet", { ...where, message: "no drag handle found", proposal: "The canonical sheet has a 40x4 handle at y=16." });
      else {
        const centred = Math.abs((handle.x + handle.width / 2) - n.width / 2) < 1.5;
        if (!centred) add("review", "sheet", { ...where, message: `drag handle is off-centre by ${num((handle.x + handle.width / 2) - n.width / 2)}px`,
          proposal: "Centre it horizontally. A 1px nudge, owner's call." });
      }
    });
  }
}

/* ---------- 9. broadcast history rows ---------- */

const specTitles = new Set();
(function collectSpec() {
  const s = spec.screens.find((x) => x.id === "history-content");
  if (!s) return;
  (function w(ns) { for (const n of ns) { if (n.t === "TEXT") specTitles.add(n.text.s); if (n.ch) w(n.ch); } })(s.nodes);
})();

for (const theme of ["light", "dark"]) {
  const anchors = {};
  for (const frame of DOC[theme].frames || []) {
    if (!/^history-content/.test(baseName(frame.name))) continue;
    walk(frame, (n, parent, d, trail) => {
      if (!/^History Item \//.test(n.name)) return;
      const where = { theme, frame: frame.name, path: trail };

      if (!isAuto(n))
        add("blocking", "history", { ...where, message: "row is not an auto-layout frame, so it cannot grow with a long title",
          proposal: "Restore Horizontal auto-layout, padding 13, gap 8, cross-axis align Top, vertical Hug. That reproduces the current single-line height of 74." });
      else {
        if (n.layout.counterAxisSizingMode !== "AUTO")
          add("blocking", "history", { ...where, message: `row height is ${n.layout.counterAxisSizingMode}, not Hug - a wrapped title will be clipped`,
            proposal: "Set vertical sizing to Hug. Single-line rows stay at 74px, so nothing visible moves." });
        if (n.layout.counterAxisAlignItems !== "MIN")
          add("review", "history", { ...where, message: `cross-axis alignment is ${n.layout.counterAxisAlignItems}, so the time drifts to the middle of a tall row`,
            proposal: "Set it to Top." });
      }

      for (const c of n.children || []) {
        (anchors[c.name] = anchors[c.name] || new Set()).add(num(c.x));
        if (c.type === "TEXT" && c.text) {
          if (c.text.textAutoResize !== "HEIGHT" && /title|artist/i.test(c.name))
            add("blocking", "history", { ...where, message: `"${c.name}" has vertical resizing ${c.text.textAutoResize}, so a long value is cut off`,
              proposal: "Set vertical resizing to Hug. Short values keep their current height." });
          if (c.text.characters && !specTitles.has(c.text.characters) && /title|artist/i.test(c.name))
            add("info", "history", { ...where, message: `"${c.name}" text differs from the spec: "${String(c.text.characters).slice(0, 44)}"`,
              proposal: "Owner edit; noted only." });
        }
      }
    });
  }
  for (const [name, xs] of Object.entries(anchors))
    if (xs.size > 1)
      add("review", "history", { theme, frame: "history-content", path: name,
        message: `"${name}" is not at the same x in every row: ${[...xs].sort((a, b) => a - b).join(", ")}`,
        proposal: "Rows should share one anchor. Fix by making the rows consistent auto-layouts rather than by dragging." });
}

/* ---------- 10. asset slots ---------- */

const slots = {};
for (const theme of ["light", "dark"]) {
  eachNode(theme, (n, parent, d, trail, frame) => {
    const m = /^Asset slot \/ (.+?) \(/.exec(n.name);
    if (!m) return;
    const key = m[1];
    const s = (slots[key] = slots[key] || { visible: 0, hidden: 0, where: [] });
    // A hidden slot next to real artwork is a leftover, not a pending asset.
    if (n.visible === false) s.hidden++; else s.visible++;
    if (s.where.length < 4) s.where.push(`${theme} · ${frame.name}`);
  });
}
for (const [key, c] of Object.entries(slots)) {
  if (c.visible)
    add("review", "assets", { frame: "-", message: `asset '${key}' is still an empty slot in ${c.visible} place(s): ${c.where.join(", ")}`,
      proposal: key.startsWith("avatar/") ? "Expected - avatar artwork is being supplied separately." : "Owner to place the mark in Figma." });
  if (c.hidden)
    add("review", "assets", { frame: "-", message: `${c.hidden} hidden '${key}' slot(s) remain beside real artwork: ${c.where.join(", ")}`,
      proposal: "The mark has been supplied, so these are leftovers. Deleting them changes nothing visually." });
}

// Avatar cells that ended up with no artwork at all - the slot was removed
// rather than filled, so nothing in the file records what belongs there.
for (const theme of ["light", "dark"]) {
  for (const frame of DOC[theme].frames || []) {
    if (!/^profile-avatar/.test(baseName(frame.name))) continue;
    let empty = 0, total = 0;
    walk(frame, (n) => {
      if (!/^Avatar cell/.test(n.name)) return;
      total++;
      let content = 0;
      (function inner(x) {
        if (["VECTOR", "INSTANCE", "RECTANGLE", "ELLIPSE", "BOOLEAN_OPERATION"].includes(x.type)) content++;
        (x.children || []).forEach(inner);
      })(n);
      if (!content) empty++;
    });
    if (empty)
      add("review", "assets", { theme, frame: frame.name,
        message: `${empty} of ${total} avatar cells are empty rings - the pending slot was deleted rather than filled`,
        proposal: "Expected while the Material 3 avatars are outstanding, but nothing in the file now records what goes in them. Consider restoring a named placeholder, or treat this note as the record." });
  }
}

/* ---------- 11. per-flow state coverage ---------- */

const FLOWS = {
  "Sleep Timer": ["sleep-timer-select", "sleep-timer-custom", "sleep-timer-custom-invalid", "sleep-timer-active", "sleep-timer-active-custom", "sleep-timer-menu-active", "sleep-timer-cancelled", "sleep-timer-completed"],
  "Report a Problem": ["report-empty", "report-filled", "report-sending", "report-error", "report-success"],
  "Broadcast History": ["history-content", "history-loading", "history-empty", "history-error", "find-track-sheet"],
  "Collection": ["collection-track-sheet", "collection-overflow-menu"],
  "Auth / Profile": ["auth-sign-in", "auth-create-account", "profile-guest", "profile-authenticated", "profile-avatar"],
  "Settings": ["settings", "settings-appearance", "settings-sync", "settings-lastfm"]
};
const flowStatus = {};
for (const [flow, ids] of Object.entries(FLOWS)) {
  const missing = ids.filter((id) => !present.light.has(id) || !present.dark.has(id));
  flowStatus[flow] = { total: ids.length, missing };
}

/* ---------- roll up repetition ----------
 * The same structural habit repeated across forty rows is one decision, not
 * forty findings. Group by what the fix would be, keep a few examples, and
 * carry the count so nothing is hidden. */

const kindOf = (f) => f.message.replace(/-?\d+(\.\d+)?/g, "N").replace(/"[^"]*"/g, '"…"').slice(0, 90);
const grouped = [];
{
  const map = new Map();
  for (const f of findings) {
    const key = [f.severity, f.check, f.theme || "-", f.frame || "-", kindOf(f), f.proposal || ""].join("|");
    if (!map.has(key)) { const g = { ...f, count: 1, examples: [f.path].filter(Boolean) }; map.set(key, g); grouped.push(g); }
    else { const g = map.get(key); g.count++; if (g.examples.length < 3 && f.path) g.examples.push(f.path); }
  }
}

/* ---------- report ---------- */

const bySeverity = (s) => grouped.filter((f) => f.severity === s);
const countOf = (s) => grouped.filter((f) => f.severity === s).reduce((a, f) => a + f.count, 0);
const order = ["blocking", "review", "info"];
const byCheck = {};
for (const f of grouped) (byCheck[f.check] = byCheck[f.check] || []).push(f);

let md = `# Final design audit — live 3.6.6 proposal pages\n\n`;
md += `Read-only. Nothing in Figma was changed, and no fix below has been applied.\n\n`;
md += `| | |\n|---|---|\n`;
md += `| light page | \`${DOC.light.source?.pageName}\` — ${(DOC.light.frames || []).length} top-level frames, exported ${DOC.light.exportedAt} |\n`;
md += `| dark page | \`${DOC.dark.source?.pageName}\` — ${(DOC.dark.frames || []).length} top-level frames, exported ${DOC.dark.exportedAt} |\n`;
md += `| findings | **${countOf("blocking")} blocking**, ${countOf("review")} to review, ${countOf("info")} informational — grouped into ${grouped.length} distinct items |\n\n`;

md += `## Flow coverage\n\n| flow | states | missing |\n|---|---|---|\n`;
for (const [flow, s] of Object.entries(flowStatus))
  md += `| ${flow} | ${s.total - s.missing.length}/${s.total} | ${s.missing.length ? s.missing.join(", ") : "—"} |\n`;
md += `\n`;

md += `## Severity\n\n`;
md += `- **blocking** — content is lost or the layout cannot be reproduced in Android.\n`;
md += `- **review** — needs an owner decision. Nothing is changed without approval.\n`;
md += `- **info** — expected, or noted for the record.\n\n`;

for (const sev of order) {
  const list = bySeverity(sev);
  if (!list.length) continue;
  md += `## ${sev === "blocking" ? "Blocking" : sev === "review" ? "To review" : "Informational"} — ` +
        `${countOf(sev)} affected nodes in ${list.length} groups\n\n`;
  const groups = {};
  for (const f of list) (groups[f.check] = groups[f.check] || []).push(f);
  for (const [check, items] of Object.entries(groups)) {
    // Report occurrences as well as groups: "6" groups covering 10 affected
    // nodes reads as six problems otherwise.
    const occ = items.reduce((a, f) => a + f.count, 0);
    md += `### ${check} — ${occ} affected node${occ === 1 ? "" : "s"} in ${items.length} group${items.length === 1 ? "" : "s"}\n\n`;
    for (const f of items.slice(0, 40)) {
      md += `- ${f.theme ? `**${f.theme}** · ` : ""}${f.frame ? `\`${f.frame}\`` : ""}${f.count > 1 ? ` — **×${f.count}**` : ""}\n`;
      md += `  - ${f.message}\n`;
      if (f.examples.length) md += `  - ${f.count > 1 ? "e.g. " : ""}${f.examples.map((e) => `\`${e}\``).join(", ")}${f.count > f.examples.length ? ", …" : ""}\n`;
      if (f.proposal) md += `  - *Proposed:* ${f.proposal}\n`;
    }
    if (items.length > 40) md += `- … and ${items.length - 40} more distinct items of this kind\n`;
    md += `\n`;
  }
}

if (!findings.length) md += `No findings.\n`;

fs.writeFileSync(outPath, md);

console.log(`audit written to ${path.relative(process.cwd(), outPath)}`);
console.log(`  light: ${(DOC.light.frames || []).length} frames   dark: ${(DOC.dark.frames || []).length} frames`);
console.log(`  blocking ${countOf("blocking")}   review ${countOf("review")}   info ${countOf("info")}   (${grouped.length} distinct)`);
for (const [check, items] of Object.entries(byCheck))
  console.log(`    ${check.padEnd(14)} ${items.reduce((a, f) => a + f.count, 0)} in ${items.length} group(s)`);
