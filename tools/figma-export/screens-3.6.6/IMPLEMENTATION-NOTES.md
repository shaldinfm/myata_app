# 3.6.6 design — FINAL, frozen

The two proposal pages are the implementation source for 3.6.6.

| | |
|---|---|
| pages | `3.6.6 PROPOSALS - LIGHT`, `3.6.6 PROPOSALS - DARK` |
| frozen at | light `2026-08-10T09:11:36.654Z`, dark `2026-08-10T09:11:53.612Z` |
| screens | 29 per theme, 58 frames |
| baselines | `baselines/proposals-{light,dark}-normalized.json` |
| blockers | none |

Raw exports stay out of the repo. The committed baselines are the deterministic
reduction — regenerating them from the same snapshot reproduces the file
byte for byte, so a future export can be diffed against them to see whether the
design has moved.

To re-check the design later:

```bash
node tools/figma-export/canonical/normalize-snapshot.mjs <fresh-export.json> /tmp/out.json
node tools/figma-export/screens-3.6.6/audit-live.mjs <fresh-light.json> <fresh-dark.json>
```

---

## What the implementation must preserve

### Broadcast History rows

Variable height, and **nothing truncates**. The row is a horizontal auto-layout
that hugs its height; the title and artist wrap to as many lines as they need.
Two of the eight sample rows wrap on purpose, so the behaviour is visible in the
design rather than described.

```
13 │ time 39 │ 8 │ art 48 │ 8 │ text 181 │ 8 │ action 40 │ 13      stroke 1px INSIDE
```

- Row height = `2×stroke + 13 + max(child) + 13`. A one-line row is **76**; the
  wrapped samples are 116 and 104.
- **Time, album art and the find-track action are vertically centred on the row**,
  not top-aligned. This is a deliberate owner decision that supersedes an earlier
  top-aligned requirement.
- The title and artist are one text block; the time is not part of it.

> **Reference wrap width is 181 px, not 179.** The text nodes are 181 wide inside
> a 179 column. That is accepted and must not be "fixed": widening the column
> pushes the action 2px right, and narrowing the text to 179 re-wraps
> `TWO DOOR CINEMA CLUB`, which measures 180.0px in Muller Regular 14. Nothing
> clips — the column does not clip and the overhang stops 6px short of the
> action. Implement the text column so it wraps at **181**.

Rows 1–2 sit 1px right of rows 3–8 (padding 14 vs 13). Owner-designed, excluded
from cleanup, and not worth reproducing in code — use one consistent padding.

### Find-track action

One action per row, and it is the same control the Collection track item uses.
There is no History-specific variant.

### Text boxes

Ten boxes that were shorter than their line height now hug it. Any text box in
the design whose height equals its line height is intentional.

### Bottom sheets

Radius 28, corners transparent, nothing opaque painted over the rounding.
`clipsContent` is off on all seven — harmless as drawn, but on Android clip the
sheet so children cannot paint over the corners.

### Account model

Registration is optional and there is no wall anywhere. Radio, player, history,
sleep timer and a local collection all work signed out; an account *adds* sync,
cloud restore and profile. Copy is benefit-oriented and must stay that way.

### Sleep timer

Presets plus **Своё время** (hours + minutes). A confirmed custom duration is not
a special case: persist the **absolute end time**, show remaining time computed
from it, allow cancellation, survive backgrounding and process death, and do
**not** resume after a device reboot.

### Report a problem

The diagnostics card lists exactly what leaves the device. **The Telegram bot
token lives on our own endpoint and never ships in the APK.** No personal data by
default. The error state preserves the chosen category and the typed text.

---

## Intentionally pending

**Avatar artwork.** 15 of 16 cells plus the current-avatar preview are empty
rings. Geometry is final — 4×4 grid, 76px cells, 18px gutters — and every cell
carries its key (`avatar/m3-01` … `avatar/m3-16`). Only the Material 3 artwork is
outstanding. This does not block implementation.

## Deferred, not blocking

- **Default constraints.** 708 nodes sit at Figma's Left/Top. Irrelevant on fixed
  390px frames; it would only matter if the frames were resized.
- **`Menu / Плеер (таймер активен)`** is hand-positioned with an even 4px rhythm.
  Converting it to auto-layout would be pixel-identical, but there is no need.
- **2px of slack** on the find-track button's fixed axis.

## Separate legacy finding — canonical pages, not this design

`CURRENT ANDROID UI` › `Now Playing Mini Player` › `WHAT YOU KNOW`: an 18px text
box against a 27.5px line height inside a clipping ancestor, so the glyphs are
genuinely cut. Both themes. Untouched, and out of scope for 3.6.6.
