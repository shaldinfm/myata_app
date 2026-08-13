# COLLECTION evidence (Phase B)

Produced by [`../capture-collection.mjs`](../capture-collection.mjs) against the
running app.

`metadata.json` is what is committed and what can be checked: the header band,
the subtitle and the empty card in dp per width and theme, the overflow's
presence at each step, the Mini Player's presence at each step, and the bytes of
the two exported files read back off the device. Screenshots are written next to
it but stay local — `../.gitignore` drops `*.png`, and `MainActivity` picks one of
ten window backgrounds at random per launch, so a committed PNG proves nothing a
person did not already look at.

`CollectionLayoutTest` is the stronger check for geometry: it measures the frozen
anchors for both states and the row at 320/360/390/412dp in both themes on API 24
and API 36. This harness covers what a measurement cannot — the two states
reached through the real database, the overflow gate, the export actions
end to end, the Mini Player gate on COLLECTION, and the clearance under a real
scroll.

| | |
|---|---|
| `api36/` | both themes, four widths, and the full flow with a stream actually playing |
| `api24/` | four widths and the full flow, light only |

`api24` is light only because `cmd uimode` does not exist on API 24, so the shell
cannot switch the theme there; the harness detects that and records it in
`themeSwitch` rather than relabelling light captures as dark. Both themes on API
24 are covered by `CollectionLayoutTest`, which overlays the night configuration.

## The flow steps

```
01-empty-no-session          empty collection, nothing started: no pill, no overflow
02-empty-with-pill           a stream was started from HOME; the pill follows here
03-empty-pill-paused         paused, and the pill stays up
04-populated                 the current track was favourited from PLAYER
05-overflow-menu             both export rows, from the frozen header overflow
06-export-txt-picker         the SAF picker, with the frozen filename prefilled
06b-export-txt-file          the saved file, read back: row count, BOM, endings
06c-export-csv-file          the same for CSV, whose first row is the header
07-scrolled-to-end           the last row clears the pill
08-empty-again-after-delete  delete took it back to empty, and the overflow with it
```

Steps 01–03 are the Mini Player contract as seen from COLLECTION — hidden before
a session exists, visible once a stream is selected, still visible while paused.
Steps 06b and 06c are the point of the export relocation: reaching the picker only
proves the intent, so the file is saved and read back to prove the bytes.

Each run starts from `pm clear`, so the empty state is genuinely empty rather than
whatever a previous run left in the database.

## Two pickers

API 36's document picker opens on a writable folder and its save button works
straight away. API 24's opens on "Recent", where the button is drawn but disabled,
and a root has to be chosen from the drawer first — where the same root name
appears twice, once as a breadcrumb that does nothing and once as the row that
selects it. The harness tries the direct save first and falls back to the drawer,
so one code path covers both images.

## Re-running

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
node tools/qa/phone/capture-collection.mjs api36
```

`connectedDebugAndroidTest` uninstalls the app when it finishes, so install again
between a test run and a capture run.
