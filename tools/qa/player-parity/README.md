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

## Nothing on this page is reconstructed

Two exact sources, and the page is drawn from both.

**Boxes, colours and layout** come from the canonical snapshot: geometry, radii,
fills and their opacities, text content, size, weight, tracking and case.

**Icon outlines** come from `tools/figma-export/player-icons/exact`, the literal
paths extracted from the FINAL `.fig`. The canonical exporter records no vector
geometry - `canonical/code.js` reads no `vectorPaths` and calls no `exportAsync` -
so the outlines used to be reconstructions drawn to fill the exact boxes. They are
not any more. The script names each one when it runs:

| | |
|---|---|
| pause glyph | node `2399:31221` - the glyph the frozen frame shows |
| like | node `2399:31217`, outlined thumbs-up |
| swipe active | component master `2401:38`, the 54-segment vector network |
| swipe inactive | component master `2400:31363` |
| dislike | exact, drawn at 35% so it reads as not-built |
| overflow | exact, drawn at 35% - the app reserves the space and draws nothing |

The play glyph is **not** on this page: `Controls > play/pause > Container` holds
one glyph, and it is Pause. Compare the app's play face against `player_play.svg`
in the bundle instead.

`tools/figma-export/player-icons` remains the in-Figma route for refreshing these
paths from the live document when the design moves.

## Two `Controls` rows

Each frozen page carries two `Controls:margin` rows at the same y=426, one
`visible: false`. The hidden one holds stale values - its dark play/pause is still
navy where the visible one is mint. The renderer skips invisible nodes, and so
should anything else reading this snapshot.

## Reading the comparison

The reference draws the frozen frame's own state, which is **Pause**. Pair it with
the app's playing capture for a like-for-like glyph; the idle capture shows the
play face, which the frozen frame has no counterpart for.

Deliberate differences, all deferred:

- the app reserves the header's trailing overflow slot and draws nothing in it;
- dislike is not built, so the reference dims it;
- the app keeps its History entry in the frozen `dislike` slot, so the right-hand
  **glyph** differs. Its colour does not: that slot takes the frozen row's
  `player_control_action`.

Anything else is a finding.
