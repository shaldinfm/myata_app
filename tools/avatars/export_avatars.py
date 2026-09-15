# -*- coding: utf-8 -*-
"""
Prepares the approved G6a avatars and writes the app's drawables and the manifest.

    python tools/avatars/export_avatars.py --source <folder with the candidate PNGs>
    python tools/avatars/export_avatars.py --source <folder> --measure   # quality report, writes nothing

Input: the owner's candidate masters (1254px round PNGs), which stay outside the repo,
and `selection.json`, which says which file becomes which `myata-NN`.

Output: `app/src/main/res/drawable-nodpi/avatar_myata_NN.webp` (384px) and
`tools/avatars/manifest.json` (source hashes, fit parameters, output hashes).

Technical preparation only - the artwork itself is never altered:

1. Fit. The painted disc in each master is slightly off-centre, not perfectly round, or
   runs past the canvas. The largest circle containing no transparent pixels is found,
   then pulled in by EDGE_INSET so its edge sits on painted artwork, not on the master's
   own anti-aliased rim. That is what removes transparent crescents and slivers.
2. Opacity. The masters' disc is alpha ~250, not 255 - about 2% see-through. Inside the
   fitted circle alpha is set to 255; colour is untouched.
3. Strays. Everything outside the fitted circle is dropped.
4. Resample in premultiplied alpha, so transparent pixels outside the circle contribute
   no colour to the edge, then apply an analytically anti-aliased circle at the output
   size. No halo, no fringe; `check_edge` measures it.
5. Encode WebP.

Requires Pillow and numpy. No absolute paths: the source folder is an argument and every
output path is relative to this repository.
"""
import argparse
import hashlib
import io
import json
import os
import sys

import numpy as np
import PIL
from PIL import Image, ImageDraw, features

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
OUT_DIR = os.path.join("app", "src", "main", "res", "drawable-nodpi")
OUT_PX = 384            # 96dp, the largest render, at xxxhdpi
QUALITY = 95
EDGE_INSET = 4          # source px pulled in from the largest clean circle
SEARCH = 12             # source px either side of the bbox centre searched for the best centre
TOLERANCE = 0.0002      # fraction of a disc's area that may be isolated transparent specks


def sha256(path):
    with open(path, "rb") as f:
        return hashlib.sha256(f.read()).hexdigest()


def fit_circle(alpha):
    """Largest circle with (almost) no transparent pixels, searched on a 4x downscale."""
    s = 4
    small = np.array(Image.fromarray(alpha).resize((alpha.shape[1] // s, alpha.shape[0] // s), Image.BOX)).astype(int)
    pad = 24
    padded = np.zeros((small.shape[0] + 2 * pad, small.shape[1] + 2 * pad), int)
    padded[pad:-pad, pad:-pad] = small
    ty, tx = np.where(padded < 200)                     # canvas outside counts as transparent
    ys, xs = np.where(padded > 128)
    cx0, cy0 = (xs.min() + xs.max()) / 2, (ys.min() + ys.max()) / 2
    k = int(TOLERANCE * np.pi * (min(small.shape) / 2) ** 2)
    best = (cx0, cy0, 0.0)
    steps = np.arange(-SEARCH / s, SEARCH / s + 1e-9, 0.5)
    for dx in steps:
        for dy in steps:
            d = np.sort(np.hypot(tx - (cx0 + dx), ty - (cy0 + dy)))
            r = d[min(k, len(d) - 1)]
            if r > best[2]:
                best = (cx0 + dx, cy0 + dy, r)
    cx, cy, r = best
    return (cx - pad) * s + s / 2, (cy - pad) * s + s / 2, r * s - s - EDGE_INSET


def circle_mask(size, supersample=8):
    big = Image.new("L", (size * supersample, size * supersample), 0)
    ImageDraw.Draw(big).ellipse((0, 0, size * supersample - 1, size * supersample - 1), fill=255)
    return np.array(big.resize((size, size), Image.LANCZOS))


def prepare(path):
    src = Image.open(path).convert("RGBA")
    a = np.array(src)
    alpha = a[..., 3]
    cx, cy, r = fit_circle(alpha)

    h, w = alpha.shape
    yy, xx = np.mgrid[:h, :w]
    dist = np.hypot(xx - cx, yy - cy)
    inside = dist < r
    stats = {
        "alpha_min_inside_before": int(alpha[inside].min()),
        "pixels_made_opaque": int((alpha[inside] < 255).sum()),
        "stray_pixels_removed": int(((dist >= r + 1) & (alpha > 0)).sum()),
    }

    # Opaque slightly past the circle, so resampling at the edge sees artwork, not the
    # master's translucent rim. Anything the output mask keeps lies within r.
    a[..., 3] = np.where(dist < r + 2, 255, alpha)
    pad = int(np.ceil(max(0, r - cx, r - cy, cx + r - w, cy + r - h))) + 4
    canvas = Image.new("RGBA", (w + 2 * pad, h + 2 * pad), (0, 0, 0, 0))
    canvas.paste(Image.fromarray(a), (pad, pad))
    box = (cx - r + pad, cy - r + pad, cx + r + pad, cy + r + pad)
    out = canvas.convert("RGBa").resize((OUT_PX, OUT_PX), Image.LANCZOS, box=box).convert("RGBA")
    o = np.array(out)
    o[..., 3] = circle_mask(OUT_PX)
    out = Image.fromarray(o)

    fit = {
        "center_x": round(float(cx), 2), "center_y": round(float(cy), 2),
        "radius": round(float(r), 2), "diameter": round(float(2 * r), 2),
        "source_px": [w, h], "edge_inset_px": EDGE_INSET,
    }
    return out, fit, stats


def check_edge(img):
    """Mean luminance of the outermost visible ring against a ring just inside it.
    A halo would show as a large jump; artwork alone varies a little."""
    a = np.array(img).astype(float)
    n = img.size[0]
    yy, xx = np.mgrid[:n, :n]
    d = np.hypot(xx - (n - 1) / 2, yy - (n - 1) / 2)
    lum = a[..., :3] @ np.array([0.2126, 0.7152, 0.0722])
    edge = (d > n / 2 - 1.5) & (d <= n / 2 - 0.5)
    inner = (d > n / 2 - 6) & (d <= n / 2 - 3)
    return round(float(lum[edge].mean() - lum[inner].mean()), 2)


def psnr_over(ref, test, bg):
    def flat(im):
        c = Image.new("RGBA", im.size, bg + (255,))
        c.alpha_composite(im)
        return np.array(c.convert("RGB")).astype(float)
    mse = ((flat(ref) - flat(test)) ** 2).mean()
    return 99.0 if mse == 0 else 10 * np.log10(255 ** 2 / mse)


def encode(img, quality):
    buf = io.BytesIO()
    if quality == "lossless":
        img.save(buf, "WEBP", lossless=True, method=6)
    else:
        img.save(buf, "WEBP", quality=quality, method=6, alpha_quality=100)
    return buf.getvalue()


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--source", required=True, help="folder holding the candidate PNGs named in selection.json")
    ap.add_argument("--measure", action="store_true", help="print a quality/size report for several encodings and write nothing")
    args = ap.parse_args()

    selection = json.load(open(os.path.join(HERE, "selection.json"), encoding="utf-8"))
    entries = selection["avatars"]
    ids = [e["id"] for e in entries]
    if ids != ["myata-%02d" % i for i in range(1, len(ids) + 1)] or len(ids) != 24:
        sys.exit("selection.json must list myata-01..myata-24 in order")

    prepared = []
    for e in entries:
        path = os.path.join(args.source, e["source"])
        if not os.path.isfile(path):
            sys.exit("missing source: %s" % path)
        img, fit, stats = prepare(path)
        prepared.append((e, path, img, fit, stats))

    if args.measure:
        print("id        q      bytes   PSNR light / dark")
        for e, _, img, _, _ in prepared:
            for q in (85, 90, 95, 100, "lossless"):
                data = encode(img, q)
                test = Image.open(io.BytesIO(data)).convert("RGBA")
                print("%s  %-8s %7d   %.1f / %.1f" % (e["id"], q, len(data),
                      psnr_over(img, test, (248, 249, 250)), psnr_over(img, test, (15, 37, 62))))
        return

    out_dir = os.path.join(REPO, OUT_DIR)
    for old in os.listdir(out_dir):
        if old.startswith("avatar_") and old.endswith(".webp"):
            os.remove(os.path.join(out_dir, old))

    records = []
    for e, path, img, fit, stats in prepared:
        name = "avatar_" + e["id"].replace("-", "_") + ".webp"
        data = encode(img, QUALITY)
        target = os.path.join(out_dir, name)
        with open(target, "wb") as f:
            f.write(data)
        decoded = Image.open(io.BytesIO(data)).convert("RGBA")
        records.append({
            "id": e["id"],
            "source_file": e["source"],
            "source_sha256": sha256(path),
            "source_bytes": os.path.getsize(path),
            "selected_version": e["version"],
            "fit": fit,
            "preparation": stats,
            "output_file": "/".join([OUT_DIR.replace(os.sep, "/"), name]),
            "output_sha256": hashlib.sha256(data).hexdigest(),
            "output_bytes": len(data),
            "output_px": OUT_PX,
            "edge_luminance_step": check_edge(decoded),
            "psnr_light_dark": [round(psnr_over(img, decoded, (248, 249, 250)), 1),
                                round(psnr_over(img, decoded, (15, 37, 62)), 1)],
            "provenance": "owner-created, cleared for Radio Myata; C2PA manifest present in the source, not carried into WebP",
        })
        print("%s <- %-15s %6d bytes  edge step %+.1f" % (e["id"], e["source"], len(data), records[-1]["edge_luminance_step"]))

    manifest = {
        "about": selection["about"],
        "provenance": selection["provenance"],
        "encoder": {"format": "WebP", "quality": QUALITY, "method": 6, "alpha_quality": 100,
                    "pillow": PIL.__version__, "libwebp": features.version("webp")},
        "stored_ids": "user_metadata.avatar_id holds myata-01..myata-24. Unknown values, including the pre-release m3-NN keys, resolve to no avatar.",
        "avatars": records,
    }
    with open(os.path.join(HERE, "manifest.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print("total bytes:", sum(r["output_bytes"] for r in records))


if __name__ == "__main__":
    main()
