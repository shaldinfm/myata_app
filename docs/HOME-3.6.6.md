# HOME — 3.6.6 (Phase B)

How the frozen HOME was derived and what was decided where the source is silent.
Everything is measured from
`tools/figma-export/canonical/figma-canonical-{light,dark}-final.json`, the FINAL
canonical export; node names are quoted so any claim can be re-checked.

## The frozen frame

`HOME` / `HOME_dark`, 390×757, background is the `background` semantic role.

```
Header - TopAppBar        0 ..  64
Main                     64 .. 628      padding 16, gap 16
  Наши потоки            80             Bold 28/36
  streams row           132 .. 347      cards 316×198 r14, gap 15
  Мятные плейлисты      363             Bold 28/36
  playlists row         415 .. 612      cards 160×160 r20, gap 16
```

Every one of those anchors is reproduced exactly, at 320/360/390/412dp in both
themes, on API 24 and API 36 — see `HomeLayoutTest`.

### The rows are taller than their cards, and that matters

The canonical containers are **215** and **197** against cards of 198 and 181.
That slack is not decoration: it is what puts `Мятные плейлисты` at 363 rather
than 346. Reproducing the card heights alone would pull everything below the
streams row up by 17. The two row heights are therefore carried as dimensions in
their own right.

### It scrolls now — an approved responsive adaptation

**HOME is vertically scrollable. This is an approved responsive adaptation, not a
new product mechanic.**

It is required, not chosen. The frozen frame is a fixed 757 and the old layout
matched that by weighting its rows, so the stream and playlist cards stretched or
squashed with the screen and **no device ever rendered the frozen 316×198 or
160×160**. Vertical scrolling is what lets the cards keep their frozen sizes on a
viewport whose height the design does not fix, and it is what makes the 154 of
bottom clearance reachable rather than a dead band — without it the content below
the fold would simply be unreachable behind the Mini Player and the BottomNavBar.

So it serves exactly two ends, both of them fidelity:

- **preserve the frozen geometry across every supported width**, 320 through
  412dp, instead of distorting it to fit;
- **keep content clear of the Mini Player and the BottomNavBar**, which float over
  the bottom of the screen.

Nothing interactive changed: the same three stream targets with the same
`switchStream` + navigate behaviour, the same playlist list and its browser
intent, the same horizontal scrolling inside each row, the same split-mode
handling. No new gesture, affordance or destination is introduced.

**This behaviour is settled and is not revisited further in this PR.** A later
change to how HOME scrolls is a separate, owner-approved decision.

## Bottom clearance

HOME is the screen the Mini Player floats over, so it is the screen that has to
reserve room for it. The reserved height is the chrome stack, measured from the
canonical frames:

```
BottomNavBar     76      canonical, 680.5..756.5 of a 757 frame
gap               4      mini_player_margin_bottom
Mini Player      74      13 + 48 + 13
total           154
```

plus the system bar inset at runtime, because `MainActivity` adds the same inset
to the navigation bar's own padding and a gesture-nav device would otherwise slip
the last card under the pill.

**This is deliberately not the HOME frame's own `paddingBottom: 128`.** That
number is the leftover of a fixed 757-tall frame that never scrolls, and it stops
26 short of the pill; it works in Figma only because the last 26 is the playlist
row's own empty slack. On a screen that scrolls, the last real content would end
up under the pill — which is the failure this reserves against.

The clearance is `clipToPadding=false` scroll room, not a dead band, so the
content still fills the screen and only the scroll extent grows.

## Colours and type

No raw hex survives in the layout.

| | role | light | dark |
|---|---|---|---|
| screen and header | `background` | `#F8F9FA` | `#0F253E` |
| greeting, both headings | `text_heading` | `#003056` | `#F5F7FA` |
| playlist card, behind the art | `brand_playlist_card_placeholder` | `#EDEEEF` | same |

The playlist placeholder is identical on both canonical pages, so it joins the
fixed brand colours and gets no night variant. It is only ever visible while the
artwork loads — the image fill is CROP and covers the card completely.

Type is unchanged from the typography migration: `Наши потоки` and
`Мятные плейлисты` are `Montserrat.Bold.28_36`, the greeting is
`Montserrat.Medium.24_32`. Both headings carry a `minHeight` of their own token
line height, for the reason measured in B2: a token applied to a single line
arrives as line *spacing*, `StaticLayout` adds none after the last line, and the
view would otherwise measure at the font's natural height instead of the frozen
36 — drifting every anchor below it.

## Decided, because the source is silent

**The header band ships with a neutral greeting and no avatar.** The frozen
`Heading 1` reads `Привет, Денис!` and the band carries a 40×40 circular avatar
on the trailing edge. Both are Phase G surfaces: the app has no accounts, the
FINAL source has no signed-out HOME header, and Phase G explicitly forbids
placeholder avatar artwork. The owner's decision was to keep the band, its height
and its typography — so nothing below it moves when G lands — with the greeting
carrying no name and the avatar omitted.

`Привет!` is therefore the one string on this screen that is not verbatim from the
canonical frame. Both section headings are.

## Not reproduced

| | |
|---|---|
| `Link` "все >" beside `Мятные плейлисты` | `visible: false` on both pages |
| `мята` / `голд` / `экстра` groups in the streams section | `visible: false`; earlier 358×170 artwork superseded by the 316×198 cards |
| the second, hidden 40×40 header control | `visible: false` |
| the frame's first fill `#EDEEEF` | the dead underlay `semantic-tokens.json` records under every screen; the second fill is the real background |

## Playlist card

The canonical card is a 160×160 `Background` at radius 20 with a cropped image
fill. The card clips to that radius itself, so `PlaylistAdapter` now loads a plain
bitmap: it used to round the bitmap a second time in Picasso at radius 15 *in
source pixels*, which on a 160dp card is neither 20dp nor the same on two
densities. `fit()` replaces the fixed `resize(400,400)` for the same reason — the
card is a known size, so the view measures to the exact pixels the device needs.

## Validation

| | |
|---|---|
| `./gradlew assembleDebug` | pass |
| `./gradlew lintDebug` | 0 errors, **409 warnings, down from 416** — **0 findings on any HOME file**; the seven that went were hardcoded strings and missing content descriptions in the old HOME layout |
| `./gradlew testDebugUnitTest` | pass |
| `HomeLayoutTest` | instrumented — the frozen anchors at four widths × both themes, card sizes, clearance, no clipping or overlap, and a wrapped heading pushing its row down rather than shrinking it. **API 24 and API 36, byte-identical.** |
| live capture | `tools/qa/phone/capture-home.mjs`, both APIs — evidence in [tools/qa/phone/home/](../tools/qa/phone/home/) |
| TV | smoke only: selection screen renders unchanged, `DPAD_CENTER` reaches `STATE_CHANGED state=READY playWhenReady=true`, no crash. No TV file or resource is touched — no TV fragment or layout references `fragment_main`, `rw_playlist_item`, `PlaylistAdapter`, or any new `home_*` dimension, style or colour. |

The Mini Player gate is observable from HOME and holds: hidden on a clean launch
at every width and theme, present after a banner tap starts a stream, and still
present with the last playlist clear of it when scrolled to the end.

> `Мятные плейлисты` wraps to two lines below 360dp. The typography sweep already
> records that and does not treat it as a regression — it is the longest heading
> in the app and the design is drawn at 390dp. What `HomeLayoutTest` asserts is
> that the wrap pushes the playlists row down and leaves its height alone, rather
> than eating it.
