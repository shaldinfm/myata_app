# Mini Player — 3.6.6 (B2)

How the frozen Mini Player was derived and what was decided where the source is
ambiguous. Everything here is measured from
`tools/figma-export/canonical/figma-canonical-{light,dark}-final.json`, the FINAL
canonical export of `CURRENT ANDROID UI — DARK` / `- LIGHT`; node ids are quoted
so any claim can be re-checked.

## Where it appears

`Now Playing Mini Player (Floating above Bottom Nav)` exists on four frames per
theme and is **absent from PLAYER**:

| screen | dark | light |
|---|---|---|
| HOME | `2444:10372` | `2393:1650` |
| COLLECTION | `2444:18465` | `2429:142` |
| COLLECTION pusto | `2444:18553` | `2429:274` |
| ABOUT US | `2444:18669` | `2429:169` |
| PLAYER | — | — |

COLLECTION and COLLECTION pusto each carry **two** stacked copies at identical
geometry. The lower one (`2444:18451`, `2444:18539`, `2407:31523`, `2429:260`)
has title, artist and icon all `#8FB6E6` and is hidden underneath the copy listed
above. It is earlier residue, and the topmost copy — which agrees with HOME and
ABOUT US — is what was implemented.

The empty-collection frame shows the pill too, so there is no "nothing playing"
state to reproduce: the design has the pill up whenever one of these screens is.

## Geometry

At the 390 reference width, and reproduced exactly:

```
 13 | artwork 48 | 12 | text column 233 | 12 | button 27 | 13    = 358
```

| | frozen | how it is expressed |
|---|---|---|
| pill | 358×74, r16, x=16 | two 16dp margins; height from content |
| gap above BottomNavBar | 4 | `mini_player_margin_bottom` |
| padding | 12 + a 1px INSIDE stroke | one 13dp padding; the stroke is painted by `bg_mini_player` |
| artwork | 48×48, r8, image CROP | `ShapeableImageView`, `centerCrop` |
| title | Muller Medium 15 / 27.5 | `TextAppearance.Myata.Onest.Medium.15_27_5` |
| artist | Muller Regular 14 / 20 | `TextAppearance.Myata.Onest.Regular.14_20` |
| button | 27×25 slot, glyph 11×14 play / 12×14 pause | 27dp wide, taken to the row's 48dp for the target |

ABOUT US puts the pill 6px above the bar rather than 4. Three screens against
one, so 4.

**Height is not fixed, and that is the whole trick.** Figma's frame hugs its
content and the artwork is the tallest child at 48; the text column comes to
27.5 + 20 = 47.5 and never governs. So the title can carry its real line height
without the pill moving.

## The 18px box is not reproduced

On the canonical page the title sits in a 233×18 container with `clipsContent`
on, against a 27.5px line height, so the glyphs are genuinely cut in both themes.
That is a **legacy finding on the canonical pages, not part of the 3.6.6 design** —
recorded as such in
[screens-3.6.6/IMPLEMENTATION-NOTES.md](../tools/figma-export/screens-3.6.6/IMPLEMENTATION-NOTES.md)
and explicitly out of scope — and the owner's decision for B2 was not to ship it.

Giving the title its real height took one non-obvious step. A token applied to a
single line does **not** produce a taller view: the line height arrives as line
*spacing* (`MyataTypography` does that deliberately, because below API 28 nothing
else is honoured), and `StaticLayout` does not add spacing after the last line.
Measured, a one-line 15/27.5 TextView came out at the font's own 19dp. So the
title and artist are `wrap_content` **plus a `minHeight` of their own token line
height**, with `center_vertical` for the frozen `textAlignVertical`.

What this changes, precisely: the outer 358×74 and every horizontal anchor are
identical to the canonical frame, verified by measurement. Inside, the text block
grows from 38 to 47.5 and so re-centres — the artist baseline sits about 4.75dp
lower than on the canonical page. That is the unavoidable cost of the title
having a line box at all, and it is the only place the implementation departs
from the frame.

## Colours

Four of the five are the same on both canonical pages, so they are fixed brand
colours with no `values-night` entry — the same contract `brand_nav_active_*`
already uses. The pill reads as `primary` on the Light page (light primary is
`#1C4771`) and as `surfaceContainer` on the Dark page (dark surfaceContainer is
`#1C4771`), which is why one hex is legible in both themes and why neither
semantic role can express it alone.

| role | light | dark |
|---|---|---|
| `brand_mini_player_container` | `#1C4771` | `#1C4771` |
| `brand_mini_player_hairline` | `#FFFFFF` @10% | same |
| `brand_mini_player_title` | `#E0E8F2` | same |
| `brand_mini_player_icon` | `#F5F7FA` | same |
| `mini_player_artist` | `#DCEBFE` | `#B3C4D1` |

The artist line is the only one that genuinely differs. Light is unanimous across
all four instances; dark is `#B3C4D1` on HOME and COLLECTION with ABOUT US the
outlier at `#DCEBFE` @80%, and the dark UI KIT component
`Component / Dark / Mini-player / *` settles it at `#B3C4D1`.

## Icons

The canonical exporter records a vector's size, position and fills but never its
geometry — there is no `vectorPaths`, `vectorNetwork` or `exportAsync` in
`canonical/code.js` — so no path data for these icons exists in the repository,
the same gap the BottomNav icons had. Here it did not need a plugin run, because
the boxes identify the glyphs:

| | Figma | Material Symbols at 24dp |
|---|---|---|
| play | 11×14 at (8,5) | `play_arrow` — `M8 5v14l11-7z` → 11×14 at (8,5) |
| pause | 12×14 at (6,5) | `pause` — `M6 19h4V5H6v14zm8-14v14h4V5h-4z` → 12×14 at (6,5) |

Bounding box, origin and canvas all agree exactly. Both are written glyph-tight —
the Material path translated into an 11×14 / 12×14 viewport — so centring in the
27dp slot reproduces the canonical x (8 for play, 7.5 for pause) instead of the
1.5dp drift a 24dp canvas would give.

## Not implemented, and why

| | |
|---|---|
| progress bar | `Progress Bar (Absolute bottom of pill)` is `visible: false` on every instance |
| background blur | the frame carries `BACKGROUND_BLUR r12` under a fully opaque fill, so it shows nothing in Figma either; Android's equivalent is API 31+ |
| buffering variant | exists in the dark UI KIT (`2436:771`) but on no canonical screen |
| two-layer drop shadow | Android has one shadow model; `elevation 8dp` stands in for the `y4/blur6/spread-4` + `y10/blur15/spread-3` pair |

## Open question — tapping the pill body

**Whether tapping the pill outside the button opens PLAYER is not defined
anywhere.** The canonical export records no prototype reactions, the app has no
mini player today to inherit a behaviour from, and
[ANDROID-3.6.6-PLAN.md](ANDROID-3.6.6-PLAN.md) does not mention it. It is
therefore **not implemented** rather than guessed at. Only the play/pause button
is interactive.

Everything else about the pill's behaviour is defined: which screens show it, what
the button does, and what the icon means.

## Content clearance is a Phase B question

The pill is an overlay — the frame is literally named "Floating above Bottom Nav"
— and the canonical HOME proves it is drawn as one: `Main` ends at y=628 and the
pill starts at y=602.5, so the design itself overlaps the tail of the content by
about 10px. No bottom padding was added to HOME, COLLECTION or ABOUT US, which
are still their un-migrated legacy layouts and already let the BottomNavBar float
over them the same way. Reserving space for both belongs to each screen's Phase B
migration.

## Validation

`MiniPlayerLayoutTest` (instrumented) measures the frozen geometry at 320/360/390/412dp
in both themes, with the worst-case long Russian metadata, and asserts the icon
follows `isPlaying` and the pill appears only on the screens the design draws it
on. Run on **API 24 and API 36**, byte-identical results.

`MiniPlayerUiStateTest` (unit, in CI) covers the projection: the title/artist
split, the per-stream lookup, the `xtra` alias, the brand-pair fallback and the
`NO_IMAGE` marker.

Live-app evidence, including playback continuity across navigation and
backgrounding, is in
[tools/qa/phone/mini-player/](../tools/qa/phone/mini-player/).
