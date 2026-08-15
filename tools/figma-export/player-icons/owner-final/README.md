# PLAYER play/pause — owner-approved FINAL, supplied

Four SVGs supplied by the owner and **verbatim** as delivered. They are the
visual source of truth for the PLAYER's central control and **supersede the
play/pause geometry in [`../exact/`](../exact/)**, which PR #42 installed.

```
play_light.svg    play_dark.svg    pause_light.svg    pause_dark.svg
```

## What is in them, and what the app takes

Each file carries the whole control: an 80×80 `rx=20` background rect **and** the
glyph over it.

The app does not take the whole control. The 80×80 surface stays
[`bg_player_play_pause.xml`](../../../../app/src/main/res/drawable/bg_player_play_pause.xml)
— a ripple over a `primary` shape, which resolves to exactly the fills below —
and only the glyph paths are installed, into `ic_player_play.xml` and
`ic_player_pause.xml`. The container and the glyph stay separate views of one
control, which is what makes the buffering face possible: the surface belongs to
the button and is never taken away.

| | light | dark |
|---|---|---|
| container rect | `#1C4771` | `#5FD9B4` | = `primary`, already |
| glyph stroke | `#F8F9FA` | `#0F253E` | = `player_play_glyph`, new pair |

`#0F253E` is exactly `on_primary` in dark, but `on_primary` is `#FFFFFF` in light
against the supplied `#F8F9FA`. `on_primary` is bound to `colorOnPrimary` in
`Theme.Myata.Base`, so moving it would repaint every Material component in the
app for a three-step colour change on one glyph. Hence a PLAYER-specific pair.

## What changed against `../exact/`

**The glyphs are strokes now, not filled outlines.** `../exact/` holds them as
closed contours wound against each other so a non-zero fill leaves the middle
open; these are open shapes with `stroke-width="4"`, `stroke-linecap="round"` and
`stroke-linejoin="round"`. That is a different construction, not a different
rendering of the same one, and it is why the paths could not be merged into the
old files.

Geometry, path centrelines, straight off the files:

| | box | centre in the 80 |
|---|---|---|
| play | `28 → 51.33` × `25.0022 → 54.9979` = 23.33 × 29.9957 | 39.665, 40.0001 |
| pause | two bars, `28.335 → 51.665` × `28.335 → 51.665` = 23.33 square | 40, 40 |

A 4-wide stroke centred on those adds 2 on every side, and the round joins reach
the full 2 at each extreme, so what is **painted** is:

| | painted | |
|---|---|---|
| play | 27.33 × 33.9957 | at `(26, 23.0022)` |
| pause | 27.33 × 27.33 | at `(26.335, 26.335)` |

Same width, play taller — the relationship `../exact/` recorded at 23.33 × 29.69
against 23.33 square, which the 80×80 surface hides anyway: the control cannot
change size between faces because the surface is not the glyph.

Play is **0.335 left of the box centre** and the supplied file says so. The app
reproduces that rather than centring it — see the drawables for how.

## Installation

The two drawables take **the 80×80 control box as their viewport** and carry the
`pathData` verbatim, digit for digit, with no translation, no re-origin and no
re-scale. Cropping the viewport to the glyph would have meant either editing the
coordinates or centring a glyph the file places off-centre; taking the control's
own box means the supplied numbers are used exactly as supplied and land exactly
where the file puts them.

Nothing here was normalised, thickened, thinned, simplified or redrawn.

## `../exact/`

Left in place and still correct for `like` and the two swipe markers, which the
app still installs from it. Its `player_play.svg` and `player_pause.svg` are
superseded by this directory and are no longer installed anywhere.
