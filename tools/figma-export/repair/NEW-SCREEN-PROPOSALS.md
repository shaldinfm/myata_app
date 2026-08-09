# Design proposals for 3.6.6

Screens and flows that need canonical Light + Dark design before Android
implementation. Written from the real app where a feature already ships, and from the
agreed 3.6.6 scope where it does not.

**None of this is applied to Figma.** The repair plugin does not touch any of it.

---

## Scope correction

An earlier revision of this document proposed removing **Таймер сна** and **Сообщить о
проблеме** from `Menu / Плеер`, on the grounds that neither exists in the app.

**That proposal is withdrawn.** Both are intentional new 3.6.6 features. The menu rows
stay exactly as they are, and both flows need full design.

---

## 1. Sleep timer — NEW in 3.6.6

**Entry point:** `Menu / Плеер` → "Таймер сна" (already in the canonical design).

**Future Android behaviour:** the timer must actually **stop playback** when it expires.
Not designed or implemented here — noted so the design does not promise something the
implementation will not do.

### Screens and states, Light + Dark

Use the existing bottom-sheet pattern verbatim — `Bottom Sheet / Найти трек на стриминге`
is the template: 358 wide, radius 28, drag handle 40×4, `surface` fill, `outline` stroke,
rows 56 high with a 48×48 leading icon slot.

**a. Picker (no timer running)**

Title `Radio Myata/screenTitle` — "Таймер сна". Four rows, `Radio Myata/rowLabel`:

| Row | |
|---|---|
| 15 минут | |
| 30 минут | |
| 45 минут | |
| 60 минут | |

No option is preselected. The selected-row treatment already exists in the music-service
sheet (leading `primary` check plus an inset `primary` bar) — reuse it, not a new control.

**b. Active timer**

Same sheet, re-entered while a timer runs:

- A status line above the rows, `Radio Myata/body` in `textSecondary`:
  "Осталось 24 минуты" — remaining time, rounded to whole minutes.
- The active option keeps the selected treatment.
- A destructive row at the bottom, separated by a `divider`:
  **"Отменить таймер"** in `error`, with the icon in `error`.

**c. Remaining time outside the sheet**

The player menu row shows the remaining time as a trailing value —
`Menu / Плеер` → "Таймер сна · 24 мин", using `textSecondary` for the value. This is the
only change to an existing canonical frame that this feature implies, and it is additive.

**d. Completed**

When the timer expires and playback stops, no sheet is on screen. The player simply
shows its paused state. **No dialog, no toast.** The one requirement is that the paused
state is genuinely reachable and correct — which the canonical Player already covers.

**Open question:** should a timer survive the app being killed? That is a product
decision with implementation cost, and the design does not assume either answer.

---

## 2. Report a problem — NEW in 3.6.6

**Entry point:** `Menu / Плеер` → "Сообщить о проблеме" (already in the canonical design).

This feature is also the intended way to **collect evidence for issue #15** — playback
stopping by itself, which is still open precisely because no diagnostic data ever reaches
us.

### Screens and states, Light + Dark

Same bottom-sheet container as above.

**a. Form**

Title — "Сообщить о проблеме".

- **Category**, required, single choice. Rows in the existing sheet-row style:
  - Радио не запускается
  - Звук пропал во время прослушивания
  - Радио само остановилось
  - Проблема с обложками или названиями треков
  - Другое
- **Description**, optional. A multi-line field, 126 high, radius 16, `surfaceContainer`
  fill, `outline` stroke, placeholder in `textDisabled`: "Что произошло? Необязательно".
- **Send** — full-width 52 high, radius 12, `primary` fill, `onPrimary` label.
- A one-line note in `textSecondary` below the button stating exactly what is attached —
  see the diagnostics list. The user must be able to see this before sending, not after.

**b. Loading**

Send becomes a progress state; the category rows and the field are disabled using
`disabled` / `textDisabled`. Nothing else moves.

**c. Success**

The sheet collapses to a short confirmation: a `primary` check, "Спасибо, отчёт
отправлен" in `Radio Myata/rowLabel`, and a single "Закрыть" button. No ticket number —
there is no ticketing system behind this.

**d. Error**

The form is kept **with everything the user typed intact**, plus an inline row above the
button: warning glyph and "Не удалось отправить. Проверьте соединение и попробуйте
снова." in `error`. The Send button stays enabled so retry is one tap.

### Diagnostic context

Attached automatically, shown to the user before sending:

- app version, Android version, device model
- current stream, playback state
- recent `MyataPlayback` events

**No personal data by default.** No account identity, no location, no listening history
beyond the current stream, no free-text unless the user typed it.

### Architecture — a hard constraint on implementation

```
Android app  ->  controlled backend / serverless endpoint  ->  Telegram Bot API
```

**Telegram bot credentials must never be embedded in the Android app.** A bot token in an
APK is extractable by anyone who downloads it and would let a third party post as the bot
and read its updates. The app talks only to our own endpoint; that endpoint holds the
token and is the only thing that talks to Telegram.

This is a design-time constraint recorded here so it is not discovered late. The endpoint
itself is out of scope for the design pass.

---

## 3. Full Broadcast History — already ships, no design

`HistoryBottomSheet.kt`, opened from the player history button
(`MyataStreamFragment.kt:222`), calls `vm.loadHistory()` and keeps **the most recent 30
entries**. Per entry: time, artist, track title, artwork.

**Proposal:** the same bottom-sheet container; title "История эфира"; rows built from the
**existing History Item component unchanged** — time `Radio Myata/timestamp`, artwork
40×40 radius 8, title `Radio Myata/listTitle`, artist `Radio Myata/miniPlayerSubtitle`;
scrolling to 30 rows.

**States that exist nowhere today and are needed:** loading (`historyLoading` already
exists in the view model), empty (a freshly started stream), and error
(`HistoryRepository` can fail — today the user just sees an empty sheet).

**Open question:** the Player already shows three rows inline. Does the sheet repeat them
or continue after them? The app repeats them.

---

## 4. Collection actions — already ship, no design

`FavoritesFragment` has `btnExportTxt` and `btnExportCsv`. `FavoritesAdapter` gives every
row five inline controls: `btnDelete`, `btnSpotify`, `btnAppleMusic`, `btnYandex`,
`btnYouTube`. None of it appears in any design.

Five permanent buttons per row is heavy for rarely-used actions and does not match the
canonical language, where secondary actions sit behind `⋮`.

**Per-track actions → the existing music-service sheet.** It already lists Spotify, Apple
Music, YouTube Music and Яндекс Музыка — four of the five. Add "Удалить из коллекции" in
`error` behind a `divider`. Removes four buttons per row and reuses a component that
exists in both themes.

**Export → `Menu / Коллекция`**, copying `Menu / Плеер`, with "Экспортировать в TXT" and
"Экспортировать в CSV".

**States:** export disabled when the collection is empty (the app does not handle this
today), and a completion confirmation, since the result is a file the user must find.

---

## Not covered here

The auth / profile / settings screens in PR #23 remain **unapproved concepts**.
