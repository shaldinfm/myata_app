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
     120  History Item 1..3        (the frozen mock's row is 74 - see below)
      16  gap
      54  "Показать ещё"
      17  padding
   ------
     512  with three one-line rows and a fourth entry behind the button
```

A row, 13 of padding around a 48-tall content row with the service links under
it:

```
   0   13        51.98      63.98          102.98             311
   |   | 38.98   |  12      | 40×40 r6     |  12   title 28   |
   |   | time R  |          | artwork      |       artist 20  |
   |                                    12   service links 34 |
```

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
| time | 14/20 Regular, RIGHT | `Onest.Regular.14_20` |
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

The `withContext(Dispatchers.IO)` is at the call site rather than in the
repository on purpose. `ArtworkRepository.fetchArtwork` is `suspend` but performs
blocking OkHttp calls **without switching dispatcher**, and its two existing
callers pass it whatever context they are on. Repairing that is a change to a
shared path with a foreground service on the other end of it, not something to
fold into a screen migration; this call site states the context it needs and the
pre-existing issue is left where it is.

## Deviations from FINAL, and why

### Rows grow; they never truncate

74 is `13 + 48 + 13`, and the 48 is one line of the mock's title over one line of
its artist. Every vertical number is applied as a minimum. This is also the
standing owner decision recorded on `item_history_track.xml`: History rows are
variable height with **no ellipsis**, so a long Russian title adds a line rather
than losing its end. There is no `maxLines` and no `ellipsize` on either line.

### The service links are under the text, not in the trailing slot

The frozen slot holds two buttons named *"Button - Mock platform icons using
generic material symbols for layout"* — placeholders, self-declared. The app has
three real ones, each in the frozen 22×34 button box, 8 apart, which is the gap
the frozen pair itself uses: its second button starts at x=30 against a 22-wide
first. Three of them measure 82.

**Beside the text, 82 did not fit.** The title column came out at:

| width | row | text column, links beside it |
|---|---|---|
| 412dp | 346 | 135 |
| 390dp | 324 | **113** |
| 360dp | 294 | 83 — about nine characters to a line |
| 320dp | 254 | 43 — `CRYOGEN` breaks over three lines |

At 113 real names wrapped hard on live history — `LOADED WITH PEARLS` over three
lines, `SOMEDAY, SOMEWHERE` over two — and truncating them was ruled out by the
no-ellipsis decision above. So by owner decision the links moved **under** the
text at every width, real content taking precedence over the mock's two-icon slot.
The column is **195** at the frozen width and the same names fit on one line;
measured live, all three rows come out at exactly 120.

What that costs is the frozen 74: the row is `13 + 48 + 12 + 34 + 13` = 120 on
one-line content, and the section 512 rather than 374 with three rows in it. What
it keeps is every other frozen number, all three working service links, the frozen
trailing edge they align to, and **one layout at every width** — the
`layout-w390dp/` variant an earlier revision of this PR needed is gone.

The frozen slot could not have been taken literally in any case. On `History
Item 1` (`2399:31072`) the text column HUGs to 169, which pushes the trailing
container's right edge to **336.15** inside a row whose content ends at **311**.
That row overflows itself in the frozen frame, which is what a mock does and a
real row cannot.

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
  section@1242px (473dp)   heading 84px (32dp)   row 316px (120dp) at every width
```

It asserts 512 on the page the frozen frame draws — three rows with the button
under them, meaning a fourth entry behind it — and 442 for a history of exactly
three, where the button is correctly gone. It asserts the links sit below the text
and 12 clear of it, and that the title column is 195 at the frozen width. It also
asserts `controls y` at 363, so a regression in the section cannot move the upper
section #42 froze.

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
