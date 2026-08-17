# Launch / splash evidence (audit)

Measurements behind [`docs/SPLASH-LAUNCH-AUDIT.md`](../../../docs/SPLASH-LAUNCH-AUDIT.md).

`launch-sequence.json` is what is committed and what can be checked: for each API
level and launch scenario, the observed phases of the launch with their durations
and what was on screen during each.

Screenshots and screen recordings stay local, per `../.gitignore` — and here they
would prove even less than usual, because `MainActivity` picks one of ten window
backgrounds at random per launch, so no two captures of the splash are the same
image.

## How it was captured

```bash
adb shell am force-stop dlinemedia.radioplayer.myata
adb shell input keyevent KEYCODE_HOME
adb shell "screenrecord --time-limit 15 --bit-rate 8000000 /sdcard/cold.mp4" &
adb shell am start -n dlinemedia.radioplayer.myata/com.example.musicplayerapp.MainActivity
adb pull /sdcard/cold.mp4
ffmpeg -i cold.mp4 -vf "fps=60,scale=150:-1,tile=8x6" -frames:v 1 sheet.png
```

`fps=60` matters for the launcher→app handoff: at 10 fps the transition looks like
a plain cut, and a suppressed system splash is indistinguishable from a fast one.
At 60 fps the single-frame cut is unambiguous.

For true process death, `am kill` is not enough — the bound `MediaPlayerService`
holds the process up. `adb root` then `kill -9 $(pidof …)` is what actually kills
it, confirmed by the pid changing.

## Devices

| AVD | API | Resolution | Density |
|---|---|---|---|
| `Myata_API36` | 36 | 1080x2400 | 420 |
| `Myata_Probe_API24` | 24 | as configured | as configured |

Default density, per the project's QA convention — 443dpi/390dp is for Figma
parity work, not for launch timing.

## Caveat on the timings

These are emulator numbers under software GL. The **absolute** durations are much
worse than a real device. What they establish is the **sequence and the ordering
defects**, which are device-independent: no starting window on either API level,
no platform splash on API 31+, and the bottom navigation bar drawing on top of the
splash artwork before HOME renders.
