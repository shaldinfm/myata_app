# Android TV regression baseline

Evidence that the mobile 3.6.6 token migration did not change TV.

```bash
node tools/qa/launch-emulator.mjs tv
node tools/qa/tv/capture-tv-baseline.mjs before   # pre-isolation build
# ... apply the A0 isolation, rebuild, reinstall ...
node tools/qa/tv/capture-tv-baseline.mjs after
node tools/qa/tv/compare-tv-baseline.mjs
```

Both runs must use the **same AVD** and the same 16-step walk, which is why the
walk lives in the script rather than in a checklist someone follows by hand.

Start `Myata_TV_API36` through `launch-emulator.mjs` rather than by hand: it
forces a cold boot and stops the emulator allocating a ~2 GB
`snapshots/default_boot/ram.img` per session. The capture calls `adb` without
`-s`, so export `ANDROID_SERIAL=emulator-5554` when the phone AVD is up too.

## What is compared, and why

`compare-tv-baseline.mjs` leans on the **uiautomator hierarchy**, not the pixels:
every node's resource-id, class, bounds and focus state at every step, plus the
focus chain across the whole walk. That is exactly what an unintended theme
change would disturb, and it is stable between runs.

Screenshots are captured and hashed, but treated as informational for the player
screens. This is live radio — track metadata and album art legitimately differ
between two runs minutes apart, so a pixel difference there proves nothing. A
pixel difference on splash or stream selection does, and is reported separately.

The capture refuses to run unless `ro.build.characteristics` contains `tv`. A
phone AVD cannot produce a TV baseline, and a baseline that cannot fail is worse
than none.
