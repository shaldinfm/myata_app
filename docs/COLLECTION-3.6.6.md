# COLLECTION — 3.6.6 (Phase B)

How the frozen COLLECTION was derived, what was decided where the source is
silent, and — at length, because it is the part of this screen most likely to be
misread later — what is deliberately **not** migrated yet.

Everything is measured from
`tools/figma-export/canonical/figma-canonical-{light,dark}-final.json`, the FINAL
canonical export, and from
`tools/figma-export/screens-3.6.6/baselines/proposals-{light,dark}-normalized.json`
for the sheets. Node names are quoted so any claim can be re-checked.

## The frozen frames

Four of them — `COLLECTION`, `COLLECTION_dark`, `COLLECTION pusto`,
`COLLECTION pusto_dark` — all 390×740, and **their geometry is identical**. Only
the fills differ between the themes, which is why one layout serves all four.

```
Header - TopAppBar        0 ..  64      "Моя коллекция", trailing overflow
Main                     64 .. 612      padding (0,16,154,16), gap 32
  subtitle               80             "Здесь хранятся ваши сохранённые треки"
  Collection List       132             rows 358x98 r24, gap 16
                                        or one 358x359 empty card
BottomNavBar            664 .. 740      merged in B1
Now Playing Mini Player 586 .. 660      merged in B2
```

Every anchor above is reproduced at 320/360/390/412dp in both themes on API 24
and API 36 — see `CollectionLayoutTest`.

## Bottom clearance is stated, not derived

HOME had to derive its 154 from three canonical boxes because the HOME frame's
own `paddingBottom: 128` is the leftover of a fixed frame that never scrolls.
COLLECTION does not: its `Main` carries `paddingBottom: 154` outright, and the
populated `Collection List` ends at exactly the 458 that padding implies.

```
BottomNavBar     76      canonical, 664..740
gap               4      mini_player_margin_bottom
Mini Player      74      13 + 48 + 13
total           154      and the frozen frame says 154
```

So the number HOME derived is confirmed by a second frame, and the dimension is
now shared: `home_content_bottom_clearance` became **`content_bottom_clearance`**.
Two screens reserving the same band from two copies of the same literal is how
they drift apart later. The system-bar inset is added on top at runtime, because
`MainActivity` adds the same inset to the navigation bar's own padding.

`CollectionLayoutTest` asserts the reserved band actually covers the measured
chrome stack, rather than only that the number is 154.

## Colours

Every colour on the frozen screen is an exact match to a semantic role on **both**
canonical pages, so the screen needed no new palette:

| frozen node | light | dark | role |
|---|---|---|---|
| screen and app-bar background | `#F8F9FA` | `#0F253E` | `background` |
| «Моя коллекция» | `#003056` | `#F5F7FA` | `text_heading` |
| subtitle, artist, empty body | `#42474E` | `#B3C4D1` | `text_secondary` |
| row card and empty card fill | `#FFFFFF` | `#142D47` | `surface` |
| card stroke | `#E1E3E4` | `#466D8F` | `outline` |
| row title, empty title | `#191C1D` | `#F5F7FA` | `text_primary` |

Two exceptions, both deliberate.

**The overflow glyph gets its own pair.** `#42474E` is exactly `text_secondary` in
light; `#F5F7FA` is exactly `text_primary` in dark. No single role is right in
both themes, which is the same situation `player_header_label` is in — and it
carries the same two values today. `collection_header_action` is nonetheless a
separate resource, because the two are equal by coincidence of the palette, not
because one screen's header action is defined as the other's.

**The empty-state illustration stays out of the token system.** Its six colours
are the colours of one drawing: `#6750A4` and `#EADDFF` are Material 3 baseline
values, `#FFD8E4` and `#E74C3C` are the heart, and none of them describes a
surface, a text or an outline anywhere else in the app. Four are identical on
both canonical pages and so have no night variant, by the same contract the fixed
brand colours use; only the two disc rings change between the themes.

The light empty-state body is `#000000` on the canonical page. That is an
outlier — every other secondary line in the same frame, the header subtitle
included, is `#42474E`, and the dark page has this line on `#B3C4D1`, which is
exactly `text_secondary`. It is `text_secondary` here, in both themes.

## The empty-state illustration

One 166×166 drawable at (96,55) in the card. The badge sits entirely inside the
disc's bounding box, so it is placed at (115,102) within the same drawable rather
than being a second asset. Every box is verbatim from the export:

```
disc / outer ring   139.79 at (13.11,13.11)   -> r 69.895 at (83.005,83.005)
disc / inner ring   111    at (27,27.5)       -> r 55.5   at (82.5,83)
disc / hub           54    at (56,56)         -> r 27     at (83,83)
disc / spindle       14    at (76,76)         -> r 7      at (83,83)
badge / plate        46    at (115,102)       -> r 23     at (138,125)
badge / heart     23.8x21.69 at (126.1,115.22)
```

Two things are authored rather than measured, and both are forced. The canonical
exporter records a vector's box and its fills but never its geometry — there is
no `vectorPaths` in `canonical/code.js` — so no path data for any of these six
exists in the repository.

**The shapes are circles.** Four of them are the rings of a record and are
unambiguous. The 46×46 badge plate is not: its silhouette has no path data
either, and the one preview that renders this frame
(`tools/figma-export/dark-theme/previews/dark-collection-empty.png`, an earlier
revision) does not draw it at all. A circle is what the rest of the illustration
is made of and what a square bounding box with no other evidence gives.

**The heart is Material's `favorite`.** This is not a guess: `Frame 5` also holds
a hidden second heart at exactly **20×18.35**, which is the bounding box of
Material's `favorite` path to the decimal. That path is placed through a group
transform mapping its source box (2,3)–(22,21.35) onto the visible heart's
23.8×21.69 at (126.1,115.22) — scale 1.19 / 1.182016, translate 123.72 / 111.674.
Both corners land exactly.

## Where the frozen boxes could not be taken literally

Three places, all the same underlying cause, and all resolved the way B2 resolved
it on the Mini Player title rather than by reproducing a defect.

**Text boxes shorter than their line height.** The frozen row title is an 18 box
against a 28 line height and the artist a 16 box against 20; the frozen empty
title is 18 against 28. Figma can draw that because it has no font padding and
honours a declared line height literally. Android cannot without clipping. Every
one of these is applied as a **minHeight**, so the token's real line box is
honoured and no glyph is cut.

**The subtitle needed a minHeight to hold its own anchor.** A token applied to a
single line does not produce a view of that height: `MyataTypography` delivers the
line height as line *spacing*, deliberately, because nothing else is honoured
below API 28 — and `StaticLayout` adds no spacing after the last line. Measured,
the subtitle came out at the font's own 17.9dp and pulled the list 2dp above its
frozen 132. `collection_subtitle_min_height` is what puts it back.

**The empty-state text blocks are centred on their box centres**, 250 and 284,
not anchored on their tops. Anchoring on the tops only works if the boxes are
reproduced literally, and they cannot be. Centred, the title takes its real 28 and
lands 236..264 while the body keeps 264..304 — they abut exactly, and both frozen
centres are preserved.

## The empty-state body does not take the frozen 191 width

It hugs its two lines instead. 191 is a **Muller** measurement; Onest sets the
same string wider, and pinning 191 forced a third line — which breaks the
two-line block the frozen frame draws far more visibly than a few dp of width
does. The copy carries its own line break, so hugging keeps exactly the frozen
break and the frozen centre while letting the replacement font have the width it
needs. This is the licensing-driven metric delta
[TYPOGRAPHY-3.6.6.md](TYPOGRAPHY-3.6.6.md) records elsewhere, not a layout choice.

## Export moved into the header overflow

The frozen `Экспортировать список` button is `visible: false` on **all four**
frames. The only export affordance in the FINAL design is the 3-dot overflow,
whose menu is the proposals frame `collection-overflow-menu` — 260×160, r20, with
exactly two rows, `Экспорт в TXT` and `Экспорт в CSV`.

The screen shipped those two actions as always-visible pills inside the container
card the frozen design removes. Deleting the card without relocating them would
have removed a shipping feature; keeping them would have contradicted the frozen
header. **Owner GO, option A:** relocate them now, restyle later.

Everything behind the actions is untouched — the same SAF
`ACTION_CREATE_DOCUMENT` intents, the same MIME types and filenames, the same
UTF-8 BOM, the same CRLF and `;`-quoted CSV formatting in `FavoritesViewModel`,
the same two toasts. Only where the user reaches them has moved. The GO covers
relocating the existing actions and nothing else; no other Phase F work is
started here.

The overflow is **hidden while the collection is empty**, which is what the
frozen empty frame does — its `Button:margin` is `visible: false`, because there
is nothing to export. That is the same condition the two pills used to express by
going disabled.

## The populated row is now FINAL — F1 and F3

Everything in the table below was an accepted, temporary deviation through Phase
B. **F1 and F3 close all but two of them.** What the row was, and what it is:

| | Phase B | now |
|---|---|---|
| cover | none | the frozen 64×64 r20 at (17,17) |
| row height | content | the frozen 98, as a minimum |
| stream badge | «МЯТА» / GOLD / XTRA pill | gone |
| service actions | four inline 32dp buttons | four rows on the per-track sheet |
| removal | a permanent 18dp cross | `Удалить из коллекции` on the same sheet |
| trailing control | none | the frozen 40×40 `arrow_forward` ring |

### The cover did not need a Room migration

Phase B recorded that it did. Only half of that was ever true. `FavoriteTrack`
stores `id, artist, track, stream, addedAt` and has nowhere to put an artwork
URL — but `ArtworkRepository.fetchArtwork` is keyed on **artist and track**,
which the entity does store, so the cover is a *view* of what the collection
already holds rather than a second store beside it. Nothing about the schema
changes.

The fetch is `PlayerHistoryAdapter`'s, unchanged: the request is the caller's,
passed in as `artworkFor`; the answer is dropped if the holder has been rebound;
the request is cancelled when the row is recycled; the bare plate shows while it
resolves and stays if nothing is found. The one real consequence is that
COLLECTION now touches the network per row, where before it was wholly offline —
**owner decision**, taken with that stated.

With something to draw, the frozen 98 is a measurement again, and
`CollectionLayoutTest` asserts it. It is a *minimum*: a second artist line grows
the row, and the cover and the action stay on the row's own 17 padding rather
than drifting with the text.

### The sheet is where the five actions went

`Bottom Sheet / Действия с треком`, the FINAL `collection-track-sheet`, 358×447
r28 on `menu_surface` with a 1px `menu_outline` stroke. Its own spec note says
what it is for: *"Replaces the per-track inline button. Four services, a divider,
then the destructive action last and separated."* The owner-confirmed order is
Spotify, Apple Music, YouTube, Яндекс Музыка, divider, `Удалить из коллекции`.

Nothing behind any of them moved. The four services call exactly the
`MusicSearchHelper` functions the inline buttons called, with the same artist and
track; `MusicSearchHelper` is untouched. The third row reads **YouTube** and not
"YouTube Music" because `openYouTube` searches youtube.com — the sheet names the
destination the user actually lands on.

The icons are the **canonical generic disc**, the same glyph on all four rows,
tinted `primary`; the rows are told apart by their labels. That is what both
canonical pages draw, and it is the owner's selection over the brand marks the
proposal references.

The sheet opens **expanded and never collapses**. A bottom sheet's default is
`STATE_COLLAPSED` at a peek height of 9/16 of the window, which on the API 24
device's 1080×1794 app window left the destructive row below the fold, reachable
only by dragging the sheet up. That default is right for a sheet whose content is
a long list and wrong for five fixed actions — the point of the surface is that
the row's one control shows everything it offers.

Two wrong diagnoses were tried on the device before that one, and are recorded so
they are not tried again. It is **not** a missing window inset: `dumpsys` reports
`app=1080x1794` on a 1080×1920 display, so the app window already excludes the
navigation bar and the bottom inset is zero. And adding bottom margin to the card
does nothing, because that margin lives inside the scrolling content and simply
extends it further below the fold.

### Removal, and undo

The row's permanent cross is gone; removal is the sheet's last row, on `error`.
After it, a Snackbar offers `Отменить` — owner's copy.

**Undo needed no new persistence.** The entity that came out of the list is the
undo record: `FavoriteTrack` carries artist, track, stream and `addedAt`, so
re-inserting the same instance brings all four back, and since the list is
`ORDER BY addedAt DESC` the original `addedAt` is what puts the row back where it
was rather than at the top, as a fresh save would. No undo table, no schema
change, no new DAO method — `FavoritesViewModel.restoreFavorite` is one `insert`.
The feedback report mirrors the removal's: UNLIKE out, LIKE back. Removing a track
reports `UNLIKE` — the Like is withdrawn and the reaction returns to neutral — and
never `DISLIKE`, which is what it used to send and which recorded everyone who
tidied their Collection as disliking the track. The undo reports `LIKE` only if the
row really came back; an insert that `OnConflictStrategy.IGNORE` skipped changed
nothing and reports nothing.

The autogenerated `id` is deliberately **not** part of that guarantee. It came
back unchanged in the end-to-end run — the harness reads the favorites table
before the removal and after the undo, and reported `1 -> 1` in both themes — but
was reassigned in an ad-hoc run that had seeded the row outside the app, so it
depends on what SQLite has to reuse. Nothing relies on it either way: the id is
not persisted anywhere else, `removeFavorite` re-reads the entity it is handed,
and a restored row being a new item to the `DiffCallback` is the right animation
for a restore in any case. **`addedAt` is what carries the position, and it was
preserved in every run** — that is the field the guarantee rests on, and the one
the harness asserts.

> **Storage superseded.** The paragraphs above describe the `favorites` table as
> 3.6.6 shipped it. Persistence is now `track_reaction`, the three-state reaction
> model (NEUTRAL / LIKED / DISLIKED), where the Collection is the LIKED rows
> ordered by `liked_at`. Everything the reader sees is unchanged, and so is the
> shape of the Undo guarantee — only its mechanics moved: removal sets the row to
> NEUTRAL instead of deleting it, undo returns it to LIKED carrying its original
> `liked_at`, and identity is `TrackKey` v1 rather than the autogenerated `id`,
> which no longer exists. `AppDatabase` migration 1 → 2 carries existing
> collections across; `ReactionMigration` documents the merge rules for legacy
> rows that turn out to be one track under the key.

The Snackbar is anchored on the Mini Player when there is one and on the
navigation bar when there is not, so it clears the same chrome stack the list
reserves its 154 for.

No COLLECTION frame draws a Snackbar, so its appearance is the design system's
own snackbar primitive rather than an invention or a Material default:
`spec/primitives.mjs:286` — 358×56 r12 on `surface_container` with a
`menu_outline` stroke, the message on `text_primary` and the action on `primary`,
inset on the same 16 margins as everything else. The two frames that use that
primitive are sleep-timer proposals; taking the established pattern is what stops
the first snackbar that ships being a shape of its own.

`isAllCaps` is turned off on the action, because the Material button style
renders «Отменить» as «ОТМЕНИТЬ» and nothing else in the app shouts. The first
live QA pass caught exactly that — it found the message and not the action.

### Two new colour roles

`menu_outline` is the stroke of every menu and sheet surface. It is **not**
`outline`: light draws #1C3F5F here against `outline`'s #E1E3E4, consistently on
both the canonical sheet and `Menu / Плеер`. In dark the two coincide at #466D8F,
which is exactly why the role has to be named — they are equal there by
coincidence of the palette, not by definition.

`error` (#B3261E / #FF8A80) has one user, the destructive sheet row. It is graded
**v2 PROPOSED, confidence WEAK** in `spec/tokens.mjs`: the dark value is the dark
UI KIT's `Token / error` swatch, the light value is *proposed*, and no canonical
screen shows an error state. `values/colors.xml` records that grading, because
the light value is the one to re-confirm when a canonical error state exists.

### Two drawables the row left behind

`rounded_banner_bg` (the stream badge's pill) and `ic_youtube` (its inline
service button) lost their last user with the FINAL row and have been **deleted**
— owner GO, taken as a follow-up to this migration rather than inside it, because
AGENTS.md keeps cleanup deletion behind an explicit approval.

Three neighbours were deliberately kept. `ic_spotify`, `ic_apple_music` and
`ic_yandex` are still drawn by `item_history_track.xml`, the History bottom
sheet's row. And `dismiss_cross`, which this row's delete control used, is still
`exo_notification_stop` in `strings.xml` — the playback notification's stop
glyph — so it stays.

`app/src_backup_best_version/` also names `ic_youtube`. That reference is
untouched and unaffected: the folder is not a source set — `app/build.gradle`
declares none, and it sits beside `app/src` rather than inside it — and it
carries its own copy of the drawable, so it resolves within its own tree.

## What is still deliberately not migrated

| | why, and who owns it |
|---|---|
| **truncation retained** | the frozen row specifies neither ellipsis nor `maxLines`, only fixed boxes. The screen has always ellipsised at one line for the title and two for the artist. Changing that is behaviour, and whether Collection adopts History's variable-height, never-truncate rule is still a separate decision. `CollectionLayoutTest` asserts F3 did not take it by accident either. |
| **PopupMenu styling** | unchanged by F1/F3. **F2** replaces it with the frozen 260×160 r20 `Menu / Коллекция` surface on `menu_surface`. The wording and the actions are already the frozen ones, and the export behaviour behind them is untouched here. |
| **no profile control** | the frozen `COLLECTION pusto` header carries a 40×40 circular control in the trailing slot. It is the same node HOME and ABOUT US carry, it opens the profile, and it is **Phase G** — deferred here exactly as `fragment_main.xml` defers it. |

## The trailing action's 2dp

Figma left-aligns a 20-wide button inside a 32.02-wide `Button:margin` wrapper
whose right edge is on the 16 margin, leaving 20 of slack and putting the glyph
centre at x=352. A 48dp touch target — the platform minimum, and what the slot
needs to be tappable at all — with its right edge on the same 16 margin puts that
centre at **350**. Two dp, against a wrapper that is slack by construction and
against HOME and ABOUT US, which put their own trailing action flush on the
margin.

## Empty-state copy

The frozen body reads «Сохраняйте понрави**вше**ся треки», which is a typo in the
design source. Owner decision: ship the correct «понравившиеся». The line break is
the frozen one and is kept, because the copy is drawn as two centred lines.

## Mini Player

**Untouched.** `MiniPlayerVisibility.SCREENS` already contains `"favorites"`, so
the B2 contract held on this screen before this migration and holds after it:
hidden before a playback session exists, visible once a stream is selected, still
visible while paused, absent only on PLAYER, body tap opens PLAYER. All of it is
re-verified here — `MiniPlayerContractTest` and `MiniPlayerLayoutTest` both pass
unchanged, and the live harness walks the gate on COLLECTION itself.

The only thing this screen owes the pill is the 154 of clearance above.

## Validation

| | |
|---|---|
| `CollectionLayoutTest` | instrumented — the frozen anchors for both states and the row, at 320/360/390/412dp in both themes, plus the colour roles, the card surface, the clearance against the measured chrome stack, and a long-Russian-metadata pass asserting the retained 1/2-line truncation and no clipping |
| `TypographyProbeTest` | the migrated surfaces now name the subtitle and the two empty-state blocks instead of the removed export pills |
| `TypographyWidthSweepTest` | the same three, swept across every shipping width |
| `MiniPlayerContractTest`, `MiniPlayerLayoutTest` | re-run unchanged: the clearance change is the one place this PR meets B2 |
| `HomeLayoutTest`, `PlayerLayoutTest` | re-run because the clearance dimension was renamed |
| `CollectionTrackSheetLayoutTest` | instrumented — the FINAL sheet's anchors at 320/360/390/412dp in both themes: the floating 358 r28 surface and its two roles, the header on the frozen 40 and 78, all five rows in the owner-confirmed order with their labels and colours, the 58 pitch, the divider's 12 either side, and a long-title pass proving the header grows the sheet instead of running into the first row |
| `tools/qa/phone/capture-collection.mjs` | the live app: both states reached through the real database, the overflow gate, the export menu, the exported files read back off the device, the Mini Player gate on COLLECTION, the last row clear of the pill, and — new with F1/F3 — the per-track sheet carrying all five rows, removal from its last row, and Отменить putting the same row back |

Live-app evidence is in [tools/qa/phone/collection/](../tools/qa/phone/collection/).

> **The full instrumented suite still cannot complete on API 24**, for the
> pre-existing reason B2 recorded: `screen0..9` are 1080×1921 PNGs in a
> density-less `drawable/`, so each `MainActivity` launch decodes a window
> background upscaled past 57 MB and the heap runs out after a few launches. The
> Collection classes are run as their own instrumentation run on API 24, exactly
> as the Mini Player classes are.

### One pre-existing failure corrected in passing

`TypographyProbeTest` named `main_author` as the Black 24/24 slot and `main_song`
as the Regular 18/18 one. PR #40 corrected PLAYER — which had the artist in the
Black slot and the title in the Regular one — but did not move this table with
it, so the test has been failing on `main` since. The two entries are swapped
here because the suite has to pass to be evidence. Nothing on PLAYER is touched.
