# Design proposals: Full Broadcast History, Export TXT/CSV

Two screens where **real, shipping Android functionality has no Figma design**. These are
deliberately **not** in the repair plugin's Apply set — repairing an existing node and
designing a new screen are different activities, and the second needs a designer.

Both proposals are written from the actual app behaviour, not invented.

---

## 1. Full Broadcast History

### What the app does today

`HistoryBottomSheet.kt` — a `DialogFragment` styled with
`Theme.MusicPlayerApp.BottomSheet`. Opened from the history button in the player
(`MyataStreamFragment.kt:222`). Calls `vm.loadHistory()`, which fetches the current
stream's history and takes **the most recent 30 entries** (`StreamsViewModel.loadHistory`).
Per entry the app has: time, artist, track title, artwork. It reloads when the stream
changes, and shows a loading state via `historyLoading`.

### What Figma already has

The Player screen contains a **Broadcast History Section** with three history rows and a
"Показать ещё" button — that button is the entry point, and nothing designs where it goes.

### Proposal

A **full-height bottom sheet**, reusing the existing components verbatim:

- Sheet container: the `Bottom Sheet` pattern already in the file — 358 wide, 28 corner
  radius, drag handle 40×4, `surface` fill, `outline` stroke.
- Title: `Radio Myata/screenTitle` (Muller Medium 24/32) — "История эфира".
- Rows: the **existing History Item component** unchanged — time
  (`Radio Myata/timestamp`, Muller Regular 14/20 after the Hanken removal), artwork 40×40
  radius 8, title `Radio Myata/listTitle`, artist `Radio Myata/miniPlayerSubtitle`.
- Scrolls to **30 rows**, matching `take(30)`.
- States needed: **loading** (the app has `historyLoading`), **empty**, and **error**
  (the repository can fail). None exists today in any design.
- Both themes, using the semantic tokens: `surface`, `textPrimary`, `textSecondary`,
  `outline`, `divider`.

**Open question for the designer:** the player already shows three rows inline. Should the
sheet repeat those three, or start after them? The app repeats them.

---

## 2. Export TXT / CSV

### What the app does today

`FavoritesFragment` has two buttons — `btnExportTxt` and `btnExportCsv` — that export the
saved-tracks list. This ships, and appears in **no design at all**.

### What Figma already has

`COLLECTION` has a header and a track list. There is no export affordance anywhere, and
the "Экспортировать список" string already appears in the design's button type sample
(`Muller Regular 22/28`), which suggests it was once considered.

### Proposal

Two options, both cheap:

**A. Overflow menu on the Collection header (recommended).** The Collection screen already
has a `⋮` affordance in the canonical design, and `Menu / Плеер` already exists as a
component to copy. Add a `Menu / Коллекция` with two rows — "Экспортировать в TXT",
"Экспортировать в CSV". Consistent with the player, costs one new component, adds nothing
to the screen itself.

**B. A row under the list.** A `Radio Myata/button` styled wide button, matching
"Показать ещё" on the Player. Simpler, but pushes a rarely-used action into permanent
view.

Either way, needed states: **empty collection** (export should be unavailable — the app
currently does not handle this) and a **completion confirmation**, since the export result
is a file the user must find.

Both themes, tokens only.

---

## Not covered here

The auth / profile / settings screens in PR #23 remain **unapproved concepts**. They are
not part of this proposal and not part of the repair plan.

## Note on the Player menu

The canonical `Menu / Плеер` in Figma contains four rows: "Найти трек", **"Таймер сна"**,
**"Сообщить о проблеме"**, "История эфира".

Two of those — sleep timer and report a problem — were dropped from the 3.6.6 canonical
scope, but **they are still present in the canonical Player menu design**. That
inconsistency needs resolving: either the rows come out of the menu, or the features come
back into scope. The repair plugin does not touch them, because removing menu rows is a
product decision rather than a drift fix.
