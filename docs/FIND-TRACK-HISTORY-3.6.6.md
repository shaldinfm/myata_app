# Найти трек and История эфира - 3.6.6 (G4b)

The two PLAYER flows the frozen `Menu / Плеер` has named since G2 and the app did
not have: **Найти трек** (a bottom sheet of streaming searches for the current
track) and **История эфира** (a full-screen history with its own four states).

Source of truth: `Дизайн Приложения ФИНАЛ.fig`, decoded read-only (the same
`fig-kiwi` decode G4a used - 265,954 nodes). Every frame below was read from the
file, not from the proposals spec that generated it; where the file and the spec
disagree, the file is what ships and the difference is noted.

| frame | light | dark |
|---|---|---|
| `Menu / Плеер (таймер активен)` | 2517:2094 | 2517:3061 |
| `find-track-sheet` | 2517:2524 | 2517:3491 |
| `history-content` | 2523:23 | 2517:3296 |
| `history-loading` | 2517:2420 | 2517:3387 |
| `history-empty` | 2517:2509 | 2517:3476 |
| `history-error` | 2517:2547 | 2517:3514 |

G4a is closed and nothing it decided is reopened here. In particular the menu
keeps the owner's compact row, and the history rows keep G4a's multiline
typography correction.

## The menu: the frozen four

```
1  Найти трек            G4b   FindTrackSheet for the current track
2  Таймер сна            G2    SleepTimerSheet               (unchanged)
3  Сообщить о проблеме   G3    report_problem                (unchanged)
4  История эфира         G4b   broadcast_history
```

Same card (260, r20, `menu_surface`, 1px `menu_outline`, no shadow), same 48 rows,
same 4 gaps, same 10 / 10 padding, same anchor. There is no four-row geometry of
its own: the menu is `wrap_content` over the one row spec, so it measures
`10 + 4*48 + 3*4 + 10 = 224`, and 172 in a build with no report endpoint (row 3 and
its gap go). The frozen 50dp bottom band is not restored.

Glyphs are the file's. Row 4 is `Icon / doc`, the same stroke outline (blob 50165)
as `Menu / Коллекция`'s export rows - so `ic_menu_doc`, measured equal. Row 1 is
a node still **named** `Icon / disc`, but the FINAL file draws a **magnifier** in
it; `ic_player_overflow_find_track.xml` is that node's fill geometry verbatim.

## Найти трек: one sheet, three doors

The file has one find-track pattern, and its note says so: "shared by Broadcast
History, the player menu and Collection. Collection adds a divider and a
destructive row below these four; nothing else differs." So there is one of it:

| | |
|---|---|
| `include_find_track_rows.xml` | handle, title (the track), subtitle (the artist), the four service rows with G4a's decoded glyphs |
| `FindTrackRows` | binds the header and wires each row to its existing `MusicSearchHelper` function |
| `FindTrackSheet` | PLAYER and History: the include, then 24 of padding - the frozen 366 |
| `CollectionTrackSheet` | the same include, then its own divider and `Удалить из коллекции` - unchanged 447 |

`MusicSearchHelper` is untouched: every URL, every query string, and the
`ACTION_VIEW` hand-off that an installed app or the browser answers. The removal
stays in `CollectionTrackSheet` / `FavoritesFragment`; nothing shared knows it
exists.

**The sheet is a snapshot.** It is handed one `FindTrackQuery` when the row is
tapped and holds nothing else. If the stream moves on while it is open, the sheet
does not retitle itself, and every row searches exactly the words it shows.

**Metadata unavailable.** `StreamsViewModel.nowPlayingQuery()` returns null for
the placeholder pair or a blank half (the same placeholder rule the history
projection already used). The menu row is then drawn in its place but disabled
(0.38), and the tap re-reads the track, so a forced click opens nothing.

## История эфира: one destination, four frames

`broadcast_history`, pushed from the player with `launchSingleTop`, and
`PlayerFragment` refuses to navigate from anywhere but the player - two taps push
one screen. No bottom bar (MainActivity) and no Mini Player (`NavScreen.PUSHED`,
the G4a default). Back pops to the player.

Which frame is up is `HistoryScreenState`, a pure projection:

1. rows -> **content** (a refresh in flight, or a failed one, keeps them: "Retry
   re-requests; it does not clear a cached list");
2. nothing, request in flight -> **loading** (retry from error included);
3. nothing, last request failed -> **error**;
4. otherwise -> **empty**.

### There is still one history backend

The screen reads `StreamsViewModel.historyTracks` - the PLAYER inline section's
state - through the same `PlayerHistoryAdapter`, given the full-screen row layout
and the row action. The request, the 30 ceiling, the current-track projection and
the one-request-at-a-time rule are unchanged.

What changed underneath is only what a failure *is*. `HistoryRepository` answered
every failure with an empty list, so empty and error could not differ. It now
returns `HistoryResult.Loaded` / `Failed` (same request, same parsing), and the
ViewModel publishes `historyFailed`. On failure it **keeps** what it already held
rather than replacing it with nothing. The inline PLAYER section does not read the
flag and keeps its three states.

### The row

```
14 | time 42 START | 8 | cover 48 r8 | 8 | title / artist | 8 | ring 40 | 14
```

On a 358 r8 card with a 1px `outline` stroke, everything centred on the row (the
frame's counter-axis is CENTER). One-line rows are the frame's 76. Text is on G4a's
natural 22 / 18 lines, so a wrapped title grows the row by 22, not 28. No
`maxLines`, no ellipsis. The ring is the Collection row's control - the same
`arrow_forward` component (2409:31540) - so it reuses both drawables and the 48
touch target (`RowActionTouchTarget`, extracted from FavoritesAdapter). It opens
`FindTrackSheet` for that row's track.

### Loading, empty, error

Loading is the frame's eight static skeleton rows at the loaded row's anchors -
no platform spinner (G4a: those freeze into a refresh arrow with animations off).
Empty and error are the frames' copy verbatim, with the 96 illustration, a 32 / 12
/ 28 rhythm, and the frame's buttons: outlined `Обновить`, filled `Повторить`.
Both re-request.

## Deviations from the file, deliberate

| | file | app | why |
|---|---|---|---|
| time column | 39 | 42 | Onest has proportional digits; `00:00` is 41.38dp (the PLAYER section's measurement). Cover and text sit 3 further in; the ring does not move. |
| title leading | 28 | 22 | G4a's multiline correction; one-line rows are still 76. |
| footer count | "последние 30 треков" | the real count, in Russian agreement | reads exactly as the frame at 30; a shorter list is not told it is 30. Agreement is chosen by `HistoryFooterText`, not `<plurals>`: Android picks quantities by the device locale, and on an English phone that printed "30 трека". |
| third service label | "YouTube Music" | "YouTube" | pre-existing recorded decision (`collection_sheet_youtube`): the helper opens youtube.com. |
| rows 1-2 padding | 15 / 13 | 14 / 14 | the frame's six untouched rows are 14 / 14. |
| unavailable Найти трек | not drawn | disabled at 0.38 | the file has no such state. |

## Validation

See the G4b report. Unit: `HistoryScreenStateTest`, `FindTrackQueryTest`,
`HistoryRepositoryTest`, `FindTrackSourceTest`. Instrumented:
`PlayerMissingFlowsTest` (the flows end to end on the running app),
`BroadcastHistoryLayoutTest` (the four frames at 320/360/390/412dp, both themes),
`CollectionTrackSheetLayoutTest` (+ the find-track sheet), and
`SleepTimerSurfacesTest` / `ReportEntryPointsTest` updated to four rows.
Screenshots: `G4bCaptureTest` (opt-in, `captureG4b=true`).
