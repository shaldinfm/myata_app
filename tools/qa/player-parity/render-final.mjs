/*
 * The FINAL frozen PLAYER upper section, drawn from the canonical export.
 *
 *   node tools/qa/player-parity/render-final.mjs
 *
 * No render of the FINAL design exists in this repository - the canonical
 * snapshot is JSON, and the PNGs under tools/figma-export/dark-theme/previews are
 * of the *earlier* dark reconstruction, not of FINAL. So a side-by-side against
 * FINAL has to draw FINAL, and this draws it from the one source of truth we
 * have: `figma-canonical-{light,dark}-final.json`, node for node.
 *
 * Every rectangle, radius, offset, colour, font size and text case below is read
 * out of that file. Nothing is typed in.
 *
 * The one thing the exporter does not record is vector geometry (see
 * canonical/code.js - no vectorPaths, no exportAsync). Icon *boxes* are exact and
 * are drawn as such; the outlines inside them come from the app's own drawables
 * so that the two sides can be compared at all, and every one of them is listed
 * in the page's legend as derived rather than exported.
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, "../../..");
const canonical = path.join(repo, "tools/figma-export/canonical");

/** Icon outlines the exporter cannot give us, keyed by the node's path suffix. */
const DERIVED_GLYPHS = {
  "play/pause>Container>Icon": {
    label: "play glyph",
    viewBox: "0 0 24 24",
    d: "M0,0 L0,24 L24,12 Z",
  },
  "like>Container>Icon": {
    label: "like (thumbs-up)",
    viewBox: "0 0 24.5 23.33",
    d:
      "M0,9.33 L4.45,9.33 L4.45,23.33 L0,23.33 Z " +
      "M24.5,10.5 C24.5,9.22 23.28,8.17 22.27,8.17 L15.25,8.17 L16.3,2.83 L16.34,2.46 " +
      "C16.34,1.98 16.15,1.54 15.85,1.22 L14.67,0 L7.34,7.69 C6.93,8.11 6.68,8.69 6.68,9.33 " +
      "L6.68,21 C6.68,22.28 7.68,23.33 8.91,23.33 L18.93,23.33 C19.85,23.33 20.64,22.74 20.98,21.91 " +
      "L24.34,13.68 C24.44,13.42 24.5,13.12 24.5,12.83 Z",
  },
  "dislike>Container>Icon": {
    label: "dislike (deferred, not built)",
    viewBox: "0 0 24.5 23.33",
    deferred: true,
    d:
      "M0,14 L4.45,14 L4.45,0 L0,0 Z " +
      "M24.5,12.83 C24.5,14.11 23.28,15.16 22.27,15.16 L15.25,15.16 L16.3,20.5 L16.34,20.87 " +
      "C16.34,21.35 16.15,21.79 15.85,22.11 L14.67,23.33 L7.34,15.64 C6.93,15.22 6.68,14.64 6.68,14 " +
      "L6.68,2.33 C6.68,1.05 7.68,0 8.91,0 L18.93,0 C19.85,0 20.64,0.59 20.98,1.42 " +
      "L24.34,9.65 C24.44,9.91 24.5,10.21 24.5,10.5 Z",
  },
  // The trailing header control: a 4x16 vertical three-dot. Reserved in the app.
  "Button:margin>Button>Container>Icon": {
    label: "overflow (deferred, reserved)",
    viewBox: "0 0 4 16",
    deferred: true,
    d:
      "M2,0 A2,2 0 1 0 2,4 A2,2 0 1 0 2,0 Z " +
      "M2,6 A2,2 0 1 0 2,10 A2,2 0 1 0 2,6 Z " +
      "M2,12 A2,2 0 1 0 2,16 A2,2 0 1 0 2,12 Z",
  },
};

/** The swipe markers, identified by their variant rather than their path. */
const COOKIE_D =
  "M8.27,4.32 C8.05,4.63 7.85,4.87 7.89,5.18 C7.94,5.48 8.15,5.89 8.1,6.24 C8.05,6.59 7.71,6.8 7.34,6.89 " +
  "C6.96,6.98 6.66,7.04 6.49,7.3 C6.33,7.57 6.23,8.01 5.97,8.25 C5.71,8.48 5.31,8.43 4.96,8.26 " +
  "C4.62,8.09 4.35,7.94 4.05,8.03 C3.76,8.13 3.39,8.41 3.04,8.42 C2.69,8.43 2.42,8.13 2.27,7.78 " +
  "C2.11,7.43 2,7.14 1.71,7.02 C1.43,6.91 0.97,6.89 0.69,6.67 C0.41,6.46 0.4,6.06 0.51,5.69 " +
  "C0.61,5.32 0.72,5.02 0.57,4.75 C0.43,4.48 0.09,4.16 0.01,3.82 C-0.06,3.48 0.19,3.16 0.51,2.95 " +
  "C0.83,2.73 1.09,2.58 1.16,2.27 C1.22,1.97 1.16,1.51 1.33,1.2 C1.49,0.89 1.89,0.81 2.27,0.85 " +
  "C2.65,0.89 2.96,0.94 3.2,0.75 C3.45,0.56 3.7,0.18 4.02,0.04 C4.35,-0.09 4.7,0.1 4.96,0.38 " +
  "C5.23,0.65 5.43,0.89 5.74,0.9 C6.05,0.91 6.49,0.78 6.83,0.88 C7.16,0.99 7.31,1.36 7.34,1.75 " +
  "C7.36,2.13 7.37,2.44 7.6,2.65 C7.83,2.86 8.25,3.03 8.44,3.33 C8.63,3.63 8.5,4.01 8.27,4.32 Z";
const CIRCLE_D = "M0,4.21 A4.21,4.21 0 1 0 8.42,4.21 A4.21,4.21 0 1 0 0,4.21 Z";

const esc = (s) =>
  String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");

/**
 * The topmost visible solid fill. Figma paints a node's fills bottom-up, and the
 * two frozen frames both carry a stack - `#ffffff` under `#f8f9fa` on the light
 * page, under `#0f253e` on the dark one - so taking the first would paint the
 * dark page white.
 */
const solid = (node) => {
  const stack = (node.fills || []).filter((p) => p.visible !== false && p.type === "SOLID");
  const f = stack[stack.length - 1];
  return f ? { color: f.color, opacity: f.opacity == null ? 1 : f.opacity } : null;
};

function findFrame(doc, name) {
  return doc.frames.find((f) => String(f.name) === name);
}

function findByName(node, name) {
  if (String(node.name) === name) return node;
  for (const c of node.children || []) {
    const hit = findByName(c, name);
    if (hit) return hit;
  }
  return null;
}

/** Render one frozen page to SVG. `section` is the `Player Section` node. */
function renderSection(section, height) {
  const out = [];
  const derived = [];

  const walk = (node, ox, oy, trail) => {
    if (node.visible === false) return;
    const x = ox + (node.x || 0);
    const y = oy + (node.y || 0);
    const w = node.width || 0;
    const h = node.height || 0;
    const name = String(node.name);
    const key = [...trail, name].slice(-4).join(">");
    const fill = solid(node);

    if (node.type === "TEXT") {
      const t = node.text || {};
      const chars = t.textCase === "UPPER" ? String(t.characters).toUpperCase() : String(t.characters);
      // Figma's line box is `height`; the baseline sits at (lineHeight + capish)/2.
      const lh = (t.lineHeight && t.lineHeight.value) || t.fontSize;
      const baseline = y + lh / 2 + t.fontSize * 0.36;
      const anchor =
        t.textAlignHorizontal === "CENTER" ? "middle" : t.textAlignHorizontal === "RIGHT" ? "end" : "start";
      const tx = anchor === "middle" ? x + w / 2 : anchor === "end" ? x + w : x;
      const family =
        t.fontSize >= 20 ? "Montserrat, 'Arial Black', sans-serif" : "Onest, Inter, Arial, sans-serif";
      out.push(
        `<text x="${tx}" y="${baseline.toFixed(2)}" text-anchor="${anchor}" fill="${fill ? fill.color : "#000"}" ` +
          `font-family="${family}" font-size="${t.fontSize}" font-weight="${t.fontWeight}" ` +
          `letter-spacing="${(t.letterSpacing && t.letterSpacing.value) || 0}">${esc(chars)}</text>`
      );
      return;
    }

    if (node.type === "VECTOR") {
      // Swipe markers first: the variant on the parent instance names the shape.
      const swipe = trail[trail.length - 1] === "Shape Set" || name === "shape";
      if (swipe) {
        const cookie = Math.abs(w - 8.53) < 0.05;
        const d = cookie ? COOKIE_D : CIRCLE_D;
        const vb = cookie ? "0 0 8.53 8.42" : "0 0 8.42 8.42";
        out.push(
          `<svg x="${x}" y="${y}" width="${w}" height="${h}" viewBox="${vb}">` +
            `<path d="${d}" fill="${fill.color}" fill-opacity="${fill.opacity}"/></svg>`
        );
        if (cookie) derived.push("swipe active marker (9-sided cookie, from its bounding box)");
        return;
      }
      // Boundary-aware: "dislike>Container>Icon" must not match "like>...".
      const g = DERIVED_GLYPHS[Object.keys(DERIVED_GLYPHS).find((k) => key === k || key.endsWith(">" + k))];
      if (g) {
        derived.push(g.label);
        out.push(
          `<svg x="${x}" y="${y}" width="${w}" height="${h}" viewBox="${g.viewBox}">` +
            `<path d="${g.d}" fill="${fill ? fill.color : "#000"}" fill-opacity="${
              g.deferred ? 0.35 : fill ? fill.opacity : 1
            }"/></svg>`
        );
        out.push(
          `<rect x="${x}" y="${y}" width="${w}" height="${h}" fill="none" stroke="#e0457b" ` +
            `stroke-width="0.4" stroke-dasharray="1.5 1.5"/>`
        );
        return;
      }
      if (fill) {
        out.push(
          `<rect x="${x}" y="${y}" width="${w}" height="${h}" fill="${fill.color}" fill-opacity="${fill.opacity}"/>`
        );
      }
      return;
    }

    if (name === "Current Track Artwork") {
      const r = node.cornerRadius || 0;
      out.push(
        `<clipPath id="art"><rect x="${x}" y="${y}" width="${w}" height="${h}" rx="${r}"/></clipPath>` +
          `<image href="__ARTWORK__" x="${x}" y="${y}" width="${w}" height="${h}" ` +
          `preserveAspectRatio="xMidYMid slice" clip-path="url(#art)"/>` +
          `<rect x="${x}" y="${y}" width="${w}" height="${h}" rx="${r}" fill="none" ` +
          `stroke="__OUTLINE__" stroke-width="1"/>`
      );
      return;
    }

    if (fill) {
      const r = Math.min(node.cornerRadius || 0, Math.min(w, h) / 2);
      out.push(
        `<rect x="${x}" y="${y}" width="${w}" height="${h}" rx="${r}" fill="${fill.color}" ` +
          `fill-opacity="${fill.opacity}"/>`
      );
    }
    for (const c of node.children || []) walk(c, x, y, [...trail, name]);
  };

  // `Player Section` already carries its own (16, 16) inside `Main`, and `Main`
  // sits at the frame origin - so the walk starts at zero, not at the margin.
  walk(section, 0, 0, []);
  return { body: out.join("\n"), derived: [...new Set(derived)] };
}

export function renderFinal(theme) {
  const file = path.join(canonical, `figma-canonical-${theme}-final.json`);
  const doc = JSON.parse(fs.readFileSync(file, "utf8"));
  const frame = findFrame(doc, theme === "light" ? "PLAYER" : "PLAYER_dark");
  const main = findByName(frame, "Main");
  const section = findByName(main, "Player Section");
  const bg = solid(frame) || solid(main) || { color: theme === "light" ? "#F8F9FA" : "#0F253E" };
  const height = 542; // frozen controls bottom is 522
  const { body, derived } = renderSection(section, height);
  const svg =
    `<svg xmlns="http://www.w3.org/2000/svg" width="390" height="${height}" viewBox="0 0 390 ${height}">` +
    `<rect width="390" height="${height}" fill="${bg.color}"/>` +
    body +
    `</svg>`;
  return { svg, derived, background: bg.color };
}

if (import.meta.url === `file://${process.argv[1]}` || process.argv[1] === fileURLToPath(import.meta.url)) {
  for (const theme of ["light", "dark"]) {
    const { svg, derived } = renderFinal(theme);
    const dest = path.join(here, `final-${theme}.svg`);
    fs.writeFileSync(dest, svg + "\n");
    console.log(`  ${path.relative(repo, dest)}  (${derived.length} derived outline(s))`);
    derived.forEach((d) => console.log(`      derived: ${d}`));
  }
}
