# Theme verification

Deterministic evidence that a theme refactor preserved the random window
background feature and did not leak `AppTheme`'s window flags into
`AppTheme0`–`AppTheme9`.

```bash
# build an APK from before the change and one from after
node tools/qa/themes/verify-random-themes.mjs before.apk after.apk

# or check the current build on its own
node tools/qa/themes/verify-random-themes.mjs app/build/outputs/apk/debug/app-debug.apk
```

## What it checks

| # | check | source |
|---|---|---|
| 0 | the framework attribute-id mapping is right | self-check against `AppTheme` |
| 1 | `MainActivity` still does `(0..9).random()` and all ten branches map `i -> AppTheme<i>` | Kotlin source |
| 2 | all ten themes present | compiled resource table |
| 3 | each points at its own `screen0..9` | compiled resource table |
| 4 | all ten mappings identical before vs after | two APKs |
| 5 | none declares `windowFullscreen` / `windowIsTranslucent` / `windowDisablePreview` | compiled resource table |

Check 0 exists because framework attributes appear in `aapt2` output as raw ids
rather than names. If that mapping were wrong, every later check would find
nothing and report a cheerful pass. So the ids are asserted against `AppTheme`,
whose XML is known to set exactly those four — a wrong mapping fails loudly.

Check 4 refuses to report "identical" unless all ten mappings were actually read.
An earlier draft compared two empty maps and passed.

Check 5 is at the **declaration** level. Inheritance through the real parent chain
is covered on-device by `RandomWindowBackgroundTest`.

## Why not screenshots

The first attempt at this hashed whole screenshots across twelve launches and
counted distinct results. It reported **11 distinct results from 10 possible
backgrounds** — impossible, and the giveaway that it was measuring album art and
the clock rather than the theme. Screen uniqueness cannot evidence theme
selection, in either direction: identical screens would not disprove randomness
either, since two launches can pick the same background.
