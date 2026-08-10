/*
 * Minimal PNG pixel comparison, no dependencies.
 *
 *   node tools/qa/tv/pixel-diff.mjs before/03-x.png after/03-x.png
 *   node tools/qa/tv/pixel-diff.mjs            # all matching pairs
 *
 * Screenshots that differ byte-wise are often identical to the eye - a focus
 * animation a frame from settling, or encoder noise. "Looks the same" is not
 * evidence, so this reports how many pixels differ and by how much.
 */
import fs from "node:fs";
import path from "node:path";
import zlib from "node:zlib";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));

function decodePng(file) {
  const buf = fs.readFileSync(file);
  if (buf.readUInt32BE(0) !== 0x89504e47) throw new Error("not a png");
  let pos = 8, w = 0, h = 0, depth = 0, color = 0;
  const idat = [];
  while (pos < buf.length) {
    const len = buf.readUInt32BE(pos);
    const type = buf.toString("ascii", pos + 4, pos + 8);
    const data = buf.subarray(pos + 8, pos + 8 + len);
    if (type === "IHDR") {
      w = data.readUInt32BE(0); h = data.readUInt32BE(4);
      depth = data[8]; color = data[9];
      if (depth !== 8) throw new Error(`unsupported bit depth ${depth}`);
      if (color !== 2 && color !== 6) throw new Error(`unsupported colour type ${color}`);
    } else if (type === "IDAT") idat.push(data);
    else if (type === "IEND") break;
    pos += 12 + len;
  }
  const bpp = color === 6 ? 4 : 3;
  const raw = zlib.inflateSync(Buffer.concat(idat));
  const stride = w * bpp;
  const out = Buffer.alloc(h * stride);
  let rp = 0;
  for (let y = 0; y < h; y++) {
    const filter = raw[rp++];
    const line = raw.subarray(rp, rp + stride); rp += stride;
    const prev = y ? out.subarray((y - 1) * stride, y * stride) : Buffer.alloc(stride);
    const cur = out.subarray(y * stride, (y + 1) * stride);
    for (let x = 0; x < stride; x++) {
      const a = x >= bpp ? cur[x - bpp] : 0, b = prev[x], c = x >= bpp ? prev[x - bpp] : 0;
      let v = line[x];
      if (filter === 1) v += a;
      else if (filter === 2) v += b;
      else if (filter === 3) v += (a + b) >> 1;
      else if (filter === 4) {
        const p = a + b - c, pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
        v += pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
      }
      cur[x] = v & 0xff;
    }
  }
  return { w, h, bpp, data: out };
}

function compare(fa, fb) {
  const A = decodePng(fa), B = decodePng(fb);
  if (A.w !== B.w || A.h !== B.h) return { error: `size ${A.w}x${A.h} vs ${B.w}x${B.h}` };
  let differing = 0, maxDelta = 0, sum = 0;
  const px = A.w * A.h;
  for (let i = 0; i < px; i++) {
    let d = 0;
    for (let c = 0; c < 3; c++) {
      const delta = Math.abs(A.data[i * A.bpp + c] - B.data[i * B.bpp + c]);
      if (delta > d) d = delta;
    }
    if (d > 0) { differing++; sum += d; if (d > maxDelta) maxDelta = d; }
  }
  return { w: A.w, h: A.h, px, differing, pct: (differing / px) * 100, maxDelta, meanDelta: differing ? sum / differing : 0 };
}

const args = process.argv.slice(2);
const pairs = args.length === 2
  ? [[args[0], args[1], path.basename(args[0])]]
  : fs.readdirSync(path.join(here, "before")).filter((f) => f.endsWith(".png"))
      .map((f) => [path.join(here, "before", f), path.join(here, "after", f), f])
      .filter(([a, b]) => fs.existsSync(a) && fs.existsSync(b));

console.log("file                                    differing px      %      max Δ   mean Δ");
for (const [a, b, name] of pairs) {
  try {
    const r = compare(a, b);
    if (r.error) { console.log(`  ${name.padEnd(38)} ${r.error}`); continue; }
    const verdict = r.differing === 0 ? "identical" : r.pct < 0.5 && r.maxDelta <= 32 ? "negligible" : "REVIEW";
    console.log(`  ${name.padEnd(38)} ${String(r.differing).padStart(9)} ${r.pct.toFixed(3).padStart(8)}% ${String(r.maxDelta).padStart(6)} ${r.meanDelta.toFixed(1).padStart(8)}   ${verdict}`);
  } catch (e) {
    console.log(`  ${name.padEnd(38)} error: ${e.message}`);
  }
}
