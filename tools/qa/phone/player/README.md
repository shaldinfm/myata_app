# PLAYER evidence (Phase B, extended by Phase C)

Produced by [`../capture-player.mjs`](../capture-player.mjs) against the running
app.

`metadata.json` is what is committed and what can be checked: the header label,
album art and play control in dp per width and theme, plus the play/pause and
favourite content descriptions at each step of the flow. Screenshots stay local -
`../.gitignore` drops `*.png`.

`PlayerLayoutTest` is the stronger check for geometry: it measures the frozen
upper-section anchors at 320/360/390/412dp in both themes on API 24 and API 36.
This harness covers what a measurement cannot - that the re-skin left the
behaviour alone.

| | |
|---|---|
| `api36/` | both themes, four widths, and the full flow with the stream actually playing |
| `api24/` | four widths and the full flow, light only |

`api24` is light only because `cmd uimode` does not exist there; the harness
records that in `themeSwitch` rather than relabelling light captures as dark. Both
themes on API 24 are covered by `PlayerLayoutTest`.

## The flow steps

```
01-player-idle       02-playing          03-favourited
04-gold              05-xtra             06-history-inline
07-history-revealed  08-paused           09-after-background
```

Steps 4 and 5 swipe the pager, so they check that switching stream still works and
that playback follows.

Steps 6 and 7 are Phase C's. `Broadcast History Section` is on the page now, so
the entry in the frozen `dislike` slot scrolls to it instead of opening a dialog
over it, and there is no sheet to dismiss. Step 7 taps "Показать ещё" and records
the row count either side: `rowsBefore` and `rowsAfter` are what is *on screen*,
which uiautomator is the limit of, so the reveal shows as 3 -> 5 rather than
3 -> 13. A control that did nothing would leave them equal.

`play="Подключение"` at a step is the control's connecting face: the 80x80 surface
stays on screen and the progress indicator replaces the glyph inside it. It shows
up more on API 24, whose image cannot reach the stream host.

Connecting used to read `play="null"` instead, meaning `btn_play` was not in the
hierarchy at all: the button was hidden and only a bare, invisible spinner was
left. That is the defect the play/pause follow-up fixed, so a `null` at **any**
step now means it has come back. Phase B had to except `06-history-sheet`, where
the bottom sheet covered the screen and there was genuinely no control to read;
the inline section that replaced it leaves the controls on the page, so the
exception is gone.

## Re-running

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
node tools/qa/phone/capture-player.mjs api36
```

`connectedDebugAndroidTest` uninstalls the app when it finishes, so install again
between a test run and a capture run.
