/*
 * Variable-font support for the replacement audit.
 *
 * All three candidates ship from Google Fonts as a single variable file with a
 * wght axis and no static instances. Measuring them at 400/500/700 therefore
 * means reading the real per-weight advance widths out of the font, not scaling
 * the default instance and calling it an estimate.
 *
 * That is what this does: fvar for the axis and its named instances, avar for
 * the axis's non-linear remapping where present, and HVAR for the per-glyph
 * advance-width deltas at a given axis coordinate. The result is the same number
 * the rasteriser would use.
 *
 * Spec: OpenType 1.9 - fvar, avar, HVAR, and the item variation store shared
 * with the other variation tables.
 */

const F2DOT14 = (b, p) => b.readInt16BE(p) / 16384;
const FIXED = (b, p) => b.readInt32BE(p) / 65536;

export function parseFvar(b, off) {
  if (!off) return null;
  const axesOffset = b.readUInt16BE(off + 4);
  const axisCount = b.readUInt16BE(off + 8);
  const axisSize = b.readUInt16BE(off + 10);
  const instanceCount = b.readUInt16BE(off + 12);
  const instanceSize = b.readUInt16BE(off + 14);

  const axes = [];
  for (let i = 0; i < axisCount; i++) {
    const p = off + axesOffset + i * axisSize;
    axes.push({
      tag: b.toString("ascii", p, p + 4),
      min: FIXED(b, p + 4),
      def: FIXED(b, p + 8),
      max: FIXED(b, p + 12),
      nameID: b.readUInt16BE(p + 18),
    });
  }

  const instances = [];
  const instBase = off + axesOffset + axisCount * axisSize;
  for (let i = 0; i < instanceCount; i++) {
    const p = instBase + i * instanceSize;
    const coords = [];
    for (let a = 0; a < axisCount; a++) coords.push(FIXED(b, p + 4 + a * 4));
    instances.push({ subfamilyNameID: b.readUInt16BE(p), coords });
  }

  return { axes, instances };
}

export function parseAvar(b, off) {
  if (!off) return null;
  const axisCount = b.readUInt16BE(off + 6);
  const maps = [];
  let p = off + 8;
  for (let a = 0; a < axisCount; a++) {
    const pairCount = b.readUInt16BE(p); p += 2;
    const pairs = [];
    for (let i = 0; i < pairCount; i++) {
      pairs.push({ from: F2DOT14(b, p), to: F2DOT14(b, p + 2) });
      p += 4;
    }
    maps.push(pairs);
  }
  return maps;
}

// User-space value -> normalised [-1, 1], then through avar if present.
export function normalizeAxis(axis, avarMap, value) {
  let v = Math.max(axis.min, Math.min(axis.max, value));
  let n;
  if (v === axis.def) n = 0;
  else if (v < axis.def) n = (v - axis.def) / (axis.def - axis.min);
  else n = (v - axis.def) / (axis.max - axis.def);

  if (avarMap && avarMap.length >= 2) {
    for (let i = 0; i < avarMap.length - 1; i++) {
      const a = avarMap[i], c = avarMap[i + 1];
      if (n >= a.from && n <= c.from) {
        if (c.from === a.from) return a.to;
        return a.to + ((n - a.from) * (c.to - a.to)) / (c.from - a.from);
      }
    }
  }
  return n;
}

function parseRegionList(b, off) {
  const axisCount = b.readUInt16BE(off);
  const regionCount = b.readUInt16BE(off + 2);
  const regions = [];
  let p = off + 4;
  for (let r = 0; r < regionCount; r++) {
    const axes = [];
    for (let a = 0; a < axisCount; a++) {
      axes.push({ start: F2DOT14(b, p), peak: F2DOT14(b, p + 2), end: F2DOT14(b, p + 4) });
      p += 6;
    }
    regions.push(axes);
  }
  return { axisCount, regions };
}

function parseItemVariationStore(b, off) {
  const format = b.readUInt16BE(off);
  if (format !== 1) return null;
  const regionListOffset = b.readUInt32BE(off + 2);
  const dataCount = b.readUInt16BE(off + 6);
  const regionList = parseRegionList(b, off + regionListOffset);

  const dataSets = [];
  for (let i = 0; i < dataCount; i++) {
    const dOff = off + b.readUInt32BE(off + 8 + i * 4);
    const itemCount = b.readUInt16BE(dOff);
    const wordDeltaCount = b.readUInt16BE(dOff + 2);
    const regionIndexCount = b.readUInt16BE(dOff + 4);
    const longWords = (wordDeltaCount & 0x8000) !== 0;
    const wordCount = wordDeltaCount & 0x7fff;

    const regionIndexes = [];
    for (let r = 0; r < regionIndexCount; r++) regionIndexes.push(b.readUInt16BE(dOff + 6 + r * 2));

    const rowStart = dOff + 6 + regionIndexCount * 2;
    const bigSize = longWords ? 4 : 2;
    const smallSize = longWords ? 2 : 1;
    const rowSize = wordCount * bigSize + (regionIndexCount - wordCount) * smallSize;

    dataSets.push({ itemCount, wordCount, regionIndexCount, regionIndexes, rowStart, rowSize, longWords });
  }
  return { regionList, dataSets };
}

function readDelta(b, set, inner, col) {
  const rowOff = set.rowStart + inner * set.rowSize;
  const bigSize = set.longWords ? 4 : 2;
  const smallSize = set.longWords ? 2 : 1;
  if (col < set.wordCount) {
    const p = rowOff + col * bigSize;
    return set.longWords ? b.readInt32BE(p) : b.readInt16BE(p);
  }
  const p = rowOff + set.wordCount * bigSize + (col - set.wordCount) * smallSize;
  return set.longWords ? b.readInt16BE(p) : b.readInt8(p);
}

// DeltaSetIndexMap: glyph id -> (outer, inner) index pair.
function parseDeltaSetIndexMap(b, off) {
  const format = b.readUInt8(off);
  const entryFormat = b.readUInt8(off + 1);
  let mapCount, dataOff;
  if (format === 0) { mapCount = b.readUInt16BE(off + 2); dataOff = off + 4; }
  else { mapCount = b.readUInt32BE(off + 2); dataOff = off + 6; }
  const entrySize = ((entryFormat & 0x30) >> 4) + 1;
  const innerBits = (entryFormat & 0x0f) + 1;
  return { mapCount, dataOff, entrySize, innerBits };
}

function lookupIndex(b, map, gid) {
  // Beyond the end, the last entry applies to every remaining glyph.
  const i = Math.min(gid, map.mapCount - 1);
  const p = map.dataOff + i * map.entrySize;
  let raw = 0;
  for (let k = 0; k < map.entrySize; k++) raw = (raw << 8) | b.readUInt8(p + k);
  return { outer: raw >> map.innerBits, inner: raw & ((1 << map.innerBits) - 1) };
}

export function parseHvar(b, off) {
  if (!off) return null;
  const ivsOffset = b.readUInt32BE(off + 4);
  const advanceMapOffset = b.readUInt32BE(off + 8);
  const store = parseItemVariationStore(b, off + ivsOffset);
  if (!store) return null;
  return {
    store,
    advanceMap: advanceMapOffset ? parseDeltaSetIndexMap(b, off + advanceMapOffset) : null,
  };
}

function regionScalar(region, coords) {
  let scalar = 1;
  for (let a = 0; a < region.length; a++) {
    const { start, peak, end } = region[a];
    if (peak === 0) continue;                       // axis not involved
    const c = coords[a] ?? 0;
    if (c === peak) continue;
    if (c <= start || c >= end) return 0;
    scalar *= c < peak ? (c - start) / (peak - start) : (end - c) / (end - peak);
  }
  return scalar;
}

/** Advance-width delta in font units for one glyph at the given normalised coords. */
export function advanceDelta(hvar, gid, coords) {
  if (!hvar) return 0;
  const { store, advanceMap } = hvar;
  // With no explicit map the glyph id indexes the single variation data set.
  const idx = advanceMap ? lookupIndex(hvar.buf, advanceMap, gid) : { outer: 0, inner: gid };
  const set = store.dataSets[idx.outer];
  if (!set || idx.inner >= set.itemCount) return 0;

  let delta = 0;
  for (let col = 0; col < set.regionIndexCount; col++) {
    const region = store.regionList.regions[set.regionIndexes[col]];
    const s = regionScalar(region, coords);
    if (s === 0) continue;
    delta += s * readDelta(hvar.buf, set, idx.inner, col);
  }
  return delta;
}
