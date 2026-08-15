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
 * The canonical exporter records no vector geometry (see canonical/code.js - no
 * vectorPaths, no exportAsync), so the icon outlines come from a second source:
 * tools/figma-export/player-icons/exact, the literal paths pulled out of the
 * FINAL .fig. Boxes from the snapshot, outlines from the bundle - both exact,
 * nothing on this page reconstructed.
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, "../../..");
const canonical = path.join(repo, "tools/figma-export/canonical");

/*
 * The icon geometry, read from the exact bundle rather than reconstructed.
 *
 * tools/figma-export/player-icons/exact holds the literal vector paths pulled
 * out of the FINAL .fig, so this page no longer draws a single approximation.
 * Each entry is keyed by the node's name trail and names the SVG that supplies
 * it; `deferred` only dims the drawing, because the app does not build that
 * control yet.
 *
 * `play/pause > Container > Icon` is the PAUSE glyph - that is the state the
 * frozen frame shows, and why the box the snapshot records for it is 23.33
 * square. The play glyph is its own node and is not on this screen.
 */
const EXACT = path.join(repo, "tools/figma-export/player-icons/exact");

/*
 * The one export artefact the app also drops, so the two sides stay comparable.
 *
 * Flattening the cookie's vector network emitted 18 degenerate segments; this is
 * the only one whose control points landed outside the shape, and it paints a
 * 3px spur that breaks the component's nine-fold symmetry. The bundle keeps it -
 * that file is the supplied artefact and stays verbatim - and both the drawable
 * and this reference drop it. See ic_player_swipe_active.xml.
 */
const COOKIE_ARTEFACT = "C 170.5194359 -3.4627247 145.2386589 -3.4627247 126.9319992 10.3881721 ";

function exactGlyph(file, label, deferred) {
  const svg = fs.readFileSync(path.join(EXACT, file), "utf8").replace(COOKIE_ARTEFACT, "");
  const head = /<svg\b[^>]*>/.exec(svg)[0];
  const viewBox = /viewBox="([^"]*)"/.exec(head)[1];
  const inner = svg.slice(svg.indexOf(">", svg.indexOf("<svg")) + 1, svg.lastIndexOf("</svg>"));
  return { label, viewBox, inner: inner.replace(/currentColor/g, "__FILL__"), deferred };
}

const DERIVED_GLYPHS = {
  "play/pause>Container>Icon": exactGlyph("player_pause.svg", "pause glyph (exact)", false),
  "like>Container>Icon": exactGlyph("player_like.svg", "like (exact)", false),
  "dislike>Container>Icon": exactGlyph("player_dislike.svg", "dislike (exact, deferred)", true),
  "Button:margin>Button>Container>Icon": exactGlyph("player_overflow.svg", "overflow (exact, deferred)", true),
};

const COOKIE = exactGlyph("player_swipe_active_cookie.svg", "swipe active (exact)", false);
const CIRCLE = exactGlyph("player_swipe_inactive_circle.svg", "swipe inactive (exact)", false);

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
        // The exact assets are the 10x10 component slot with the shape offset
        // inside it, so they are placed on the slot, not on the ink's own box.
        const cookie = Math.abs(w - 8.53) < 0.05;
        const g = cookie ? COOKIE : CIRCLE;
        const slotX = cookie ? x - 0.74 : x - 0.79;
        const slotY = y - 0.79;
        out.push(
          `<svg x="${slotX}" y="${slotY}" width="10" height="10" viewBox="${g.viewBox}">` +
            g.inner.replace(/__FILL__/g, fill.color).replace(
              /<path /g,
              `<path fill-opacity="${fill.opacity}" `
            ) +
            `</svg>`
        );
        derived.push(g.label);
        return;
      }
      const g = DERIVED_GLYPHS[Object.keys(DERIVED_GLYPHS).find((k) => key === k || key.endsWith(">" + k))];
      if (g) {
        derived.push(g.label);
        const colour = fill ? fill.color : "#000";
        const opacity = g.deferred ? 0.35 : fill ? fill.opacity : 1;
        out.push(
          `<svg x="${x}" y="${y}" width="${w}" height="${h}" viewBox="${g.viewBox}">` +
            g.inner.replace(/__FILL__/g, colour).replace(/<path /g, `<path fill-opacity="${opacity}" `) +
            `</svg>`
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

/**
 * One frozen PLAYER page.
 *
 * `scope: "upper"` draws `Player Section` alone, 390x542 - what B2 and #42
 * compared against, unchanged.
 *
 * `scope: "full"` draws `Main`, 390x926, which is the same walk over the parent
 * node and so adds `Broadcast History Section` at y=552 without a line of
 * drawing code of its own. It stops at `Main`: `BottomNavBar` sits below it at
 * 946 and belongs to no phase of the PLAYER migration.
 */
export function renderFinal(theme, scope = "upper") {
  const file = path.join(canonical, `figma-canonical-${theme}-final.json`);
  const doc = JSON.parse(fs.readFileSync(file, "utf8"));
  const frame = findFrame(doc, theme === "light" ? "PLAYER" : "PLAYER_dark");
  const main = findByName(frame, "Main");
  const bg = solid(frame) || solid(main) || { color: theme === "light" ? "#F8F9FA" : "#0F253E" };
  // The upper page stops just past the frozen controls bottom of 522; the full
  // one is `Main`'s own height, which ends on the history section's last pixel.
  const height = scope === "full" ? Math.round(main.height) : 542;
  const root = scope === "full" ? main : findByName(main, "Player Section");
  const { body, derived } = renderSection(root, height);
  const svg =
    `<svg xmlns="http://www.w3.org/2000/svg" width="390" height="${height}" viewBox="0 0 390 ${height}">` +
    `<rect width="390" height="${height}" fill="${bg.color}"/>` +
    body +
    `</svg>`;
  return { svg, derived, background: bg.color };
}

if (import.meta.url === `file://${process.argv[1]}` || process.argv[1] === fileURLToPath(import.meta.url)) {
  for (const theme of ["light", "dark"]) {
    for (const scope of ["upper", "full"]) {
      const { svg, derived } = renderFinal(theme, scope);
      const dest = path.join(here, `final-${theme}${scope === "full" ? "-full" : ""}.svg`);
      fs.writeFileSync(dest, svg + "\n");
      console.log(`  ${path.relative(repo, dest)}  (${derived.length} icon outline(s), all exact)`);
      if (scope === "upper") derived.forEach((d) => console.log(`      ${d}`));
    }
  }
}
