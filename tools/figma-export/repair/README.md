# Canonical repair plugin (dry run / apply)

Applies the agreed canonical decisions to the **real Figma file**. Two modes, and the
second can only do what the first showed.

Nothing here touches Android.

## Files

| File | Role |
|---|---|
| `repair-plan.json` | **The reviewable artefact.** Every mutation, with node id, expected current value, new value and reason. Generated from the two canonical snapshots |
| `code.template.js` | Plugin source |
| `build-plugin.mjs` | Inlines the plan into `code.js` (Figma plugins cannot import JSON) |
| `code.js` | Generated, committed so the plugin runs without a build step |
| `ui.html`, `manifest.json` | Plugin UI and manifest |

Rebuild after editing the plan or the template:

```bash
node tools/figma-export/repair/build-plugin.mjs
```

## Safety model

- **Dry Run performs no writes.** Statically verified: its body contains no assignment
  to any node property, no `remove()`, no `create*`, no `setValueForMode`, no
  `setBoundVariableForPaint`. It calls exactly two Figma APIs, both read-only:
  `getLocalVariableCollectionsAsync` and `getLocalTextStylesAsync`.
- **Apply refuses without a Dry Run** in the same session, and executes **only** the
  entries that Dry Run marked `READY`.
- **Apply re-verifies every node immediately before writing.** If a value changed since
  the Dry Run, that entry is skipped and reported rather than overwritten.
- **Every node id comes from the plan.** `getNode()` is called only with `m.id` / `e.id`,
  both of which originate in `REPAIR_PLAN`. The plugin cannot discover its own targets.
- **Never touched:** image hashes, vector paths, x/y/width/height, `resize()`, artwork,
  and any frame not named in the plan. Sub-pixel differences are not treated as defects.
- Exactly **one** deletion is in the plan — the hidden duplicate subtitle on the light
  Collection-empty screen. Nothing else is removed.

## What it does

**20 structural and typography mutations**

| Op | Count | What |
|---|---|---|
| `setConstraints` | 3 | Player control icon containers → CENTER/CENTER |
| `setFontFamily` | 8 | Hanken Grotesk → Muller Regular, same size and line height |
| `setAutoLayoutHug` | 3 | TikTok (dark) and Threads (both) → HORIZONTAL + HUG/HUG |
| `setVisible` | 2 | two light-only About blurs → hidden |
| `setLayoutSizingHorizontal` | 1 | dark Player artist line → HUG |
| `setFontStyle` | 1 | light "История эфира" → Muller Bold |
| `renameNode` | 1 | light history timestamp `Text` → `10:45` |
| `deleteNode` | 1 | light hidden duplicate subtitle |

**18 semantic variables** in a new collection `Radio Myata / Semantic` with modes
**Light** and **Dark**. Nine are bound to real nodes (**101 pairs = 202 node bindings**);
nine are created with proposed values but **not bound**, because the design does not yet
use them unambiguously.

**15 text styles** under `Radio Myata/…`, created from the styles the design actually
uses. Existing styles with the same name are reused, not duplicated.

## Removed from the plan after review: the standalone `play/pause` frames

An earlier revision also set CENTER/CENTER on `2484:138` (dark) and `2484:63` (light).
Both were removed, because the evidence does not support calling them a defect:

- They sit in **loose top-level canvas frames** named `play/pause` — dark at x=428 y=1227,
  light at x=1077 y=1130 — parked below the screens, not inside any screen.
- They are plain `FRAME`s, **not components**, and **no instance anywhere references
  them**. They are detached working copies.
- Their container is **23.33 × 29.69** (the taller pause glyph), while the CENTER/CENTER
  sibling next to it is **23.33 × 23.33** (the square play glyph). Different icons with
  different intrinsic sizes — MIN/MIN may well be deliberate there, and nothing renders
  them either way.
- The agreed decision covered the Player's like/dislike containers and the `like` found in
  the second controls set. All three of those **are** in the plan
  (`2444:18269`, `2399:31216`, `2399:31223`) and are genuine: they sit in the same
  controls row as identically-sized siblings that use CENTER/CENTER.

Those two loose frames look like leftover scratch copies. Tidying the canvas is a
housekeeping decision for the designer, not drift repair.

## Deliberately left raw, and why

- **Brand and artwork colours** — stream banners, the cyan gradient ramp, social logo
  vectors (`#fefefe`→`#1c4771` on Spotify/Boosty/Я.Музыка glyphs). These are brand assets,
  not theme roles; tokenising them would let a theme change repaint a logo.
- **Frame root fills.** The screen frames carry `#ffffff` in *both* themes on Player,
  About and Collection-empty, and `#edeeef` in both on Home — they are Figma frame fills,
  not the visible background. Binding them would be binding noise.
- **`#e74c3c`** on the empty-collection illustration and **`#ff3f7b`** on the Home banner:
  identical in both themes, so they carry no theme role.
- **`background`, `surfaceElevated`, `divider`, `textDisabled`, `secondary`, `error`,
  `disabled`, `scrim`, `onPrimary`** — created with proposed values but unbound. The
  design either does not distinguish them yet or uses them identically across themes.
  Binding them would require inventing design intent.

## Known ambiguity, preserved rather than "fixed"

`#f5f7fa` in dark maps to **six** different light values. Two are frequent and meaningful,
so both are kept as separate roles:

- `textPrimary` — `#f5f7fa` / `#191c1d` (Player: track title, "История эфира", history rows)
- `textHeading` — `#f5f7fa` / `#003056` (Home, Collection, About: screen and section titles)

They collapse to one value in dark. This may be unintentional in the light page, but
merging them would change the visual design, so the plan preserves both and leaves the
decision to the designer.

## How to run

1. Open the Radio Myata file in **Figma Desktop**, with both canonical pages present.
2. `Plugins` → `Development` → `Import plugin from manifest…` → select
   `tools/figma-export/repair/manifest.json`.
3. Run **Radio Myata · Canonical Repair**.
4. Press **1 · Dry Run**. Read the report: every entry shows status, current value,
   proposed value and reason. Nothing has been written at this point.
5. Only if the report is what you expect, press **2 · Apply**.

Apply stays disabled until a Dry Run has run, and disables itself again afterwards.

## Requirements

The Muller weights used by the plan (`Regular`, `Bold`) must be available in Figma. If a
weight is missing, that entry is skipped and named in the report rather than silently
substituted.
