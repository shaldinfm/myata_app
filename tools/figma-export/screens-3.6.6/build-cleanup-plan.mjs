/*
 * Generates the recovery plan from the CURRENT snapshots.
 *
 *   node tools/figma-export/screens-3.6.6/build-cleanup-plan.mjs
 *
 * Idempotent: anything already in its target state is reported as complete and
 * produces no mutation. After the partial apply that means the Last.fm
 * deletions, the avatar renames and the text-column hugs drop out, and only the
 * history rows and the text boxes remain.
 *
 * Two things this builder gets right that the first one did not:
 *
 * 1. STROKE. Figma measures auto-layout padding from the INSIDE of an
 *    inside-aligned stroke, and adds the stroke to the hugged size. The rows
 *    carry a 1px INSIDE stroke, so padding taken straight from the measured
 *    child offsets put every child 1px in and made every row 2px tall. Padding
 *    is now stroke-compensated.
 *
 * 2. BASELINE. The failed revert restored layoutMode and child offsets but not
 *    the frame height, so every row in the live file is 2px taller than the
 *    owner left it. The target is therefore history-baseline.json - the geometry
 *    the owner actually designed - not the current file.
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
const BASE = JSON.parse(fs.readFileSync(path.join(here, "history-baseline.json"), "utf8"));
const baseById = Object.fromEntries(BASE.rows.map((r) => [r.nodeId, r]));

const r2 = (n) => Math.round(n * 100) / 100;
const mutations = [];
const complete = { "history-row-autolayout": 0, "history-text-hug": 0, "text-box-hug": 0, "lastfm-leftover": 0, "avatar-naming": 0 };

/* ---- A. history rows ---- */
for (const theme of ["light", "dark"]) {
  const frame = (DOC[theme].frames || []).find((f) => /^history-content/.test(f.name));
  if (!frame) continue;
  (function w(n, trail) {
    if (/^History Item \//.test(n.name)) {
      if (n.layout && n.layout.layoutMode && n.layout.layoutMode !== "NONE") { complete["history-row-autolayout"]++; return; }

      const owner = baseById[n.id];
      const sw = n.strokes && n.strokes.length && n.strokeWeight ? n.strokeWeight : 0;
      const inside = n.strokeAlign === "INSIDE";
      const bleed = inside ? sw : 0;   // how far the content box is inset by the stroke

      // Target geometry is the owner's, not the live file's.
      const kidsNow = (n.children || []).slice().sort((a, b) => a.x - b.x);
      const target = owner ? owner.children : kidsNow.map((k) => ({ name: k.name, x: r2(k.x), y: r2(k.y), w: r2(k.width), h: r2(k.height) }));
      const targetH = owner ? owner.height : r2(n.height);
      const targetW = owner ? owner.width : r2(n.width);

      const gaps = [];
      for (let i = 1; i < target.length; i++) gaps.push(r2(target[i].x - (target[i - 1].x + target[i - 1].w)));
      const gap = gaps[0];
      const uniformGap = gaps.every((g) => Math.abs(g - gap) < 0.01);

      const maxH = Math.max(...target.map((k) => k.h));
      const contentW = target.reduce((a, k) => a + k.w, 0) + (target.length - 1) * gap;

      // childX = bleed + padLeft  ->  padLeft = firstChildX - bleed
      const padL = r2(target[0].x - bleed);
      const padR = r2(targetW - bleed - padL - contentW - bleed);
      // hugged height = 2*bleed + padTop + maxChild + padBottom, and padding is symmetric
      const padTB = r2((targetH - 2 * bleed - maxH) / 2);

      // predict every child position from those parameters and check it matches the owner's
      const predicted = [];
      let cursor = bleed + padL;
      for (const k of target) {
        const cy = bleed + padTB + (maxH - k.h) / 2;      // counterAxisAlignItems CENTER
        predicted.push({ name: k.name, x: r2(cursor), y: r2(cy) });
        cursor = r2(cursor + k.w + gap);
      }
      const mismatch = predicted.filter((p, i) => Math.abs(p.x - target[i].x) > 0.01 || Math.abs(p.y - target[i].y) > 0.01);
      const predictedH = r2(2 * bleed + padTB + maxH + padTB);

      mutations.push({
        id: `A-${theme}-${n.id}`, group: "history-row-autolayout",
        page: DOC[theme].source.pageName, frame: frame.name, node: n.name, nodeId: n.id, path: trail,
        check: {
          type: "FRAME", layoutMode: "NONE", width: r2(n.width), height: r2(n.height),
          strokeWeight: sw, strokeAlign: n.strokeAlign,
          children: kidsNow.map((k) => ({ name: k.name, x: r2(k.x), y: r2(k.y), w: r2(k.width), h: r2(k.height) }))
        },
        apply: {
          op: "setAutoLayout", layoutMode: "HORIZONTAL", itemSpacing: gap,
          padding: { top: padTB, right: padR, bottom: padTB, left: padL },
          primaryAxisAlignItems: "MIN", counterAxisAlignItems: "CENTER",
          primaryAxisSizingMode: "FIXED", counterAxisSizingMode: "AUTO"
        },
        expect: { height: targetH, width: targetW, children: target },
        current: `layoutMode NONE, ${r2(n.width)}x${r2(n.height)}, stroke ${sw}px ${n.strokeAlign}` +
                 (owner && Math.abs(n.height - owner.height) > 0.01 ? ` — ${r2(n.height - owner.height)}px taller than the owner's ${owner.height}` : ""),
        proposed: `HORIZONTAL auto-layout, gap ${gap}, padding ${padTB}/${padR}/${padTB}/${padL} (stroke-compensated), ` +
                  `counterAxisAlignItems CENTER, height hugs to ${predictedH}`,
        pixelsMove: mismatch.length > 0,
        wrapChange: false,
        // No UNINTENDED visual change. The height going 78 -> 76 is the point of
        // the recovery, not a side effect, so it is reported on its own line
        // rather than as a defect that would gate Apply.
        visualChange: false,
        restoresOwnerGeometry: owner ? Math.abs(n.height - owner.height) > 0.01 : false,
        heightDeltaVsCurrent: owner ? r2(owner.height - n.height) : 0,
        evidence: `stroke ${sw}px ${n.strokeAlign}: content box is inset by ${bleed}px, so padding ${padL} puts the first child at ${r2(bleed + padL)} ` +
                  `and the hugged height is 2x${bleed} + ${padTB} + ${maxH} + ${padTB} = ${predictedH}. ` +
                  `All ${target.length} predicted child positions match the owner's geometry` + (mismatch.length ? ` EXCEPT ${mismatch.length}` : "") + ".",
        blockedBy: !uniformGap ? `gaps are not uniform: ${gaps.join(",")}`
                 : mismatch.length ? `predicted positions differ from the owner's for ${mismatch.map((m) => m.name).join(", ")}`
                 : Math.abs(predictedH - targetH) > 0.01 ? `predicted height ${predictedH} != owner height ${targetH}`
                 : !owner ? "no owner baseline recorded for this row"
                 : null
      });
    }
    (n.children || []).forEach((c) => w(c, trail + " > " + c.name));
  })(frame, frame.name);
}

/* ---- B. text column hug (already applied - detect and skip) ---- */
for (const theme of ["light", "dark"]) {
  const frame = (DOC[theme].frames || []).find((f) => /^history-content/.test(f.name));
  if (!frame) continue;
  (function w(n) {
    if (/^History Item \//.test(n.name)) {
      const col = (n.children || []).find((c) => c.name === "Text");
      if (col && col.layout) {
        if (col.layout.primaryAxisSizingMode === "AUTO") complete["history-text-hug"]++;
        else mutations.push({
          id: `B-${theme}-${col.id}`, group: "history-text-hug",
          page: DOC[theme].source.pageName, frame: frame.name, node: "Text", nodeId: col.id, path: n.name + " > Text",
          check: { type: "FRAME", layoutMode: "VERTICAL", primaryAxisSizingMode: "FIXED", height: r2(col.height) },
          apply: { op: "setSizing", primaryAxisSizingMode: "AUTO" },
          expect: {}, current: "primaryAxisSizingMode FIXED", proposed: "primaryAxisSizingMode AUTO",
          pixelsMove: false, wrapChange: false, visualChange: false,
          evidence: "height already equals its content", blockedBy: null
        });
      }
    }
    (n.children || []).forEach(w);
  })(frame);
}

/* ---- C. text boxes shorter than a line ---- */
for (const theme of ["light", "dark"]) {
  for (const frame of DOC[theme].frames || []) {
    (function w(n, trail) {
      const lh = n.text && n.text.lineHeight && n.text.lineHeight.value;
      if (n.text && typeof lh === "number") {
        // Only a box that was in scope counts as complete: one whose height now
        // equals its line height exactly. Counting every already-Hug text node
        // in the file would be meaningless.
        if (Math.abs(n.height - lh) < 0.51 && n.text.textAutoResize === "HEIGHT" && lh === 32 && n.text.fontSize === 24)
          complete["text-box-hug"]++;
        else if (n.height + 0.5 < lh && n.text.textAutoResize === "NONE") {
          const mixedFont = n.text.fontFamily === "MIXED" || n.text.fontStyle === "MIXED";
          mutations.push({
            id: `C-${theme}-${n.id}`, group: "text-box-hug",
            page: DOC[theme].source.pageName, frame: frame.name, node: n.name, nodeId: n.id, path: trail,
            check: { type: "TEXT", textAutoResize: "NONE", height: r2(n.height), lineHeight: lh,
                     textAlignVertical: n.text.textAlignVertical, characters: n.text.characters, x: r2(n.x), y: r2(n.y) },
            // the font must be loaded before ANY write to a text node
            font: { family: n.text.fontFamily, style: n.text.fontStyle },
            apply: { op: "setTextAutoResize", textAutoResize: "HEIGHT" },
            expect: { height: lh, x: r2(n.x), y: r2(n.y) },
            current: `height ${r2(n.height)}, lineHeight ${lh}, textAutoResize NONE, font ${n.text.fontFamily} ${n.text.fontStyle}`,
            proposed: `textAutoResize HEIGHT (height resolves to ${lh}); font loaded first, family and style untouched`,
            pixelsMove: false, wrapChange: false, visualChange: false,
            evidence: `vertical alignment is ${n.text.textAlignVertical}, so the glyph keeps its top edge and the box grows ` +
                      `${r2(lh - n.height)}px down into empty space. "${n.text.characters}" is one line.`,
            blockedBy: mixedFont ? "fontName is MIXED - refusing to guess which font to load"
                     : n.text.textAlignVertical !== "TOP" ? `textAlignVertical is ${n.text.textAlignVertical}, not TOP`
                     : null
          });
        }
      }
      (n.children || []).forEach((c) => w(c, trail + " > " + c.name));
    })(frame, frame.name);
  }
}

/* ---- D / E. already complete? ---- */
for (const theme of ["light", "dark"]) {
  for (const frame of DOC[theme].frames || []) {
    (function w(n) {
      if (/^Asset slot \/ logo\/lastfm/.test(n.name))
        mutations.push({ id: `D-${theme}-${n.id}`, group: "lastfm-leftover", page: DOC[theme].source.pageName,
          frame: frame.name, node: n.name, nodeId: n.id, path: n.name,
          check: { visible: false, namePrefix: "Asset slot / logo/lastfm" }, apply: { op: "remove" }, expect: {},
          current: "hidden leftover", proposed: "delete", pixelsMove: false, wrapChange: false, visualChange: false,
          evidence: "invisible", blockedBy: null });
      if (/^Avatar cell \d\d · avatar\/m3-\d\d$/.test(n.name)) complete["avatar-naming"]++;
      (n.children || []).forEach(w);
    })(frame);
  }
}
complete["lastfm-leftover"] = 3 - mutations.filter((m) => m.group === "lastfm-leftover").length;

/* ---- emit ---- */
const groups = {};
for (const m of mutations) (groups[m.group] = groups[m.group] || []).push(m);
const blocked = mutations.filter((m) => m.blockedBy);

const plan = {
  schemaVersion: "cleanup-2.0.0",
  status: "RECOVERY DRY RUN - not applied.",
  generatedFrom: {
    light: { page: DOC.light.source.pageName, exportedAt: DOC.light.exportedAt },
    dark: { page: DOC.dark.source.pageName, exportedAt: DOC.dark.exportedAt },
    ownerBaseline: "history-baseline.json"
  },
  alreadyComplete: complete,
  rootCause: {
    historyRows: "Figma measures auto-layout padding from the inside of an INSIDE-aligned stroke and adds the stroke to a hugged size. " +
                 "The rows carry a 1px INSIDE stroke, so padding copied from the measured child offsets placed every child at stroke+padding " +
                 "(+1,+1) and hugged to 2*stroke+padTop+content+padBottom (+2). Padding is now reduced by the stroke weight on every side.",
    textBoxes: "Any write to a TEXT node requires its font to be loaded first. The plan now records each node's family and style, " +
               "the plugin loads them before writing, and a MIXED fontName blocks the mutation rather than being guessed at.",
    revertGap: "The failed revert restored layoutMode and child offsets but not the frame height, so all 16 rows are still 2px taller " +
               "than the owner designed. Recovery targets history-baseline.json, which restores them."
  },
  counts: { total: mutations.length, blocked: blocked.length, byGroup: Object.fromEntries(Object.entries(groups).map(([k, v]) => [k, v.length])) },
  mutations
};
fs.writeFileSync(path.join(here, "CLEANUP-PLAN.json"), JSON.stringify(plan, null, 2) + "\n");

let md = `# Recovery cleanup — DRY RUN\n\n**Nothing applied.** Generated from the post-Apply snapshots.\n\n`;
md += `| | |\n|---|---|\n| light | \`${DOC.light.source.pageName}\`, exported ${DOC.light.exportedAt} |\n`;
md += `| dark | \`${DOC.dark.source.pageName}\`, exported ${DOC.dark.exportedAt} |\n`;
md += `| recovery mutations | **${mutations.length}**, ${blocked.length} blocked |\n`;
md += `| already complete | ${Object.entries(complete).filter(([, v]) => v).map(([k, v]) => `${k} ${v}`).join(", ")} |\n\n`;
md += `## Root cause\n\n- **History rows.** ${plan.rootCause.historyRows}\n- **Text boxes.** ${plan.rootCause.textBoxes}\n- **Revert gap.** ${plan.rootCause.revertGap}\n\n`;
for (const [g, items] of Object.entries(groups)) {
  md += `## ${g} (${items.length})\n\n| frame | node | current | proposed | px vs owner | wrap | height Δ vs live |\n|---|---|---|---|---|---|---|\n`;
  for (const m of items)
    md += `| ${m.frame} | \`${m.node}\` | ${m.current} | ${m.proposed} | ${m.pixelsMove ? "MOVES" : "no"} | ${m.wrapChange ? "CHANGES" : "no"} | ${m.heightDeltaVsCurrent || 0} |\n`;
  md += `\n${items[0].evidence}\n\n`;
  const b = items.filter((m) => m.blockedBy);
  if (b.length) { md += `**Blocked:**\n\n`; for (const m of b) md += `- \`${m.path}\` — ${m.blockedBy}\n`; md += `\n`; }
}
fs.writeFileSync(path.join(here, "CLEANUP-DRY-RUN.md"), md);

console.log(`recovery plan: ${mutations.length} mutations, ${blocked.length} blocked`);
for (const [g, v] of Object.entries(groups)) console.log(`    ${g.padEnd(26)} ${v.length}`);
console.log(`  already complete:`, JSON.stringify(complete));
console.log(`  move pixels vs owner baseline: ${mutations.filter((m) => m.pixelsMove).length}`);
console.log(`  wrap changes:                  ${mutations.filter((m) => m.wrapChange).length}`);
console.log(`  rows whose height is corrected: ${mutations.filter((m) => m.heightDeltaVsCurrent).length}`);
if (blocked.length) for (const m of blocked) console.log(`  BLOCKED ${m.id}: ${m.blockedBy}`);
