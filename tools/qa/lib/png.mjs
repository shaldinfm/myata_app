/*
 * Minimal dependency-free PNG decode, shared by the QA harnesses.
 *
 * Lifted from tools/qa/tv/pixel-diff.mjs, which keeps its own copy so the TV
 * regression gate has no dependency on anything outside its directory.
 * Supports 8-bit truecolour with and without alpha, which is what adb
 * screencap produces.
 */
import fs from "node:fs";
import zlib from "node:zlib";

export function decodePng(file) {
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

export function pixel(img, x, y) {
  const i = y * img.w * img.bpp + x * img.bpp;
  return [img.data[i], img.data[i + 1], img.data[i + 2]];
}

export const dist = (a, b) =>
  Math.max(Math.abs(a[0] - b[0]), Math.abs(a[1] - b[1]), Math.abs(a[2] - b[2]));

export const hexToRgb = (h) => [
  parseInt(h.slice(1, 3), 16),
  parseInt(h.slice(3, 5), 16),
  parseInt(h.slice(5, 7), 16),
];
