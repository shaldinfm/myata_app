# Launch / splash evidence

Measurements behind [`docs/SPLASH-LAUNCH-AUDIT.md`](../../../docs/SPLASH-LAUNCH-AUDIT.md)
and the launch-experience fix that followed it.

| | |
|---|---|
| `launch-sequence.json` | per API level and scenario, the observed phases of the launch and what was on screen during each, before and after the fix |
| `launch-timing.json` | cold-launch timing, before vs after: `am start -W` TotalTime, and the dead time derived from it |
| `classify-launch.py` | turns one screenrecord into a phase table; this is what measures the starting window |

## The one number that matters

`am start -W` TotalTime is tap to the app's *own* first frame. It is not the dead
time, and it is not what the fix was about - it went **up** slightly, because a
splash screen costs something to draw.

The dead time is tap to the first frame that is not the launcher, and it needs
the video:

```bash
python tools/qa/splash/classify-launch.py launch.mp4
# deadTime = TotalTime - startWindowTotalSeconds * 1000
```

On the pre-fix build `startWindowTotalSeconds` is **0** on both API levels - no
such window is ever created - so the dead time is the whole of TotalTime. That
zero is the finding, which is why the classifier reports an empty run rather than
failing when it finds none.

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

## Instrumentation

`LaunchSequenceTest` is the repeatable half of this, and the half that belongs in
CI: it checks that the bottom bar is not granted to HOME while the splash still
has a view, and that the live theme does not carry `windowDisablePreview`.

```bash
ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.musicplayerapp.LaunchSequenceTest
```

Run it on its own or with `RandomWindowBackgroundTest`, not inside a full
`connectedDebugAndroidTest`: the splash hands over on a real playlist fetch, and a
device still busy from another class's twenty launches can miss the budget. That
shows up as a skipped test, never as a silent pass.

## Caveat on the timings

These are emulator numbers under software GL. The **absolute** durations are much
worse than a real device. What they establish is the **sequence and the ordering
defects**, which are device-independent: no starting window on either API level,
no platform splash on API 31+, and the bottom navigation bar drawing on top of the
splash artwork before HOME renders.
