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
| like, history | `text_primary` | | |

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

## Icons

`Controls > play/pause > Container > Icon` is 23×23 — a full icon canvas, unlike
the Mini Player's glyph-tight box — so `ic_player_play` and `ic_player_pause` keep
Material's 24 canvas and render at 23. Same glyphs and same provenance as B2:
`play_arrow` and `pause`, identified by bounding box and origin.

## Playback contract

Untouched. No second state machine, no change to `MediaPlayerService`, and no
speculative fix for the open issue #15. `MyataStreamFragment` keeps the same
observers on the same LiveData; what changed is which drawable and which colour
they apply. Stream switching, swiping, buffering, artwork and metadata updates,
favourites and background playback are all as they were.

The Mini Player stays absent on PLAYER, and its session/visibility contract from
B2 is not modified.

## Validation

| | |
|---|---|
| `./gradlew assembleDebug` | pass |
| `./gradlew lintDebug` | 0 errors, **394 warnings, down from 409** — 0 findings on any PLAYER file |
| `./gradlew testDebugUnitTest` | pass |
| `PlayerLayoutTest` | instrumented — the frozen anchors at 320/360/390/412dp in both themes, the reserved header slot proven non-clickable, worst-case long Russian metadata on one line with no clipping or overlap. **API 24 and API 36, byte-identical.** |
| regression | `MiniPlayerContractTest` and `HomeLayoutTest` re-run on API 24 |
| live | `tools/qa/phone/capture-player.mjs` on both APIs — [tools/qa/phone/player/](../tools/qa/phone/player/) |
| TV | smoke: renders unchanged, `DPAD_CENTER` reaches `STATE_CHANGED state=READY playWhenReady=true`, no crash |

The live run confirms, in both themes: idle → play glyph; tap → `Пауза`; favourite
toggles both ways; swiping reaches GOLD and XTRA with playback following; the
History sheet still opens; pause returns the play glyph; state survives a
background round trip; and **the Mini Player is absent at every step**.
