"""Phase table for a recorded cold launch.

    python tools/qa/splash/classify-launch.py <launch.mp4> [--fps 30]

Prints, as JSON, the runs of frames the recording falls into:

    launcher      the home screen, still there, no acknowledgement of the tap
    startwindow   a flat fill - the starting window / platform splash, which is
                  @color/background with at most a centred icon
    app           anything the app itself drew: the splash artwork, HOME, PLAYER

The point of the table is the `startwindow` run. It cannot be timed against
`am start` directly - a screenrecord and an adb call are not aligned closely
enough to time a ~300ms interval - but its *duration* is measurable inside the
one video, with no alignment at all. Combined with `am start -W` TotalTime, which
is tap to the app's own first frame, that gives the number the fix is about:

    dead time = TotalTime - startwindow duration

On a build with android:windowDisablePreview="true" there is no `startwindow`
run, because no such window is ever created, and the dead time is the whole of
TotalTime. That absence is the finding, so the classifier is written to report an
empty run rather than to fail when it finds none.

Classification is by brightness and flatness on downscaled frames. A starting
window is a flat fill; the launcher has a wallpaper, icons and a search bar, and
every app screen here is either saturated artwork or text on cards. These are not
close together, so the thresholds do not need to be precise - and `--debug`
prints the per-frame numbers when a recording disagrees.
"""
import json
import subprocess
import sys
import tempfile
from pathlib import Path

import numpy as np
from PIL import Image

# A frame counts as flat when almost none of it differs from its own median.
#
# 0.80, not 0.90: the icon is a fixed fraction of the *screen*, so on a smaller
# panel it covers more of the downscaled frame and drags flatness down. The
# measured starting windows are 0.98 on API 36 and 0.85-0.88 on API 24, while the
# launcher sits at 0.12 and the busiest app frame seen here reaches 0.66. The gap
# either side of 0.80 is wide, and it is the API 24 number that sets the floor.
FLATNESS_MIN = 0.80
# ...and bright enough to be a light fill, or dark enough to be the dark one.
# Both @color/background values are near the ends; nothing else in these
# recordings is both flat and extreme.
LIGHT_MIN = 200
DARK_MAX = 70


def frames(video: str, fps: int, workdir: str):
    subprocess.run(
        ["ffmpeg", "-y", "-i", video, "-vf", f"fps={fps},scale=80:-1",
         str(Path(workdir) / "f_%04d.png")],
        check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    return sorted(Path(workdir).glob("f_*.png"))


def classify(path: Path):
    a = np.asarray(Image.open(path).convert("L"), dtype=np.int16)
    # Ignore the top and bottom bands: the status bar and the gesture pill are
    # never flat and are present in every phase, including the starting window.
    h = a.shape[0]
    a = a[int(h * 0.10):int(h * 0.90)]
    med = float(np.median(a))
    flatness = float(np.mean(np.abs(a - med) <= 6))
    flat = flatness >= FLATNESS_MIN
    if flat and (med >= LIGHT_MIN or med <= DARK_MAX):
        return "startwindow", med, flatness
    return "app_or_launcher", med, flatness


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if not args:
        print(__doc__)
        sys.exit(1)
    video = args[0]
    fps = 30
    if "--fps" in sys.argv:
        fps = int(sys.argv[sys.argv.index("--fps") + 1])
    debug = "--debug" in sys.argv

    with tempfile.TemporaryDirectory() as tmp:
        fs = frames(video, fps, tmp)
        marks = [classify(f) for f in fs]

    if debug:
        for i, (kind, med, flat) in enumerate(marks):
            print(f"{i:4d} {i / fps:6.2f}s {kind:16s} median={med:6.1f} flatness={flat:.3f}")

    runs = []
    for i, (kind, _, _) in enumerate(marks):
        if runs and runs[-1]["kind"] == kind:
            runs[-1]["frames"] += 1
        else:
            runs.append({"kind": kind, "startFrame": i, "frames": 1})
    for r in runs:
        r["startSeconds"] = round(r["startFrame"] / fps, 3)
        r["durationSeconds"] = round(r["frames"] / fps, 3)

    startwindow = [r for r in runs if r["kind"] == "startwindow" and r["durationSeconds"] >= 0.1]
    print(json.dumps({
        "video": video,
        "fps": fps,
        "frames": len(marks),
        "runs": runs,
        "startWindowRuns": startwindow,
        "startWindowTotalSeconds": round(sum(r["durationSeconds"] for r in startwindow), 3),
    }, indent=2))


if __name__ == "__main__":
    main()
