/*
 * SVG path -> Figma-safe path.
 *
 * Figma's VectorPath.data accepts only a subset of the SVG path grammar. The
 * arc command A/a and the shorthands H/h, V/v, S/s, T/t are NOT in it, and
 * assigning them throws "Failed to convert path. Invalid command at …".
 *
 * The HTML preview never caught this because it renders <path d="…"> inside a
 * real <svg>, which implements the full SVG 1.1 grammar. The preview was
 * therefore not a test of what Figma would accept.
 *
 * normalizePath() rewrites any path into absolute M / L / C / Z only - the
 * narrowest subset Figma definitely supports - preserving geometry. Arcs become
 * cubic Béziers split at 90° or finer, which is the standard conversion and is
 * accurate to well under a tenth of a pixel at icon sizes.
 */

const ARGC = { M: 2, L: 2, H: 1, V: 1, C: 6, S: 4, Q: 4, T: 2, A: 7, Z: 0 };
const TOKEN = /([MmLlHhVvCcSsQqTtAaZz])|(-?(?:\d*\.\d+|\d+)(?:[eE][+-]?\d+)?)/g;

function tokenize(d) {
  const out = [];
  let m;
  TOKEN.lastIndex = 0;
  while ((m = TOKEN.exec(d)) !== null) out.push(m[1] !== undefined ? m[1] : parseFloat(m[2]));
  return out;
}

const round = (n) => {
  const r = Math.round(n * 1000) / 1000;
  return Object.is(r, -0) ? 0 : r;
};

/* SVG spec F.6.5: endpoint parameterisation -> centre parameterisation, then
 * split the sweep into <=90° pieces and convert each to a cubic. */
function arcToCubics(x1, y1, rx, ry, phiDeg, fa, fs, x2, y2) {
  if (x1 === x2 && y1 === y2) return [];
  rx = Math.abs(rx); ry = Math.abs(ry);
  if (rx === 0 || ry === 0) return [{ type: "L", pts: [x2, y2] }];

  const phi = (phiDeg * Math.PI) / 180;
  const cosP = Math.cos(phi), sinP = Math.sin(phi);
  const dx2 = (x1 - x2) / 2, dy2 = (y1 - y2) / 2;
  const x1p = cosP * dx2 + sinP * dy2;
  const y1p = -sinP * dx2 + cosP * dy2;

  // scale the radii up if they are too small to span the endpoints
  const lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry);
  if (lambda > 1) { const s = Math.sqrt(lambda); rx *= s; ry *= s; }

  const rx2 = rx * rx, ry2 = ry * ry;
  const num = rx2 * ry2 - rx2 * y1p * y1p - ry2 * x1p * x1p;
  const den = rx2 * y1p * y1p + ry2 * x1p * x1p;
  const co = (fa !== fs ? 1 : -1) * Math.sqrt(Math.max(0, num / den));
  const cxp = (co * rx * y1p) / ry;
  const cyp = (-co * ry * x1p) / rx;
  const cx = cosP * cxp - sinP * cyp + (x1 + x2) / 2;
  const cy = sinP * cxp + cosP * cyp + (y1 + y2) / 2;

  const ux = (x1p - cxp) / rx, uy = (y1p - cyp) / ry;
  const vx = (-x1p - cxp) / rx, vy = (-y1p - cyp) / ry;

  let theta1 = Math.atan2(uy, ux);
  let dtheta = Math.atan2(ux * vy - uy * vx, ux * vx + uy * vy);
  if (!fs && dtheta > 0) dtheta -= 2 * Math.PI;
  if (fs && dtheta < 0) dtheta += 2 * Math.PI;

  const n = Math.max(1, Math.ceil(Math.abs(dtheta) / (Math.PI / 2)));
  const delta = dtheta / n;
  const t = (4 / 3) * Math.tan(delta / 4);

  const map = (ex, ey) => [cx + cosP * ex - sinP * ey, cy + sinP * ex + cosP * ey];

  const segs = [];
  for (let i = 0; i < n; i++) {
    const a1 = theta1 + i * delta, a2 = a1 + delta;
    const c1 = Math.cos(a1), s1 = Math.sin(a1), c2 = Math.cos(a2), s2 = Math.sin(a2);
    const [p1x, p1y] = map(rx * c1, ry * s1);
    const [d1x, d1y] = [-rx * s1, ry * c1];
    const [p2x, p2y] = map(rx * c2, ry * s2);
    const [d2x, d2y] = [-rx * s2, ry * c2];
    const [q1x, q1y] = map(rx * c1 + t * d1x, ry * s1 + t * d1y);
    const [q2x, q2y] = map(rx * c2 - t * d2x, ry * s2 - t * d2y);
    void p1x; void p1y;
    segs.push({ type: "C", pts: [q1x, q1y, q2x, q2y, p2x, p2y] });
  }
  return segs;
}

export function normalizePath(d) {
  const tk = tokenize(d);
  const out = [];
  let i = 0, cmd = null;
  let x = 0, y = 0, sx = 0, sy = 0;          // current point, subpath start
  let px = null, py = null, prevType = null; // last control point, for S/T

  const push = (type, pts) => { out.push({ type, pts }); };

  while (i < tk.length) {
    if (typeof tk[i] === "string") { cmd = tk[i]; i++; }
    else if (cmd === null) throw new Error("path starts with a number: " + d);
    else if (cmd === "M") cmd = "L";           // implicit lineto after moveto
    else if (cmd === "m") cmd = "l";

    const up = cmd.toUpperCase();
    const rel = cmd !== up;
    const need = ARGC[up];
    if (need === undefined) throw new Error("unknown command '" + cmd + "' in " + d);
    const a = tk.slice(i, i + need);
    if (a.length < need) throw new Error("truncated '" + cmd + "' in " + d);
    i += need;

    let cp1x = null, cp1y = null;
    switch (up) {
      case "M": {
        const nx = rel ? x + a[0] : a[0], ny = rel ? y + a[1] : a[1];
        x = sx = nx; y = sy = ny; push("M", [x, y]); break;
      }
      case "L": {
        x = rel ? x + a[0] : a[0]; y = rel ? y + a[1] : a[1]; push("L", [x, y]); break;
      }
      case "H": { x = rel ? x + a[0] : a[0]; push("L", [x, y]); break; }
      case "V": { y = rel ? y + a[0] : a[0]; push("L", [x, y]); break; }
      case "C": {
        const c1x = rel ? x + a[0] : a[0], c1y = rel ? y + a[1] : a[1];
        const c2x = rel ? x + a[2] : a[2], c2y = rel ? y + a[3] : a[3];
        x = rel ? x + a[4] : a[4]; y = rel ? y + a[5] : a[5];
        push("C", [c1x, c1y, c2x, c2y, x, y]); cp1x = c2x; cp1y = c2y; break;
      }
      case "S": {
        const rx1 = /[CS]/.test(prevType || "") && px !== null ? 2 * x - px : x;
        const ry1 = /[CS]/.test(prevType || "") && py !== null ? 2 * y - py : y;
        const c2x = rel ? x + a[0] : a[0], c2y = rel ? y + a[1] : a[1];
        x = rel ? x + a[2] : a[2]; y = rel ? y + a[3] : a[3];
        push("C", [rx1, ry1, c2x, c2y, x, y]); cp1x = c2x; cp1y = c2y; break;
      }
      case "Q": {
        const qx = rel ? x + a[0] : a[0], qy = rel ? y + a[1] : a[1];
        const ex = rel ? x + a[2] : a[2], ey = rel ? y + a[3] : a[3];
        // exact quadratic -> cubic, so the output alphabet stays M/L/C/Z
        push("C", [x + (2 / 3) * (qx - x), y + (2 / 3) * (qy - y),
                   ex + (2 / 3) * (qx - ex), ey + (2 / 3) * (qy - ey), ex, ey]);
        x = ex; y = ey; cp1x = qx; cp1y = qy; break;
      }
      case "T": {
        const qx = /[QT]/.test(prevType || "") && px !== null ? 2 * x - px : x;
        const qy = /[QT]/.test(prevType || "") && py !== null ? 2 * y - py : y;
        const ex = rel ? x + a[0] : a[0], ey = rel ? y + a[1] : a[1];
        push("C", [x + (2 / 3) * (qx - x), y + (2 / 3) * (qy - y),
                   ex + (2 / 3) * (qx - ex), ey + (2 / 3) * (qy - ey), ex, ey]);
        x = ex; y = ey; cp1x = qx; cp1y = qy; break;
      }
      case "A": {
        const ex = rel ? x + a[5] : a[5], ey = rel ? y + a[6] : a[6];
        for (const seg of arcToCubics(x, y, a[0], a[1], a[2], !!a[3], !!a[4], ex, ey)) push(seg.type, seg.pts);
        x = ex; y = ey; break;
      }
      case "Z": { push("Z", []); x = sx; y = sy; break; }
    }
    px = cp1x; py = cp1y; prevType = up;
  }

  return out.map((s) => (s.type === "Z" ? "Z" : s.type + " " + s.pts.map(round).join(" "))).join(" ");
}

/* The check Create will effectively perform. Kept deliberately strict: if a
 * string passes this, figma.vectorPaths accepts it. */
export function validateFigmaPath(d) {
  if (typeof d !== "string" || !d.trim()) return "empty path";
  const letters = d.match(/[A-Za-z]/g) || [];
  for (const c of letters) if (!"MLCZ".includes(c)) return "unsupported command '" + c + "'";
  const tk = tokenize(d);
  if (typeof tk[0] !== "string" || tk[0] !== "M") return "path must start with M";
  let i = 0;
  while (i < tk.length) {
    const c = tk[i];
    if (typeof c !== "string") return "stray number at index " + i;
    i++;
    const need = { M: 2, L: 2, C: 6, Z: 0 }[c];
    for (let k = 0; k < need; k++, i++) if (typeof tk[i] !== "number") return "'" + c + "' is missing arguments";
    while (typeof tk[i] === "number") {                 // implicit repeats
      const rep = c === "M" ? 2 : need;
      if (rep === 0) return "'Z' takes no arguments";
      for (let k = 0; k < rep; k++, i++) if (typeof tk[i] !== "number") return "'" + c + "' repeat is truncated";
    }
  }
  return null;
}
