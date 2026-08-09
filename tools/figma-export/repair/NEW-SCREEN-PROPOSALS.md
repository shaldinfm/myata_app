# Design proposals — Full Broadcast History, Collection actions, Player menu

Screens and interactions where **real shipping Android functionality has no canonical
design**, plus one cleanup. None of this is in the repair plugin's Apply set: repairing a
node and designing a screen are different activities, and the second needs a designer.

Everything below is written from the actual app code, not invented.

---

## 1. Full Broadcast History

### What ships today

`HistoryBottomSheet.kt` — a `DialogFragment` using `Theme.MusicPlayerApp.BottomSheet`,
opened from the history button in the player (`MyataStreamFragment.kt:222`). It calls
`vm.loadHistory()`, which fetches the current stream's history and keeps **the most recent
30 entries**. Per entry: time, artist, track title, artwork. It reloads on stream change
and exposes a `historyLoading` flag.

### What Figma has

The Player carries a **Broadcast History Section** with three rows and a "Показать ещё"
button. That button is the entry point, and nothing designs where it leads.

### Proposal — both themes

Reuse what already exists rather than inventing:

- **Container**: the existing `Bottom Sheet` pattern — 358 wide, radius 28, drag handle
  40×4, `surface` fill, `outline` stroke. The `Bottom Sheet / Найти трек на стриминге`
  frame is the template.
- **Title**: `Radio Myata/screenTitle` (Muller Medium 24/32) — "История эфира".
- **Rows**: the **existing History Item component, unchanged** — time
  (`Radio Myata/timestamp`, Muller Regular 14/20), artwork 40×40 radius 8, title
  `Radio Myata/listTitle`, artist `Radio Myata/miniPlayerSubtitle`.
- **Length**: 30 rows, matching `take(30)`.
- **Tokens**: `surface`, `textPrimary`, `textSecondary`, `outline`, `divider`.

**States that must be drawn and currently do not exist anywhere:**

| State | Why it is needed |
|---|---|
| Loading | `historyLoading` already exists in the view model |
| Empty | A newly started stream has no history |
| Error | `HistoryRepository` can fail; today the user sees an empty sheet |

**Open question for the designer:** the Player already shows three rows inline. Does the
sheet repeat them or continue after them? The app repeats them.

---

## 2. Collection actions — export and per-track

### What ships today

`FavoritesFragment` has `btnExportTxt` and `btnExportCsv`. `FavoritesAdapter` gives every
row five inline controls: `btnDelete`, `btnSpotify`, `btnAppleMusic`, `btnYandex`,
`btnYouTube`. **None of this appears in any design.**

### The problem with the current arrangement

Five inline buttons per row is a lot of permanent affordance for actions used rarely, and
it does not match the canonical language — the Player puts secondary actions behind a
`⋮` menu (`Menu / Плеер`), and the canonical Collection header already shows a `⋮`.

The design should not preserve the current inline layout simply because Android does it
that way today.

### Proposal

**Per-track actions → a bottom sheet, opened from the row.**
Reuse `Bottom Sheet / Найти трек на стриминге` verbatim — it already lists Spotify, Apple
Music, YouTube Music and Яндекс Музыка, which is exactly four of the five actions. Add one
destructive row, "Удалить из коллекции", using `error` for label and icon, separated by a
`divider`. The row itself keeps a single tap target for play/open.

This deletes four buttons per row from the screen and reuses a component that already
exists in both themes.

**Export → the Collection header `⋮` menu.**
Add `Menu / Коллекция`, copying the `Menu / Плеер` component, with two rows:
"Экспортировать в TXT" and "Экспортировать в CSV". This is where the canonical language
already puts screen-level secondary actions.

**States to draw:** export disabled when the collection is empty (the app does not handle
this today), and a completion confirmation, since the result is a file the user has to go
and find.

---

## 3. Player menu cleanup — exact proposed change

`Menu / Плеер` currently contains four rows:

| Row | Status |
|---|---|
| Найти трек | ships (music-service sheet) |
| **Таймер сна** | **no implementation anywhere in `app/src`; out of 3.6.6 scope** |
| **Сообщить о проблеме** | **no implementation; `FeedbackRepository` is track LIKE/DISLIKE, not problem reporting** |
| История эфира | ships (`HistoryBottomSheet`) |

### Proposed change

Remove the two rows that have no product behind them, on **both** pages:

- `Menu row / Таймер сна`
- `Menu row / Сообщить о проблеме`

The menu frame is 206×264 with four 48-row entries; removing two makes it 206×168. The
remaining two rows keep their order and styling.

**Not applied.** This is a product decision, not drift repair, so it stays out of the
repair plugin until you say otherwise. The alternative is equally valid: keep the rows and
put both features back into scope.

---

## Not covered here

The auth / profile / settings screens in PR #23 remain **unapproved concepts** and are not
part of any proposal above.
