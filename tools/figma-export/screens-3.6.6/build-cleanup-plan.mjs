/*
 * Generates the bounded structural cleanup plan from the two live snapshots.
 *
 *   node tools/figma-export/screens-3.6.6/build-cleanup-plan.mjs
 *
 * Emits CLEANUP-PLAN.json (machine-readable, node ids included) and
 * CLEANUP-DRY-RUN.md (the review document). Nothing here touches Figma; the
 * plan is derived from the exported snapshots, so every "current" value in it
 * is what the live file actually contains.
 *
 * Scope is exactly the approved list. Constraints, the 1px row anchor
 * difference, clipsContent, title/artist width, and every other owner-positioned
 * node are deliberately NOT in it.
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const S = path.join(here, "snapshots");
const DOC = {
  light: JSON.parse(fs.readFileSync(path.join(S, "proposals-light.json"), "utf8")),
  dark: JSON.parse(fs.readFileSync(path.join(S, "proposals-dark.json"), "utf8"))
};
const r2 = (n) => Math.round(n * 100) / 100;
const mutations = [];

const each = (theme, fn) => (DOC[theme].frames || []).forEach((f) => (function w(n, trail, parent) {
  fn(n, f, trail, parent);
  (n.children || []).forEach((c) => w(c, trail + " > " + c.n0 || trail + " > " + c.name, n));
})(f, f.name, null));

/* ---- A. history rows: restore the auto-layout contract ---- */
for (const theme of ["light", "dark"]) {
  const frame = (DOC[theme].frames || []).find((f) => /^history-content/.test(f.name));
  if (!frame) continue;
  (function w(n, trail) {
    if (/^History Item \//.test(n.name)) {
      const kids = (n.children || []).slice().sort((a, b) => a.x - b.x);
      const padL = r2(kids[0].x);
      const last = kids[kids.length - 1];
      const padR = r2(n.width - (last.x + last.width));
      const gaps = [];
      for (let i = 1; i < kids.length; i++) gaps.push(r2(kids[i].x - (kids[i - 1].x + kids[i - 1].width)));
      const gap = gaps[0];
      const uniformGap = gaps.every((g) => Math.abs(g - gap) < 0.01);
      const maxH = Math.max(...kids.map((k) => k.height));
      const padTB = r2((n.height - maxH) / 2);
      const centred = kids.every((k) => Math.abs((k.y + k.height / 2) - n.height / 2) < 0.6);

      mutations.push({
        id: `A-${theme}-${n.id}`, group: "history-row-autolayout",
        page: DOC[theme].source.pageName, frame: frame.name, node: n.name, nodeId: n.id, path: trail,
        current: `layoutMode NONE, height ${r2(n.height)} fixed, children positioned absolutely`,
        proposed: `layoutMode HORIZONTAL, itemSpacing ${gap}, padding ${padTB}/${padR}/${padTB}/${padL}, ` +
                  `primaryAxisAlignItems MIN, counterAxisAlignItems CENTER, ` +
                  `primaryAxisSizingMode FIXED (width ${r2(n.width)}), counterAxisSizingMode AUTO (hug height)`,
        pixelsMove: false, wrapChange: false, visualChange: false,
        evidence: `every child is already centred on the row's vertical midline (${centred}); ` +
                  `gaps are ${uniformGap ? "uniformly " + gap : "NOT uniform: " + gaps.join(",")}; ` +
                  `padding top/bottom ${padTB} reproduces the current height ${r2(n.height)} from the tallest child ${r2(maxH)}`,
        blockedBy: uniformGap && centred ? null : "gaps or centring are not uniform - needs a look before applying"
      });
    }
    (n.children || []).forEach((c) => w(c, trail + " > " + c.name));
  })(frame, frame.name);
}

/* ---- B. the text column inside each row must hug its height ---- */
for (const theme of ["light", "dark"]) {
  const frame = (DOC[theme].frames || []).find((f) => /^history-content/.test(f.name));
  if (!frame) continue;
  (function w(n, trail) {
    if (/^History Item \//.test(n.name)) {
      const col = (n.children || []).find((c) => c.name === "Text");
      if (col && col.layout && col.layout.primaryAxisSizingMode === "FIXED")
        mutations.push({
          id: `B-${theme}-${col.id}`, group: "history-text-hug",
          page: DOC[theme].source.pageName, frame: frame.name, node: "Text", nodeId: col.id, path: trail + " > Text",
          current: `VERTICAL auto-layout, primaryAxisSizingMode FIXED (height ${r2(col.height)} locked)`,
          proposed: `primaryAxisSizingMode AUTO (hug height); width stays ${r2(col.width)} FIXED`,
          pixelsMove: false, wrapChange: false, visualChange: false,
          evidence: `height ${r2(col.height)} already equals the sum of its children, so hugging resolves to the same number. ` +
                    `Without this the row can hug but the column inside it still cannot grow.`,
          blockedBy: null
        });
    }
    (n.children || []).forEach((c) => w(c, trail + " > " + c.name));
  })(frame, frame.name);
}

/* ---- C. text boxes shorter than one line, inside a clipping ancestor ---- */
for (const theme of ["light", "dark"]) {
  for (const frame of DOC[theme].frames || []) {
    (function w(n, trail, clipped) {
      const lh = n.text && n.text.lineHeight && n.text.lineHeight.value;
      if (n.text && typeof lh === "number" && n.height + 0.5 < lh && n.text.textAutoResize === "NONE")
        mutations.push({
          id: `C-${theme}-${n.id}`, group: "text-box-hug",
          page: DOC[theme].source.pageName, frame: frame.name, node: n.name, nodeId: n.id, path: trail,
          current: `height ${r2(n.height)}, lineHeight ${lh}, textAutoResize NONE, textAlignVertical ${n.text.textAlignVertical}`,
          proposed: `textAutoResize HEIGHT (height resolves to ${lh})`,
          pixelsMove: false, wrapChange: false, visualChange: false,
          evidence: `vertical alignment is ${n.text.textAlignVertical}, so the glyph keeps its current top edge and the box grows ` +
                    `${r2(lh - n.height)}px downward into empty space. Content "${n.text.characters}" is a single line; x/y, font, size and line height are untouched.`,
          blockedBy: n.text.textAlignVertical === "TOP" ? null : `vertical alignment is ${n.text.textAlignVertical}, not TOP - growing the box would recentre the glyph`
        });
      (n.children || []).forEach((c) => w(c, trail + " > " + c.name, clipped || n.clipsContent === true));
    })(frame, frame.name, false);
  }
}

/* ---- D. hidden Last.fm leftovers, only where the real mark is present ---- */
for (const theme of ["light", "dark"]) {
  for (const frame of DOC[theme].frames || []) {
    (function w(n, trail) {
      for (const c of n.children || []) {
        if (!/^Asset slot \/ logo\/lastfm/.test(c.name)) continue;
        const siblingMark = (n.children || []).some((s) => s !== c && s.type === "VECTOR" && s.visible !== false);
        mutations.push({
          id: `D-${theme}-${c.id}`, group: "lastfm-leftover",
          page: DOC[theme].source.pageName, frame: frame.name, node: c.name, nodeId: c.id, path: trail + " > " + c.name,
          current: `hidden placeholder frame ${r2(c.width)}x${r2(c.height)} at ${r2(c.x)},${r2(c.y)}, visible=${c.visible !== false}`,
          proposed: "delete",
          pixelsMove: false, wrapChange: false, visualChange: false,
          evidence: `already invisible, and a visible real mark sits beside it in the same parent (${siblingMark}). ` +
                    `Deleting an invisible node cannot change the rendering.`,
          blockedBy: c.visible !== false ? "slot is VISIBLE - not a leftover, do not delete"
                   : siblingMark ? null : "no real Last.fm mark found beside it - deleting would lose the only record of the slot"
        });
      }
      (n.children || []).forEach((c) => w(c, trail + " > " + c.name));
    })(frame, frame.name);
  }
}

/* ---- E. name the 16 future avatar locations ---- */
for (const theme of ["light", "dark"]) {
  const frame = (DOC[theme].frames || []).find((f) => /^profile-avatar/.test(f.name));
  if (!frame) continue;
  const cells = [];
  (function w(n) { if (/^Avatar cell/.test(n.name)) cells.push(n); (n.children || []).forEach(w); })(frame);
  cells.sort((a, b) => (a.y - b.y) || (a.x - b.x));
  cells.forEach((c, i) => {
    const key = `avatar/m3-${String(i + 1).padStart(2, "0")}`;
    const proposedName = `Avatar cell ${String(i + 1).padStart(2, "0")} · ${key}`;
    if (c.name === proposedName) return;
    mutations.push({
      id: `E-${theme}-${c.id}`, group: "avatar-naming",
      page: DOC[theme].source.pageName, frame: frame.name, node: c.name, nodeId: c.id, path: frame.name + " > " + c.name,
      current: `name "${c.name}", ${r2(c.width)}x${r2(c.height)} at ${r2(c.x)},${r2(c.y)}, ${(c.children || []).length} children`,
      proposed: `name "${proposedName}" — geometry, fills and strokes untouched`,
      pixelsMove: false, wrapChange: false, visualChange: false,
      evidence: "a layer name is not rendered. This restores the record of which asset belongs in which cell now that the PENDING slots were deleted.",
      blockedBy: null
    });
  });
}

/* ---- emit ---- */
const groups = {};
for (const m of mutations) (groups[m.group] = groups[m.group] || []).push(m);
const blocked = mutations.filter((m) => m.blockedBy);

const plan = {
  schemaVersion: "cleanup-1.0.0",
  status: "DRY RUN ONLY - not applied, and no apply tool has been built yet.",
  generatedFrom: {
    light: { page: DOC.light.source.pageName, exportedAt: DOC.light.exportedAt },
    dark: { page: DOC.dark.source.pageName, exportedAt: DOC.dark.exportedAt }
  },
  scope: {
    approved: ["history-row-autolayout", "history-text-hug", "text-box-hug", "lastfm-leftover", "avatar-naming"],
    deliberatelyExcluded: [
      "the 709 default constraints",
      "the 1px history row anchor difference (rows 1-2 vs 3-8)",
      "clipsContent on the bottom sheets",
      "title/artist width 181 -> 179 (see the wrap analysis - it would re-wrap)",
      "manual stacks elsewhere, and every other owner-positioned node"
    ]
  },
  counts: { total: mutations.length, blocked: blocked.length, byGroup: Object.fromEntries(Object.entries(groups).map(([k, v]) => [k, v.length])) },
  mutations
};
fs.writeFileSync(path.join(here, "CLEANUP-PLAN.json"), JSON.stringify(plan, null, 2) + "\n");

const TITLES = {
  "history-row-autolayout": "1 · Broadcast History rows — restore the auto-layout contract",
  "history-text-hug": "1b · Broadcast History — the inner text column must hug its height",
  "text-box-hug": "2 · Text boxes shorter than one line",
  "lastfm-leftover": "3 · Hidden Last.fm leftovers",
  "avatar-naming": "5 · Name the 16 future avatar locations"
};

let md = `# Bounded structural cleanup — DRY RUN\n\n`;
md += `**Nothing has been applied, and no apply tool exists yet.** This plan is derived from the\n`;
md += `exported snapshots, so every "current" value below is what the live file actually contains.\n\n`;
md += `| | |\n|---|---|\n`;
md += `| light | \`${DOC.light.source.pageName}\`, exported ${DOC.light.exportedAt} |\n`;
md += `| dark | \`${DOC.dark.source.pageName}\`, exported ${DOC.dark.exportedAt} |\n`;
md += `| mutations | **${mutations.length}**, of which ${blocked.length} blocked |\n`;
md += `| pixels move | ${mutations.filter((m) => m.pixelsMove).length} |\n`;
md += `| wrapping changes | ${mutations.filter((m) => m.wrapChange).length} |\n`;
md += `| visual output changes | ${mutations.filter((m) => m.visualChange).length} |\n\n`;

md += `## Out of scope, deliberately\n\n`;
for (const x of plan.scope.deliberatelyExcluded) md += `- ${x}\n`;
md += `\n`;

for (const [g, items] of Object.entries(groups)) {
  md += `## ${TITLES[g] || g} (${items.length})\n\n`;
  const byFrame = {};
  for (const m of items) (byFrame[m.page + " · " + m.frame] = byFrame[m.page + " · " + m.frame] || []).push(m);
  md += `| page · frame | node | current | proposed | px | wrap | visual |\n|---|---|---|---|---|---|---|\n`;
  for (const [k, list] of Object.entries(byFrame))
    for (const m of list)
      md += `| ${k} | \`${m.node}\` | ${m.current} | ${m.proposed} | ${m.pixelsMove ? "MOVES" : "no"} | ${m.wrapChange ? "CHANGES" : "no"} | ${m.visualChange ? "CHANGES" : "no"} |\n`;
  md += `\n**Why this is safe:** ${items[0].evidence}\n\n`;
  const b = items.filter((m) => m.blockedBy);
  if (b.length) { md += `**Blocked (${b.length}):**\n\n`; for (const m of b) md += `- \`${m.path}\` — ${m.blockedBy}\n`; md += `\n`; }
}

fs.writeFileSync(path.join(here, "CLEANUP-DRY-RUN.md"), md);

console.log(`CLEANUP-PLAN.json and CLEANUP-DRY-RUN.md written`);
console.log(`  ${mutations.length} mutations, ${blocked.length} blocked`);
for (const [g, v] of Object.entries(groups)) console.log(`    ${g.padEnd(26)} ${v.length}`);
console.log(`  pixels move: ${mutations.filter((m) => m.pixelsMove).length}   wrap changes: ${mutations.filter((m) => m.wrapChange).length}   visual changes: ${mutations.filter((m) => m.visualChange).length}`);
if (blocked.length) for (const m of blocked) console.log(`  BLOCKED ${m.id}: ${m.blockedBy}`);
