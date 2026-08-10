# Phone rendering baseline (A3)

Evidence that the A3 theme refactor did not change how the phone UI renders.

```bash
node tools/qa/phone/capture-phone-baseline.mjs before
# ... apply the refactor, rebuild, reinstall ...
node tools/qa/phone/capture-phone-baseline.mjs after
node tools/qa/phone/capture-phone-baseline.mjs after --night
```

Set `PHONE_SERIAL` if the phone emulator is not `emulator-5556`.

The gate is the uiautomator hierarchy — node ids, classes and bounds. Screenshots
are captured for human review only: `MainActivity` picks one of ten window
backgrounds at random per launch, so two runs legitimately differ.

The harness also dismisses the emulator's "System UI isn't responding" dialog,
which appears under software GL and would otherwise land in a screenshot and be
mistaken for a rendering change.

## What this harness does NOT prove

It does **not** evidence that the random window background survived a refactor.

An earlier version tried to, by hashing whole screenshots across twelve launches
and counting distinct results. That was invalid, and self-evidently so once the
numbers were read: it reported **11 distinct results from 10 possible
backgrounds**. It was seeing album art, playlist covers and the clock, not the
theme. A count that can exceed the number of possible causes is measuring
something else.

That claim now lives in two places that can actually support it:

- `tools/qa/themes/verify-random-themes.mjs` — reads the **compiled resource
  table** of the shipped APK: every `AppTheme0..9` present, each pointing at its
  own `screen0..9`, mappings identical before vs after, and none of them
  declaring the window flags `AppTheme` carries.
- `app/src/androidTest/.../RandomWindowBackgroundTest.kt` — asks the **running
  activity's theme** what it resolved `android:windowBackground` to, so
  inheritance through the real parent chain is covered too.
