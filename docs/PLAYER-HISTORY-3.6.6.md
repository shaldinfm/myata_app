# PLAYER Broadcast History — 3.6.6 (Phase C)

The inline `Broadcast History Section` on PLAYER, migrated to the FINAL frozen
design. Measured from
`tools/figma-export/canonical/figma-canonical-{light,dark}-final.json`:
**light `2396:30784`, dark `2444:18288`**, inside `PLAYER` `2396:30727` and
`PLAYER_dark` `2444:18225`.

[PLAYER-3.6.6.md](PLAYER-3.6.6.md) covers the upper section, which this phase
does not touch.

## The production history path this reuses

Nothing here fetches, stores or orders history. The section is a second **view**
of the state the History bottom sheet has always read:

| | |
|---|---|
| `data/HistoryTrack.kt` | `artist`, `track`, `played_at`, `played_at_formatted` |
| `data/HistoryRepository.kt` | `GET radiomyata.ru/api_track_history.php?stream=&limit=` |
| `StreamsViewModel.historyTracks` / `.historyLoading` / `.loadHistory()` | the single state |
| `HistoryBottomSheet` + `HistoryAdapter` | the other view, untouched |

`loadHistory()` keys off `currentStreamLive`, clears when the stream changes and
is called by whichever pager page **is** the current stream — so a swipe costs one
request, not three, and chronology is the API's own ordering, unchanged.

One thing did change in the ViewModel, because it disagreed with itself: the
fetch took `getHistory`'s default `limit` of **20** and the result was then
trimmed with `take(30)`, so the 30 was unreachable and the real ceiling was a
default nobody had written down. Both ends now read `HISTORY_LIMIT = 30`.

## The frozen section

All offsets are inside the section; it sits at `(16, 552)` of the 1022-tall
frame, which is **473** in page coordinates — the same convention the blocks above
it use — and 30 below where `Controls` ends. Geometry is identical on both pages.

```
      17  padding
      32  heading      "История эфира"
      16  gap
      74  History Item 1..3        (a minimum; real content grows it)
      16  gap
      54  "Показать ещё"
      17  padding
   ------
     374  with three one-line rows and a fourth entry behind the button
```

A row, 13 of padding around a 48-tall content row:

```
   0   13        55         67             119                311
   |   | 42      |  12      | 40×40 r6     |  12   title 28   |
   |   | time S  |          | artwork      |       artist 20  |
```

The time column is 42 rather than the frozen 38.98, and START rather than RIGHT;
both are covered below. Everything after it sits 3.02 further in than the frozen
frame, and nothing sits under the text.

The row *has* `cornerRadius 8` and a 1px `INSIDE` stroke, and paints neither: the
stroke is `#000000` at opacity 0 and there is no fill. It is 13 of padding and
nothing else, which is why no row-corner dimen exists — a corner on a surface that
is never drawn is not reproducible.

## Colours

Every value below is read off both canonical pages.

| part | light | dark | resource |
|---|---|---|---|
| section fill | `#FFFFFF` | `#142D47` | `surface` |
| section + button stroke | `#E1E3E4` | `#466D8F` | `outline` |
| heading, title | `#191C1D` | `#F5F7FA` | `text_primary` |
| time, artist | `#42474E` | `#B3C4D1` | `text_secondary` |
| artwork plate | `#E1E3E4` | `#E1E3E4` | `brand_player_history_artwork_placeholder` |
| button fill | `#F8F9FA` | `#1C3F5F` | `player_history_more_container` |
| button label | `#003056` | `#5FD9B4` | `player_history_more_label` |

The last three are the only ones that could not take an existing role.

The **artwork plate** is identical on both pages, so it joins the fixed brand
colours and gets no night variant. It reads as `outline` in light purely by
coincidence of the palette — dark `outline` is `#466D8F` while this stays
`#E1E3E4` — which is exactly why it is a literal and not that token.

The **button** needs a declared pair at both ends. Its fill is exactly
`background`/`surface_container` in light, but dark's `#1C3F5F` is neither —
`surface_container` is `#1C4771` there and `surface` is `#142D47`. Its label is
exactly `text_heading` in light and exactly `primary` in dark, and each of those
roles is wrong in the other theme.

## Typography

By role, from [TYPOGRAPHY-3.6.6.md](TYPOGRAPHY-3.6.6.md), which names Broadcast
History explicitly.

| | frozen | app |
|---|---|---|
| heading | 24/32 Bold | `Montserrat.Bold.24_32` — section headings are Montserrat at the frozen weight |
| time | 14/20 Regular | `Onest.Regular.14_20`, START aligned — see below |
| title | 17/28 Regular | `Onest.Regular.17_28` |
| artist | 14/20 Regular | `Onest.Regular.14_20` |
| button | 22/28 Regular | `Montserrat.Medium.22_28` |

The button's weight is not a liberty: the canonical snapshot still carries Muller,
and the typography FINAL moved **button and card-heading weights Regular → Medium**
at 22px on the way to Montserrat. Every frozen text box is applied as a
`minHeight` for the reason B2 measured — Figma honours a declared line height
literally and Android would clip to it.

## Artwork

`HistoryTrack` has no artwork field, and the frozen `Album Art` node carries a
real `IMAGE` fill set to `CROP`. The cover is therefore **derived**, not stored:
`ArtworkRepository` — the app's single source of truth for artwork, already held
by `StreamsViewModel` for the now-playing metadata, and already backed by an
in-memory cache the two now share — resolves one from the row's artist and track.
The frozen `Background` plate is what shows while that is in flight and what stays
if nothing is found.

Only **bound** rows ask, so the reveal step is what bounds the fan-out: three
lookups on open, at most ten per tap.

`historyArtworkUrl` states no dispatcher of its own. `ArtworkRepository.fetchArtwork`
switches to IO around its blocking body (#45), so a wrapper here would dispatch to
the dispatcher the callee is about to move to regardless — and it would cost a cache
hit the round trip the repository avoids by reading its cache ahead of its own
switch. An earlier revision of this branch did wrap the call site, back when
`fetchArtwork` blocked on whatever thread its callers happened to be on; that is
fixed at the source now.

## Deviations from FINAL, and why

### The time column is 42, not 38.98

The frozen box cannot hold a real timestamp. Onest's digits are **proportional**,
and not marginally: `'1'` advances 363 units where `'0'` advances 665, nearly
double. At 14sp that spreads the clock across

| time | width |
|---|---|
| `11:11` — narrowest of the 1440 | 23.87dp |
| `08:11` | 31.72dp |
| `10:01` — what the layout test used to assert against | 32.33dp |
| `08:12` | 34.57dp |
| `08:03` | 39.26dp |
| `00:00` — widest of the 1440 | **40.78dp** |

so 38.98 held some times and cut the last digit off others. It shipped visibly:
rows read `08:0` where the minute was wide, in both themes.

Tabular figures would have been the tidier fix — same advance for every digit,
frozen width preserved — but this font has none. Onest's GSUB carries only
`calt`, `ccmp` and `locl`, so `fontFeatureSettings="tnum"` selects a feature that
is not there and changes nothing.

**42, not 41.** The advances above are not the finished width: `Paint.measureText`
puts `00:00` at **41.38dp** once this font's kerning is applied, so a box sized to
the 40.78 the advances add up to still clipped — which the layout test caught,
because it measures through the view's own paint rather than trusting arithmetic.

It stays a **fixed** width, which is the one place this row does not relax a
frozen number into a minimum: the artwork is constrained to the end of the time
view, so a `wrap_content` column would start each row's artwork at a different x
and trade a clipped digit for a ragged artwork edge down the whole section.
Everything after the time therefore sits 3.02 further in than the frozen frame,
which takes the title column to 192 at the design width.

**START, not RIGHT**, by owner correction. Every timestamp begins on the same
vertical line at 13, so the column reads as a column. This is the other half of
why the box is fixed rather than hugging: a fixed box holds both alignments at
once — the timestamps' left edge and the artwork's — where a self-sizing one has
to give up whichever it is not anchored to.

Bound worth knowing: 42 is dp and the text is sp, so this holds at the default
font scale. A large accessibility font scale will overrun it — as it will every
other fixed box in this frozen row, so it is a property of the design, not of
this number.

### Rows grow; they never truncate

74 is `13 + 48 + 13`, and the 48 is one line of the mock's title over one line of
its artist. Every vertical number is applied as a minimum. This is also the
standing owner decision recorded on `item_history_track.xml`: History rows are
variable height with **no ellipsis**, so a long Russian title adds a line rather
than losing its end. There is no `maxLines` and no `ellipsize` on either line.

## The row carries no service actions

The owner-confirmed FINAL PLAYER reference for these rows is

```
time  →  40dp artwork  →  title / artist
```

and nothing else. Spotify, Apple Music and Yandex Music are **not** part of the
inline section.

An earlier revision of this branch did put all three here, on their own line under
the text, and reasoned at length about which of the mock's numbers to keep while
doing it. That reasoning is withdrawn: it was solving where to fit buttons the
reference does not contain. Removed with them are the three `history_open_*`
strings, the `PlayerHistoryServiceButton` style and the three
`player_history_service_*` dimens — all added by this branch for this purpose, so
none of it outlives the change.

**Nothing stands in for them.** No hidden or `GONE` views, no substitute icons, no
reserved width beside the text and no reserved line beneath it. The layout test
asserts the absence directly, looking up `music_services`, `btn_spotify`,
`btn_apple_music` and `btn_yandex` in the row and requiring all four to be null —
a real check rather than a vacuous one, since those ids still resolve for the
surfaces that do use them.

**The other surfaces are untouched.** `MusicSearchHelper` is unchanged, and the
History bottom sheet (`item_history_track.xml` / `HistoryAdapter`) and Collection
(`item_favorite_track.xml` / `FavoritesAdapter`) keep their service links exactly
as they were. What changed is one surface's row, not the feature.

**What it gives back is the frozen geometry.** With nothing under the text the row
is `13 + 48 + 13` = **74** on one-line content, and the section is
`17 + 32 + 16 + 3×74 + 16 + 54 + 17` = **374** — the frozen figure itself, where
the service line had made it 512. `Показать ещё` stays below the rows.

### The hidden `Ещё` button is not drawn

`Margin > Container > Button` is `visible: false` on both pages.

## "Показать ещё"

Three rows on open — the frozen count — then **ten more per tap**, hidden when
there is nothing left. The frozen frame draws one populated state and one button
and says nothing about the step; ten reaches the 30-entry ceiling in three taps
and is what bounds the artwork fan-out.

It is a projection, not state: `BroadcastHistoryState` reads the ViewModel's list
size and loading flag plus one view-scoped reveal count, and the rows drawn are
the real list cut to it. `BroadcastHistoryStateTest` proves for every history size
from 1 to 30 that each offered tap strictly increases the rows on screen and that
the button disappears exactly when the history is exhausted — a dead control fails
it.

## The `dislike` slot

The control there now scrolls to the section instead of opening
`HistoryBottomSheet`: with the history inline on the same page, a modal copy of it
on top of itself is not a second view of anything. Its glyph and its colour are
untouched, and its content description still names the same thing.

> **Superseded.** The slot holds Dislike now that the reaction model gives it
> something to record - see the note in [PLAYER-3.6.6.md](PLAYER-3.6.6.md). The
> section itself is unchanged and still inline on this page; only the scroll
> shortcut is gone. `ic_history` went with it: the shortcut was its only caller,
> and a drawable with no reference is not a deferred decision the way
> `HistoryBottomSheet` is - that is still a working screen, and still left in
> place. The glyph itself is not lost: `tools/figma-export/code.js` carries its
> SVG inline and names the old path as provenance.

`HistoryBottomSheet` and `item_history_track.xml` are left in place and are now
unreferenced. Deleting a working feature was not in scope; their disposition is a
separate decision.

## States

`LOADING`, `EMPTY`, `POPULATED`. There is no error state, and that is
pre-existing: `HistoryRepository` answers a failed request with an empty list
rather than raising, so "no history" and "no API" are one state to any view of it
and the string has to be true of both.

A refresh over rows that are already up leaves them up — the section is inline on
a scrolling page, and swapping it for a spinner on every poll would move
everything under the reader's finger.

**The error state cannot be isolated on device.** The app gates its whole UI on
`radiomyata.ru/covers/playlists.txt`, the same host the history API is on, so
blocking that host replaces the entire screen with "Не удалось загрузить данные"
and PLAYER is never reached. Verified by blocking the app's UID with `iptables` on
API 24. The state is covered by `BroadcastHistoryStateTest` and its view is
asserted present and wired by `PlayerHistoryLayoutTest`.

## Validation

`PlayerHistoryLayoutTest` measures the section at 320/360/390/412dp in both
themes. Byte-identical on API 24 and API 36 for every anchor:

```
  section@1242px (473dp)   heading 84px (32dp)   row 195px (74dp) at every width
```

It asserts 374 on the page the frozen frame draws — three rows with the button
under them, meaning a fourth entry behind it — and 304 for a history of exactly
three, where the button is correctly gone. It asserts that the row holds none of
`music_services`, `btn_spotify`, `btn_apple_music` or `btn_yandex`, that the row
ends 13 below the artist with no reserved line under the text, and that the title
column is 192 at the frozen width. It also asserts `controls y` at 363, so a
regression in the section cannot move the upper section #42 froze.

On the timestamp column it asserts the fixed 42, START alignment with the text
beginning at the box's own left edge, that all 1440 `HH:MM` values fit when
measured through the view's own paint, and that `00:00`, `08:08`, `10:45` and
`23:59` render unclipped on one line and share a left edge.

On the running app, both themes, real history from the live API:

```
  390dp  section 358dp @552 (+44 status inset)   3 rows, "Показать ещё" offered
         21:06 CARRY ME / BOMBAY BICYCLE CLUB    covers resolved per row
         21:04 4.3 FORTY / KNOX                  rows 120, 120, 120dp
         21:00 EVERGREEN / YOUNG THE GIANT       every line unwrapped
```

`capture-player.mjs` step 6 is now `06-history-inline` and step 7
`07-history-revealed`, which taps the button and records the row count either
side. Mini Player: absent on PLAYER at every step, on both APIs, unchanged.
