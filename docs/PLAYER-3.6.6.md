# PLAYER — 3.6.6 (Phase B)

The upper section of PLAYER, migrated to the FINAL frozen design. Measured from
`tools/figma-export/canonical/figma-canonical-{light,dark}-final.json`.

## What Phase B covers, and what it does not

The frozen `PLAYER` frame is 390×1022 and holds two sections: the player itself
and, below it, an inline Broadcast History card. **Phase B re-skins the first and
leaves the second alone** — Phase C owns the history redesign, so History keeps
its existing bottom sheet and its existing entry point, and neither
`HistoryBottomSheet` nor `item_history_track.xml` is touched. The frozen frame's
lower half is therefore not reproduced yet; the space is simply empty. That is a
deliberate, temporary deviation, agreed before the work started.

Two more owner decisions, both because the frozen source describes a later phase:

- **No overflow menu.** The canonical `Menu / Плеер` is four rows all reading
  `Действие` — placeholder copy — and the real entries are the Sleep Timer (D) and
  Report a problem (E). The header's trailing 32×39 box is reserved so the label
  stays centred where the design centres it, but nothing is drawn in it and
  nothing is clickable. A dead control would be worse than an empty slot.
- **No dislike control.** The frozen `Controls` row has `like` and `dislike`.
  Phase B ships the existing favourite in the `like` slot and the existing History
  entry in the other, so History keeps working until Phase C brings the card onto
  this screen. No new feedback behaviour is invented.

## The frozen upper section

Offsets are absolute, from the top of the frame; `Player Section` is
hand-positioned rather than auto-laid-out.

```
 16   header row       "Сейчас играет", centred, 358×47
 63   swipe dots       3 × 10, centred
 79   page top
 95   album art        239×239 r20, image FILL, 1px outline
371   track title      Black 24/24
403   artist           Regular 18/18
442   controls         play 80×80 r20, like/dislike 49×54, 32 inset
522   controls bottom
```

The header and the dots are chrome — the label reads the same on every stream and
the dots describe the pager — so they live in `fragment_player.xml` and stay still
while the pages move. The rest is per-stream and lives in
`fragment_myata_stream.xml`, whose offsets are the frozen ones minus 79.

**The status bar inset moved.** It used to pad the dots, which was correct when
they were the topmost thing on screen. The header is above them now, so the inset
goes once on the shell root and everything below it keeps its frozen spacing.

## Text boxes: the frozen height is not reachable

The frozen title box is 24 tall against a 24 line height, and the artist box 18
against 18 — a box exactly as tall as its own font size. Figma can draw that,
having no font padding and honouring the declared line height literally. Android
cannot: Montserrat Black at 24sp needs about 29dp from ascent to descent, and
Regular at 18sp about 22dp. Forcing the frozen heights clips the glyphs, which is
the defect class this migration does not reproduce.

So each block is **anchored by its own offset from the page top** rather than
stacked. A text box that needs more height grows into the frozen 8 gap below it
instead of pushing everything down, which is what keeps the controls — and the
section as a whole — exactly where the frozen frame puts them. Measured, the
visible gap between the two lines comes out at about 7 against the frozen 8, and
`title 292 / artist 324 / controls 363` are exact.

Both lines are one line, ellipsized. The frozen boxes clip instead; the design
decision is one line, and ellipsis is that decision without the clipping.

## Title and artist were the wrong way round

The app put the **artist** in the 24 Black slot and the **title** in the 18
Regular one — the reverse of the frozen frame, and of the Mini Player shipped in
B2. It is corrected here: `main_song` is the track title on top, `main_author` the
artist below. The ids keep their meanings, so the clipboard copy still reads
`artist - title`.

## What the re-skin removed

| | why |
|---|---|
| full-bleed per-stream background artwork | the frozen PLAYER is a flat `background` fill; there is no such layer in the frame |
| the decorative `artist_frame` overlay and the circular artwork crop | the frozen artwork is a 239 rounded square |
| six per-stream play/pause drawables | the frozen control is tinted by role — `primary` and `on_primary` — not by station |
| the heart on the favourite control | the frozen `like` is a thumbs-up; there is no heart anywhere in the frame |
| three identical white swipe dots | the frozen active marker is a nine-lobed cookie in `primary`, against circles in the inactive pair |
| per-stream accent tinting of the text and the two side controls | every control in the frozen frame takes a semantic colour |

The drawables themselves stay in the repo; only this screen stops referencing
them. Android TV uses its own (`btn_play_tv` / `btn_pause_tv`) and is untouched.

**`layout-sw320dp/fragment_myata_stream.xml` is deleted.** `sw320dp` matches every
modern phone, so that variant — not `layout/` — was what actually shipped, and
leaving a hand-tuned percentage-based copy beside the frozen one would mean the
re-skin never appeared on a real device. One implementation now.

## Colours

| | role | light | dark |
|---|---|---|---|
| screen | `background` | `#F8F9FA` | `#0F253E` |
| "Сейчас играет" | `player_header_label` | `#42474E` | `#F5F7FA` |
| track title | `text_primary` | `#191C1D` | `#F5F7FA` |
| artist | `text_secondary` | `#42474E` | `#B3C4D1` |
| artwork stroke | `outline` | `#E1E3E4` | `#466D8F` |
| artwork backdrop | `brand_player_artwork_backdrop` | `#1C4771` | same |
| play/pause | `primary` / `on_primary` | `#1C4771` / `#FFFFFF` | `#5FD9B4` / `#0F253E` |
| like | `player_like` | `#3F4A3C` | `#F5F7FA` |
| like, in the collection | `primary` | | |
| history (the deferred `dislike` slot) | `player_control_action` | `#42474E` | `#F5F7FA` |
| swipe, active | `primary` | | |
| swipe, inactive | `player_swipe_inactive` | `#801C4771` | `#6F899F` |

The rule used where a frozen value does not match a role in both themes: prefer
the approved role when the dark value matches it exactly and the light value is a
near neighbour of the *same* role — that is the token contract absorbing drift on
a single node. Declare a pair only when the two values are different roles.

- artist: `#B3C4D1` is exactly `text_secondary` dark; light's `#2F353C` against
  `#42474E` is the same role → one token.
- artwork stroke: `#466D8F` is exactly `outline` dark; light's `#FFFFFF` against
  `#E1E3E4` is the same role → one token.
- header label: `#F5F7FA` is exactly `text_primary` **dark** while `#42474E` is
  exactly `text_secondary` **light** — different roles → its own pair.

The artwork backdrop is `#1C4771` on both pages, so it is fixed and gets no night
variant. In Figma it is a 229 square behind an opaque 239 one and never really
shows; here it is the artwork card's background, which is the one state where it
earns its place — the colour the design put behind the art fills the card while
the art loads.

## Two `Controls` rows, one of them stale

Each frozen page carries **two** `Controls:margin` rows at the same y=426, and one
of them is `visible: false`. They do not agree: the hidden copy's dark play/pause
is still `#1C4771`, while the visible one is `#5FD9B4`. Everything in this
document is read off the **visible** row, and anything else reading the snapshot
should skip invisible nodes too — the hidden row is what makes the frozen dark
control look like navy-on-navy, which it is not.

| | light | dark | role |
|---|---|---|---|
| `play/pause` fill | `#1C4771` | `#5FD9B4` | exactly `primary` |
| `play/pause` glyph | `#FFFFFF` | `#0F253E` | exactly `on_primary` |
| `dislike` glyph | `#42474E` | `#F5F7FA` | the `player_header_label` pair |
| `like` glyph | `#3F4A3C` | `#F5F7FA` | dark exactly `text_primary`; light a green-cast neighbour of `text_secondary` → its own pair, `player_like` |

## The header label is upper-case

`Mobile Header (Subtle) > Container > "Сейчас играет"` is `textCase: UPPER`, 12/16
with 0.6px of tracking. It renders **СЕЙЧАС ИГРАЕТ**. The app drew it as written
until this was corrected; the string keeps the characters the design stores and
`textAllCaps` does what Figma's case does, with `letterSpacing="0.05"` for the
0.6/12.

## The swipe indicator is not three dots

```
swipe            104.42 x 10 at (126.79, 47), padding 38/38, itemSpacing 0
  Shape Set      10 x 10 at 38   variant Shape = "9-sided cookie"   ← active
    shape        8.53 x 8.42 at (0.74, 0.79)   primary, opacity 1
  Shape Set      10 x 10 at 48   variant Shape = "Circle"
    shape        8.42 x 8.42 at (0.79, 0.79)   inactive pair
  shape          8.42 x 8.42 at 58             inactive pair
```

Three 10dp slots edge to edge — 30 wide, centred — each holding a marker about
8.4 across, centred in its slot at (5, 5). **The active page is a different
shape**, a nine-lobed scalloped disc rather than a circle, as well as a different
colour. The app drew three identical white ovals filling the whole 10dp.

The inactive colour is the one thing the two pages express differently: light says
`primary` at 50% opacity, dark says a solid `#6F899F`. That forces a pair,
`player_swipe_inactive`, and the light half keeps the frozen expression
(`#801C4771`) rather than the composited `#8AA0B6` it lands on.

`ic_player_swipe_active` is derived rather than copied, and the derivation is the
box. A nine-fold scalloped disc `r(t) = 1 + a·cos(9(t + π/2))` has a square
bounding box only when `a = 0`; the frozen 8.53 × 8.42 fixes the ratio at
1.01306, and `a = 0.081` reproduces it to six decimal places. Sampled as 27
cubics, which holds the analytic curve to 0.014dp.

## The icons, exact

The canonical snapshot records a vector's box but never its geometry, so for a
while every icon on this screen was an exact box around a reconstructed outline.
That is over: the owner extracted the literal paths from
`Дизайн Приложения ФИНАЛ.fig` offline, and the bundle is committed at
[tools/figma-export/player-icons/exact](../tools/figma-export/player-icons/exact/)
— SVGs, VectorDrawables, and a manifest carrying the node ids, blob ids,
dimensions, transforms and pathData behind each one.

Five of them are installed. **All five are outlines, not solids** — which no
bounding box could have revealed, and which every reconstruction got wrong:

| drawable | replaced | now |
|---|---|---|
| `ic_player_play` | a solid triangle filling a 23.33 square, read off a box | node `2484:59`, **23.33 × 29.6927299**, hollow triangle |
| `ic_player_pause` | solid bars, Material's 1:1:1 split taken to a 23.33 square | node `2399:31221`, 23.3333334 square, **two hollow bars** |
| `ic_player_like` | a thumbs-up drawn to fill the 24.5 × 23.33 box | node `2399:31217`, same box, **outlined** |
| `ic_player_swipe_active` | a cosine-lobed disc, amplitude solved from the box | master `2401:38`, the **54-segment vector network** |
| `ic_player_swipe_inactive` | a circle drawn to the box | master `2400:31363`, the component's own circle |

**The play and pause glyphs are not the same size.** Pause is 23.33 square; play
is the same width and 29.69 tall. That settles what the canonical snapshot could
not: `Controls > play/pause > Container` HUGs its child and measures 23.33 square,
so the glyph the frozen frame shows is **Pause**. Play is its own node and is not
on this screen at all. Both are drawn at their own size, centred, so the control
does not move between them.

The swipe drawables are the 10 component slot rather than the ink: their group
transform is the component master's, so the marker lands where the component puts
it. The ink works out at 8.53 × 8.42 and 8.42 square — exactly what the snapshot
records for those nodes, which is the two sources agreeing.

Dislike and overflow are in the bundle and deliberately not installed: their
phases own them.

Two things carried knowingly:

- the supplied cookie path contains 18 degenerate segments that return to their
  own start point, an artefact of converting a vector network to a single path.
  Seventeen are sub-pixel; the first has control points at y=-3.46 and paints a
  faint spike above the top lobe — **3 pixels at the 26px this renders at** on a
  420dpi screen. Dropping that one no-op segment would remove it. It is left in
  rather than editing geometry supplied as exact.
- lint's `VectorPath` warns that the cookie's 3854-character path is long. That
  is what a literal 54-segment network costs, and it is drawn once per page.

## The icons the export cannot give us

The canonical exporter records a vector's box, position and fills but never its
geometry — `canonical/code.js` reads no `vectorPaths` and calls no `exportAsync`.
So every icon on this screen has an **exact box and a reconstructed outline**.

**Superseded by the section above** — the geometry arrived by offline extraction
from the `.fig` instead. [tools/figma-export/player-icons](../tools/figma-export/player-icons/)
stays as the in-Figma route: `nav-icons`' machinery — `exportAsync(SVG)`, ids with
a structural fallback, VectorDrawable XML out — pointed at these nodes, for
whenever the design moves and the paths need refreshing from the live document.

The nodes, all read off the visible `Controls` row:

| icon | light | dark | box |
|---|---|---|---|
| play/pause glyph | `2399:31221` | `2444:18274` | 23.33 × 23.33 |
| like | `2399:31217` | `2444:18270` | 24.5 × 23.33 |
| swipe active (cookie) | `I2402:31398;58548:7288` | `I2444:18241;58548:7288` | 8.53 × 8.42 |
| swipe inactive (circle) | `I2402:31378;58548:7250` | `I2444:18242;58548:7250` | 8.42 × 8.42 |
| dislike *(deferred)* | `2399:31224` | `2444:18277` | 24.5 × 23.33 |
| header overflow *(deferred)* | `2396:30741` | `2444:18239` | 4 × 16 |

## Icons: the box, and what the box could not say

`Controls > play/pause > Container > Icon` is 23.33×23.33, and `Container` is
`layoutSizing: HUG` — it hugs its child. So that box is **ink, not a canvas**, and
the glyph inside it is centred at (40, 40) of the 80×80 control. The two side
slots read the same way (`like`/`dislike > Container > Icon`, 24.5×23.33).

#40 read the box as a canvas and kept Material `play_arrow`/`pause` on a 24 canvas
rendered at 23. That paints 10.5×13.4dp of ink — 46% of the frozen box — and
`play_arrow` is not centred in its own canvas, so it also landed 1.6dp right of
centre. The fill was always the right size; the glyph inside it is what made the
control read as smaller than the frozen 80×80.

The correction after that filled the box exactly, which was as far as a bounding
box can take you. What a box cannot say is that the glyph is **hollow**, or that
the play glyph is 29.69 tall rather than 23.33. Both came from the exact bundle —
see [the section above](#the-icons-exact).

## The central control: three faces

One control, `Controls > play/pause`: 80×80 r20 filled with `primary`, the
`on_primary` glyph centred in it. The **fill is the button's own background**, so
it is painted in every state and only the middle changes.

| state | source | middle |
|---|---|---|
| paused / idle | `isPlaying == false` | Play glyph |
| connecting / buffering | `isBuffering == true` | progress indicator, 23dp, `on_primary`, centred |
| playing | `isPlaying == true` | Pause glyph |

Both inputs are the service's own, read through the MediaController
`StreamsViewModel` already holds: `isPlaying` from `onIsPlayingChanged`,
`isBuffering` from `onPlaybackStateChanged(STATE_BUFFERING)`. `PlayerControlState`
projects the pair onto a face — buffering wins, so a stream switch reads as
connecting rather than playing — and `PlayerControl` paints it. No second state
machine, and nothing new is stored.

**What was wrong.** Connecting used to hide the whole button (`View.INVISIBLE`)
and leave a bare 23dp spinner where the control had been. With no fill behind it
the indicator has no contrast of its own: `on_primary` is `#FFFFFF` against a
`#F8F9FA` background in Light and `#0F253E` against a `#0F253E` one in Dark. It
was invisible in **both** themes, and the control appeared to vanish mid-connect.
Two observers each owned half the control and neither owned the surface, so
nothing was responsible for keeping it on screen; that is the shape of the bug,
and one projection is the fix.

The button stops taking taps while connecting, which is what hiding it used to do
— an `INVISIBLE` view receives no touches — so a tap during connect still does
nothing and no second start command can be issued. Only the appearance changed.
TalkBack gains `player_connecting` for the state that now has a visible face and
no action.

## Playback contract

Untouched. No second state machine, no change to `MediaPlayerService`, and no
speculative fix for the open issue #15. `MyataStreamFragment` keeps the same
observers on the same LiveData; what changed is which drawable and which colour
they apply, and — in the follow-up — that the two observers now feed one
projection instead of dividing the control between them. Stream switching,
swiping, buffering, artwork and metadata updates, favourites and background
playback are all as they were.

The Mini Player stays absent on PLAYER, and its session/visibility contract from
B2 is not modified.

## Validation

| | |
|---|---|
| `./gradlew assembleDebug` | pass |
| `./gradlew lintDebug` | 0 errors, 385 warnings. One is on a PLAYER file: `VectorPath` on the exact cookie's 3854-character path, which is what a literal 54-segment network costs |
| `./gradlew testDebugUnitTest` | pass |
| `PlayerLayoutTest` | instrumented — the frozen anchors at 320/360/390/412dp in both themes, the reserved header slot proven non-clickable, worst-case long Russian metadata on one line with no clipping or overlap. **API 24 and API 36, byte-identical.** |
| `PlayerControlStateTest` | unit — the three faces, and buffering winning over playing |
| `PlayerControlRenderTest` | instrumented — the control **as painted**, in all three states and both themes: the surface, its radius, the glyph's size and centring, that the glyph is an **outline** and not a solid, and that the surface rectangle never moves. **API 24 and API 36.** |
| regression | `MiniPlayerContractTest` on both images; `HomeLayoutTest` on API 24 |
| live | `tools/qa/phone/capture-player.mjs` on both APIs — [tools/qa/phone/player/](../tools/qa/phone/player/) |
| TV | smoke: renders unchanged, `DPAD_CENTER` reaches `STATE_CHANGED state=READY playWhenReady=true`, no crash |

The live run confirms, in both themes: idle → play glyph; tap → `Пауза`; favourite
toggles both ways; swiping reaches GOLD and XTRA with playback following; the
History sheet still opens; pause returns the play glyph; state survives a
background round trip; and **the Mini Player is absent at every step**.

### Measuring the control, not its bounds

`PlayerLayoutTest` measures `btn_play`'s bounds, which is where the frozen control
sits but not what a user sees — a view can report 80×80 and paint a smaller shape,
or paint nothing at all, which is exactly what connecting did. So
`PlayerControlRenderTest` reads no view bound for the control's size. It rasterises
`player_controls`, finds the `primary`-coloured region, and every claim about size,
radius and position is a claim about those pixels:

- the painted surface is 80×80 **and** coincides with `btn_play` — an 80dp box
  holding a 64dp visible shape passes the first and fails the second;
- the top-left pixel is not filled and the fill first spans its full width 20dp
  down, so the radius is 20;
- the `on_primary` glyph inside it is 23×23, centred (searched inset by the
  radius, because in Dark `on_primary` and `background` are the same `#0F253E`
  and the page showing through the corners would otherwise read as glyph);
- connecting paints the **same** surface rectangle, with the indicator's box
  contained in it and the fill sampled just outside the indicator;
- and the surface rectangle is identical across all three faces.

Live pixel measurements agree, at 390dp/420dpi on both images: the painted control
is 210×210px = 80.0×80.0dp at the same origin in idle, connecting and playing, in
both themes, with the corner arc reaching full width 52px ≈ 20dp down.

### The app beside FINAL, in pixels

No render of FINAL exists in this repository — the snapshot is JSON, and the PNGs
under `tools/figma-export/dark-theme/previews/` are of the *earlier* dark
reconstruction. So [tools/qa/player-parity/](../tools/qa/player-parity/) draws
FINAL from the snapshot, node for node, and the device capture goes beside it at
the same 390 scale. Everything except the icon outlines is read out of the
snapshot; the outlines are the app's own, and the harness names each one.

Measured off those two images at 390/420dpi, the painted control lands on the
frozen rectangle: 155–235 × 442–522 in the reference, 156–234 × 442–521 on the
device, the 1-unit shortfall being anti-aliasing from downscaling a 1024px
screenshot. Header, swipe row, album art, both text lines and the `like` slot
line up to the same tolerance in both themes.

Two differences are deliberate and deferred: the app reserves the header's
trailing overflow slot and draws nothing in it, and it keeps its History entry in
the frozen `dislike` slot, so the right-hand **glyph** differs. Its **colour** no
longer does — the frozen row defines that slot unambiguously and identically on
both pages, so the slot takes `player_control_action` while the glyph and the
behaviour stay the app's until the phase that owns them.

### Mini Player, re-verified

No change was needed and none was made: the B2 contract holds live on **HOME,
COLLECTION and ABOUT US**, on both images. A cold launch with nothing selected
shows no pill on any section; starting MYATA brings it up on all three and never
on PLAYER; pausing and resuming leave it up; it survives navigation between
sections and an Activity relaunch with the session alive; and a force-stop, which
takes the service with it, returns to no pill. On API 24 the emulator image cannot
reach the stream host, so the session exists there with nothing ever playing —
which is the `isPlaying`-is-not-a-session distinction demonstrated by accident.
`MiniPlayerContractTest` already covers all of this, step 8 included, so no test
was added.
