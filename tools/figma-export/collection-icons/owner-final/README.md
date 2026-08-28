# COLLECTION row action — authoritative vector

`collection_row_action_arrow.svg` is the owner's export of the COLLECTION track
row's trailing action from the frozen 3.6.6 Figma file. It is the source of
`app/src/main/res/drawable/ic_collection_row_action.xml`, whose path is copied
from it verbatim.

Same arrangement as [`../../player-icons/owner-final/`](../../player-icons/owner-final):
the geometry exists only in the live Figma document, so an export handed back by
the owner is the authoritative artefact and lives here next to the drawable it
produced.

## Canonical nodes

The instance appears three times per theme, once per track item, and all six
agree:

| theme | nodes |
|---|---|
| light | `2409:31572`, `2409:31560`, `2409:31542` |
| dark | `2444:18398`, `2444:18410`, `2444:18422` |

`Track Item N` > `Container` > `Button` > `Container` > `arrow_forward`, an
instance of component `2409:31540` (key
`95d643147904484c1fb0a3ff701b05b73a5a20b0`), 24×24 with rotation 45 inside a
33.94 wrapper — 24·√2, the bounding box of a 24 square turned through 45°.

## What the export contains

The whole control at 40×40, which is why the drawable's viewport is 40:

- `<rect>` — the ring. **Not** taken into the drawable: it already exists as
  `bg_collection_row_action.xml`, which this export confirms exactly (`x/y 1`,
  38 square, `rx 19`, `stroke-width 2`, `#1C4771` = light `primary`).
- `<path>` — the glyph, already in its final north-east orientation with the 45°
  baked into the coordinates. Copied verbatim.

`#1C4771` is light `primary`; the drawable holds a white placeholder and the
view tints with `app:tint="@color/primary"`, which is what gives the dark theme
its own `#5FD9B4`.

## Why this file had to exist

The canonical snapshot exporter records each node's box and never its geometry,
so the drawable was previously authored by matching that box to a published
Material glyph. The box matched on all four edges — 16×16 at (4,4) — and the
path still did not: against this export, eight of the nine vertices agree to
7·10⁻⁵ and the ninth is out by 0.025, because the published Material Symbols
path rounds one vertex to `10.6` where the authoritative drawing is symmetric at
`10.575`.

A matching bounding box places the family. It does not fix the path.

`CollectionRowActionGlyphTest` transcribes this file's `d` vertex for vertex and
rasterises it as the reference the drawable is compared against, so the drawable
cannot drift back onto a Material path without failing.
