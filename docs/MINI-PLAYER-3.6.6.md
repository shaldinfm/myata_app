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

## Interaction contract

The canonical export records no prototype reactions, so none of this comes from
Figma. It is the owner's decision, taken after the geometry was accepted.

| target | does |
|---|---|
| the pill body, artwork and metadata included | opens PLAYER |
| the play/pause button | playback only, **never** navigation |

The button is a child of the body with its own click listener, so it consumes its
own taps and one never reaches the other. `MiniPlayerContractTest` dispatches a
real touch at the button's centre *through the pill* — rather than calling
`performClick()` on the button, which would prove nothing about routing — and
asserts the destination is unchanged. The artwork, title and artist have no
listeners of their own, which is what puts them inside the body's target rather
than beside it.

PLAYER is reached through the shell's own `openPlayer`, the same call the bottom
navigation makes, so this adds no second route to the destination.

**Accessibility keeps the two apart.** The button remains its own focusable node
with its own `Воспроизвести` / `Пауза` description. The body's click is relabelled
with `ViewCompat.replaceAccessibilityAction` so TalkBack offers "открыть плеер"
instead of a generic "activate" — a label change only; the null command leaves the
behaviour exactly as the listener defines it.

The body is a control now, so it carries the platform's press affordance:
`bg_mini_player` became a `<ripple>` over the unchanged surface shape, masked to
the same 16dp rounded rectangle so it cannot paint over the corners. Geometry,
fill and stroke are untouched — they still come from `bg_mini_player_surface`,
which is the drawable this used to be.

## Visibility contract

The design draws the pill on HOME, COLLECTION, COLLECTION pusto and ABOUT US and
omits it from PLAYER — but every frame it appears on already has a track in it,
so the frames say nothing about an app that has never played anything. The owner's
contract fills that gap:

| | |
|---|---|
| cold launch, nothing started | **hidden** |
| user starts MYATA / GOLD / XTRA | appears, showing that stream |
| user pauses | **stays up**, showing the paused state |
| HOME ↔ COLLECTION ↔ ABOUT US | preserved, stream and all |
| PLAYER | hidden |
| UI recreated over a live service | appears immediately, from the service |
| true cold start, no session survived | hidden until the user starts a stream |

**Visibility is not `isPlaying`.** A paused stream is still the user's chosen
stream. The gate is `StreamsViewModel.hasPlaybackSession`, read from the player's
own timeline — a session is "the service has a media item loaded". That is what
separates *nothing selected yet* from *selected and paused*, in both directions:

- on a genuinely cold start the player is empty, so it stays false however many
  times the app is opened;
- when the UI is rebuilt over a service that is still alive, the new controller
  reconnects to the existing timeline and it comes back true on its own.

**Nothing is persisted to make the pill reappear.** There is no stored flag, so
there is none to go stale, and a service that really did die leaves the pill
hidden — which is the intended outcome, not a gap.

`MiniPlayerVisibility` holds the rule with no Android types, so the whole contract
is a unit test. `MiniPlayerContractTest` then walks all eight situations against
the real service, starting a stream through `switchStream` and letting the
timeline change reach the pill — never writing `hasPlaybackSession` itself. A
media item is set before the stream is reached over the network, so the walk holds
on an image that cannot reach the stream host as well as on one that can.

## Content clearance is a Phase B question

The pill is an overlay — the frame is literally named "Floating above Bottom Nav"
— and the canonical HOME proves it is drawn as one: `Main` ends at y=628 and the
pill starts at y=602.5, so the design itself overlaps the tail of the content by
about 10px. No bottom padding was added to HOME, COLLECTION or ABOUT US, which
are still their un-migrated legacy layouts and already let the BottomNavBar float
over them the same way. Reserving space for both belongs to each screen's Phase B
migration.

## Validation

| | |
|---|---|
| `MiniPlayerUiStateTest` | unit, in CI — the projection: title/artist split, per-stream lookup, the `xtra` alias, the brand-pair fallback, the `NO_IMAGE` marker |
| `MiniPlayerVisibilityTest` | unit, in CI — the eight visibility situations, one case per rule |
| `MiniPlayerLayoutTest` | instrumented — geometry at 320/360/390/412dp in both themes with worst-case long Russian metadata, the glyph sizes, and the icon following `isPlaying` |
| `MiniPlayerContractTest` | instrumented — the eight situations against the real service, plus body-tap-opens-PLAYER and play/pause-does-not-navigate |

Both instrumented classes pass on **API 24 and API 36**, with byte-identical
geometry.

> **The full instrumented suite cannot complete on API 24, for a pre-existing
> reason.** `screen0..9` are 1080×1921 PNGs in a density-less `drawable/`, so each
> `MainActivity` launch decodes a window background upscaled to 57,187,632 bytes
> on a 420dpi device, and the heap runs out after a few launches. This reproduces
> with the Mini Player classes excluded — the run then dies in
> `RandomWindowBackgroundTest` on the identical allocation — so it is not caused
> by this work and is not fixed here. The Mini Player classes pass on API 24 when
> run as their own instrumentation run, and the full suite passes on API 36 apart
> from `ExampleInstrumentedTest.useAppContext`, which is AGP template residue
> asserting the namespace against a different applicationId.

Live-app evidence, including playback continuity across navigation and
backgrounding, is in
[tools/qa/phone/mini-player/](../tools/qa/phone/mini-player/).
