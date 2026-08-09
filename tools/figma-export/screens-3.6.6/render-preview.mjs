/*
 * spec.json -> preview.html. Light and dark are rendered from the same node
 * tree with only the token table swapped, so the preview cannot disagree with
 * itself the way two separately drawn themes would.
 *
 *   node tools/figma-export/screens-3.6.6/render-preview.mjs
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const spec = JSON.parse(fs.readFileSync(path.join(here, "spec.json"), "utf8"));

const esc = (s) => String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
const col = (v, theme) => (v == null ? null : v.charAt(0) === "#" ? v : spec.tokens[v][theme]);
const px = (n) => (Math.round(n * 100) / 100) + "px";

function node(n, theme) {
  const st = [`left:${px(n.x)}`, `top:${px(n.y)}`, `width:${px(n.w)}`, `height:${px(n.h)}`];
  if (n.opacity != null) st.push(`opacity:${n.opacity}`);

  if (n.t === "TEXT") {
    st.push(`color:${col(n.fill, theme)}`);
    st.push(`font-weight:${{ Regular: 400, Medium: 500, Bold: 700 }[n.text.font]}`);
    st.push(`font-size:${px(n.text.size)}`, `line-height:${px(n.text.lh)}`);
    st.push(`text-align:${n.text.align.toLowerCase()}`);
    return `<div class="t" style="${st.join(";")}" title="${esc(n.n)}">${esc(n.text.s)}</div>`;
  }

  if (n.t === "ASSET") {
    // Never drawn. The preview shows a labelled slot; the plugin clones the real
    // node. An approximation here would be exactly what the review rejected.
    const a = spec.assets[n.key];
    const pending = a.status === "PENDING_OWNER";
    st.push(`border:1px dashed ${pending ? col("error", theme) : col(n.tint, theme)}`);
    st.push(`color:${pending ? col("error", theme) : col(n.tint, theme)}`);
    st.push(`border-radius:${px(n.slotRadius ?? 4)}`, "font-size:6px", "line-height:1.1", "overflow:hidden",
            "display:flex", "align-items:center", "justify-content:center", "text-align:center", "opacity:.9");
    const short = n.key.replace(/^(logo|control)\//, "");
    return `<div class="f" style="${st.join(";")}" title="${esc(n.key)} — ${esc(a.status)}">${esc(short)}</div>`;
  }

  if (n.t === "VECTOR") {
    const s = col(n.stroke, theme), f = col(n.fill, theme);
    return `<svg class="v" style="${st.join(";")}" viewBox="0 0 24 24" fill="none">` +
           `<path d="${esc(n.path)}" stroke="${s || "none"}" fill="${f || "none"}" ` +
           `stroke-width="${n.sw}" stroke-linecap="round" stroke-linejoin="round"/></svg>`;
  }

  const f = col(n.fill, theme), s = col(n.stroke, theme);
  if (f) st.push(`background:${f}`);
  if (s && n.sw) st.push(`box-shadow:inset 0 0 0 ${px(n.sw)} ${s}`);
  if (n.r) st.push(`border-radius:${px(Math.min(n.r, Math.min(n.w, n.h) / 2))}`);
  if (n.t === "ELLIPSE") st.push("border-radius:50%");
  return `<div class="f" style="${st.join(";")}" title="${esc(n.n)}">` +
         (n.ch || []).map((c) => node(c, theme)).join("") + "</div>";
}

const groups = [...new Set(spec.screens.map((s) => s.group))];

const body = groups.map((g) => {
  const list = spec.screens.filter((s) => s.group === g);
  return `<section><h2>${esc(g)}</h2>` + list.map((s) => `
    <article>
      <h3>${esc(s.title)} <code>${esc(s.id)}</code> <em>${s.w}×${s.h}</em></h3>
      ${s.notes ? `<p class="note">${esc(s.notes)}</p>` : ""}
      <div class="pair">
        ${["light", "dark"].map((theme) => `
          <figure class="${theme}">
            <figcaption>${theme}</figcaption>
            <div class="screen" style="width:${px(s.w)};height:${px(s.h)};background:${col("background", theme)}">
              ${s.nodes.map((n) => node(n, theme)).join("")}
            </div>
          </figure>`).join("")}
      </div>
    </article>`).join("") + "</section>";
}).join("");

const html = `<!doctype html><html lang="ru"><head><meta charset="utf-8">
<title>Radio Myata · 3.6.6 proposal screens</title>
<style>
  :root { color-scheme: light dark; }
  body { margin:0; padding:32px; background:#7a7f85; font:14px/1.5 system-ui,-apple-system,sans-serif; color:#fff; }
  h1 { font-size:22px; margin:0 0 4px; }
  .lead { margin:0 0 8px; opacity:.85; max-width:900px; }
  .warn { background:#fff3cd; color:#5b4300; border-radius:8px; padding:10px 14px; margin:16px 0 32px; max-width:900px; }
  h2 { font-size:18px; margin:48px 0 8px; border-bottom:1px solid rgba(255,255,255,.3); padding-bottom:6px; }
  article { margin:28px 0 40px; }
  h3 { font-size:15px; font-weight:600; margin:0 0 4px; }
  h3 code { background:rgba(0,0,0,.28); padding:1px 6px; border-radius:4px; font-size:12px; font-weight:400; }
  h3 em { opacity:.7; font-style:normal; font-size:12px; }
  .note { margin:0 0 12px; max-width:900px; opacity:.85; font-size:13px; }
  .pair { display:flex; gap:28px; flex-wrap:wrap; align-items:flex-start; }
  figure { margin:0; }
  figcaption { font-size:11px; text-transform:uppercase; letter-spacing:.08em; opacity:.75; margin-bottom:6px; }
  .screen { position:relative; overflow:hidden; border-radius:14px;
            box-shadow:0 8px 24px rgba(0,0,0,.35); font-family:"Muller","Hanken Grotesk",system-ui,sans-serif; }
  .f, .t, .v { position:absolute; box-sizing:border-box; }
  .t { white-space:pre; overflow:hidden; }
</style></head><body>
<h1>Radio Myata · 3.6.6 proposal screens</h1>
<p class="lead">${esc(spec.status)} Generated from <code>spec.json</code>; both themes come from one node tree with the token table swapped.</p>
<div class="warn"><strong>Muller is not embedded here.</strong> Unless Muller is installed locally this page falls back to a
system face, so letterforms and text widths will differ from Figma. Colour, layout and hierarchy are accurate.</div>
<div class="warn"><strong>Dashed boxes are asset slots, not artwork.</strong> Brand marks are never drawn here — the plugin clones
the real node out of the Figma file. A slot outlined in <em>red</em> has no node yet and needs one supplied by the owner:
${Object.keys(spec.assets).filter((k) => spec.assets[k].status === "PENDING_OWNER").map((k) => `<code>${esc(k)}</code>`).join(", ") || "none"}.</div>
${body}
</body></html>`;

fs.writeFileSync(path.join(here, "preview.html"), html);
console.log(`preview.html written: ${spec.screens.length} screens, ${spec.screens.length * 2} frames.`);
