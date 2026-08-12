# Mini Player evidence (B2)

Produced by [`../capture-mini-player.mjs`](../capture-mini-player.mjs) against the
running app.

`metadata.json` is what is committed and what can be checked: pill bounds in dp
per width and theme, and the button's content description at each step of the
flow. Screenshots are written next to it but stay local - `../.gitignore` drops
`*.png` because a capture run regenerates them, and because `MainActivity` picks
one of ten window backgrounds at random per launch, so two runs legitimately
differ and a committed PNG proves nothing a person did not already look at.

| | |
|---|---|
| `api36/` | both themes, four widths, and the full flow with the stream actually playing |
| `api24/` | four widths, light only, and the full flow |

**`api24` is light only on purpose.** `cmd uimode` does not exist on API 24, so
the shell cannot switch the theme there; the harness detects that and says so in
`themeSwitch` rather than relabelling light captures as dark. Both themes on API
24 are covered by `MiniPlayerLayoutTest`, which overlays the night configuration
instead of asking the shell.

The stream host is also unreachable from the API 24 image
(`ERROR_CODE_IO_NETWORK_CONNECTION_FAILED`), so `isPlaying` never becomes true
there and the button correctly stays on the play glyph. On API 36 the stream
plays, and the flow shows the icon following it: play → `Пауза` while playing →
`Воспроизвести` after pause, holding through COLLECTION, ABOUT US and a
background/foreground round trip.

## What the flow steps are

```
01-home-idle        02-home-playing     03-home-paused
04-collection       05-about            06-player          (no pill - by design)
07-back-home        08-after-background
```

## Re-running

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
node tools/qa/phone/capture-mini-player.mjs api36
```

`connectedDebugAndroidTest` uninstalls the app when it finishes, so install again
between a test run and a capture run. The script now refuses to start rather than
driving an app that is not there.
