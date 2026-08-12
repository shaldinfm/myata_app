# HOME evidence (Phase B)

Produced by [`../capture-home.mjs`](../capture-home.mjs) against the running app.

`metadata.json` is what is committed and what can be checked: the header band and
both row anchors in dp per width and theme, and the Mini Player's presence at each
step of the flow. Screenshots are written next to it but stay local -
`../.gitignore` drops `*.png`, and `MainActivity` picks one of ten window
backgrounds at random per launch, so a committed PNG proves nothing a person did
not already look at.

`HomeLayoutTest` is the stronger check for geometry: it measures the frozen
anchors at 320/360/390/412dp in both themes on API 24 and API 36. This harness
covers what a measurement cannot - the Mini Player gate, the stream targets, and
the bottom clearance under a real scroll.

| | |
|---|---|
| `api36/` | both themes, four widths, and the full flow with the stream actually playing |
| `api24/` | four widths and the full flow, light only |

`api24` is light only because `cmd uimode` does not exist on API 24, so the shell
cannot switch the theme there; the harness detects that and records it in
`themeSwitch` rather than relabelling light captures as dark. Both themes on API
24 are covered by `HomeLayoutTest`, which overlays the night configuration.

## The flow steps

```
01-clean-launch              no session yet, so no pill
02-player-after-banner-tap   the banner switched stream and opened PLAYER
03-home-with-pill            back on HOME, the pill is up
04-home-scrolled-to-end      the last playlist clears the pill
```

Steps 2 and 3 are the two halves of the Mini Player contract as seen from HOME,
and step 2 doubles as the check that the re-skin did not disturb the stream
targets.

## Re-running

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
node tools/qa/phone/capture-home.mjs api36
```

`connectedDebugAndroidTest` uninstalls the app when it finishes, so install again
between a test run and a capture run.
