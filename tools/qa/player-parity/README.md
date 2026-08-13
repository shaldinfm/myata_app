# PLAYER parity — the app beside FINAL

Bounds and unit tests say the app hits the frozen numbers. They cannot say it
*looks* like the frozen design. This puts the two next to each other.

```bash
node tools/qa/player-parity/render-final.mjs
```

writes `final-light.svg` and `final-dark.svg`: the frozen PLAYER upper section
drawn from `tools/figma-export/canonical/figma-canonical-{light,dark}-final.json`,
node for node. Two placeholders are left in the SVG for whoever rasterises it -
`__ARTWORK__` for the album art and `__OUTLINE__` for its 1px stroke - so the file
stays small and carries no image bytes.

## Why FINAL has to be drawn at all

There is no render of FINAL in this repository. `tools/figma-export/dark-theme/previews/`
holds PNGs of the **earlier** dark reconstruction, not of FINAL, and the canonical
snapshot is JSON. So a side-by-side against FINAL means drawing FINAL from the
snapshot. Every rectangle, radius, offset, colour, font size and text case in the
SVG is read out of that file; nothing is typed in.

## What is exact and what is not

Exact, straight from the snapshot: geometry, radii, fills and their opacities,
text content, size, weight, tracking and case, and every icon's **box**.

Not exact: the icon **outlines**. The canonical exporter records a vector's box,
position and fills but never its geometry - `canonical/code.js` reads no
`vectorPaths` and calls no `exportAsync`. The five outlines the page draws inside
those exact boxes are the app's own drawables, listed by the script when it runs:

| | |
|---|---|
| play glyph | fills the frozen 23.33 square |
| like (thumbs-up) | fills the frozen 24.5 x 23.33 box |
| swipe active marker | nine-lobed cookie, its amplitude solved from the frozen 8.53 x 8.42 box |
| dislike | deferred, drawn at 35% so it reads as not-built |
| overflow | deferred, drawn at 35% - the app reserves the space and draws nothing |

**These can be made literal.** `tools/figma-export/nav-icons` already exports real
SVG geometry via `exportAsync(SVG)` and resolves nodes by id; it was only ever
run over the four bottom-nav icons. The PLAYER icon node ids are listed in
[docs/PLAYER-3.6.6.md](../../../docs/PLAYER-3.6.6.md#the-icons-the-export-cannot-give-us).

## Two `Controls` rows

Each frozen page carries two `Controls:margin` rows at the same y=426, one
`visible: false`. The hidden one holds stale values - its dark play/pause is still
navy where the visible one is mint. The renderer skips invisible nodes, and so
should anything else reading this snapshot.

## Reading the comparison

Deliberate differences, both deferred:

- the app reserves the header's trailing overflow slot and draws nothing in it;
- the app keeps its History entry in the frozen `dislike` slot, so the right-hand
  glyph differs and is tinted `text_primary` rather than the frozen row colour.

Anything else is a finding.
