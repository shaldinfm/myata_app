# Typography 3.6.6 — Montserrat + Onest — **FINAL**

The Figma migration is complete. The 3.6.6 design source carries no Muller.

Muller was removed for licensing: there is no applicable Fontfabric licence. Both
replacements are **SIL OFL 1.1**, verified from the `name` table of the binaries
rather than from a web page.

| | family | licence |
|---|---|---|
| expressive | **Montserrat** | OFL 1.1 — Copyright 2024 The Montserrat.Git Project Authors |
| utility | **Onest** | OFL 1.1 — Copyright 2021 The Onest Project Authors |

## The contract

Assignment is by **role**, never by screen. A heading in Settings is Montserrat
even though Settings reads better in Onest overall; the mini-player is Onest on
HOME even though the headings beside it are Montserrat.

### Montserrat — expressive / music / emphasis

| role | notes |
|---|---|
| screen and section headings | weight as frozen |
| card headings | `Heading 3` at 24px → **Medium 500** |
| buttons / CTAs | **Medium 500**, 22px |
| compact actions | **Medium 500**, **21px** — dialog, bottom-sheet and utility-screen buttons |
| full Player now-playing metadata | `Track Info` |
| standalone action links | short `Link` nodes, Medium |

### Onest — utility / navigation / reading

BottomNav · mini-player · body and helper text · long paragraphs · Sleep Timer ·
Settings · Profile and account UI · forms · **Broadcast History** (timestamp,
title and artist) · **Collection track rows** (title and secondary metadata) ·
player transport labels · body links · settings group captions.

Track lists are entirely Onest: hierarchy there is carried by size and weight,
not by family.

### Weight mapping

`Light 300 → Light` · `Regular 400 → Regular` · `Medium 500 → Medium` ·
`Bold 700 → Bold` · `Black 900 → Black` · `Heavy 900 → Black`

## Result

| page | id | Onest | Montserrat | Muller |
|---|---|---|---|---|
| CURRENT ANDROID UI - LIGHT | `2388:366` | 69 | 22 | **0** |
| CURRENT ANDROID UI — DARK | `2436:531` | 130 | 29 | **0** |
| 3.6.6 PROPOSALS - LIGHT | `2517:1936` | 243 | 37 | **0** |
| 3.6.6 PROPOSALS - DARK | `2517:2903` | 280 total, 243 / 37 | | **0** |

Parity and contract both pass on all four, matched by original page id, with
**zero unexpected differences** — no node added or removed, no character edited,
no fill, stroke or effect changed.

## What moved besides the family

- **Compact actions** 22 → 21px (19 labels across the utility screens).
- **Button and card-heading weights** Regular → Medium.
- **One AUTO line height pinned**: `PLAYER` → "PAUSE" was the only text node in
  the frozen set without an explicit line height, so it would have been redefined
  by the new font. It is pinned to 26.77px — Muller's `hhea` is exactly 1000/1000
  units, so its AUTO line height was exactly `1.0 × fontSize`, measured from the
  shipped binary rather than assumed.
- **Approved growth corrections** on the headings that would otherwise have
  wrapped: `Привет, Денис!`, `Моя коллекция` (both screens) and `Поддержать
  радио` keep 24px and stay on one line, with their text box released to hug into
  space that was already empty. Nothing was shrunk to fit.
- **One owner-applied correction**: `sleep-timer-menu-active` → "Сообщить о
  проблеме", laid out so it stays on one line. Present and one-line on both
  proposal pages.

The design was largely immune to the new fonts' taller line box because 90 of the
91 frozen text nodes already pinned `lineHeight` in PIXELS. **That immunity does
not extend to Android**, where layouts do not set explicit line heights — see the
migration risks below.

## Baselines

| baseline | source page |
|---|---|
| `tools/figma-export/canonical/figma-canonical-light-normalized.json` | CURRENT ANDROID UI - LIGHT |
| `tools/figma-export/canonical/figma-canonical-dark-normalized.json` | CURRENT ANDROID UI — DARK |
| `tools/figma-export/screens-3.6.6/baselines/proposals-light-normalized.json` | 3.6.6 PROPOSALS - LIGHT |
| `tools/figma-export/screens-3.6.6/baselines/proposals-dark-normalized.json` | 3.6.6 PROPOSALS - DARK |

```bash
node tools/figma-export/typography-migration/finalize.mjs --check
```

asserts the invariant the migration established: no Muller in any baseline, and
the exact family counts above.

The raw pre-migration snapshots in `canonical/` and `screens-3.6.6/snapshots/`
are now **historical**. They no longer reproduce the baselines, by design.

## Tools

| tool | purpose |
|---|---|
| `tools/figma-export/font-trial/` | the frozen classifier, and the four-column visual trial it was approved from |
| `tools/figma-export/typography-migration/` | the migration, parity and contract validators, finalizer |
| `tools/figma-export/preflight/` | read-only audit of the four pages |
| `tools/figma-export/repair-cta-size/` | the targeted CTA-size repair |
| `tools/fonts/` | offline metric measurement and candidate comparison |

Every plugin's `code.js` is **generated** — `build.mjs` splices the classifier
verbatim out of `font-trial/code.js`, so no tool can apply rules other than the
approved ones. `build.mjs --check` fails if any has drifted.

## Still open — Android

Android has not been touched. It still ships nine Muller files, 824 KB, all of
which must go. Known constraints for that migration:

1. **Line height.** Muller's line box is exactly 1000 units; Montserrat is +21.9%
   and Onest +27.5%. Android layouts rely on font metrics, so type will grow
   vertically unless explicit line heights are set. This is the largest risk and
   it does not appear in Figma.
2. **Static instances required.** `minSdk` is 24; `fontVariationSettings` needs
   API 26. Both families ship variable-only from Google Fonts, so static weights
   must be instanced with `fonttools varLib.instancer`.
3. **`mullerblack` is referenced in 11 layouts**, `mullerheavy` in one, and
   `muller_light` is TV-only — a 300 weight the mobile design never defines.
4. **`mullerlight` and `mullerthin` have zero references** — 175 KB of dead
   weight to drop.

See [FONT-REPLACEMENT-AUDIT.md](FONT-REPLACEMENT-AUDIT.md) for the full Android
inventory and [FONT-CANDIDATE-COMPARISON.md](FONT-CANDIDATE-COMPARISON.md) for the
measurements behind the choice.
