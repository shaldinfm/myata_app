# Muller replacement — candidate comparison

Local comparison only. **No font binary is committed**, nothing was added to
`app/src/main/res/font`, no production code changed and Figma is untouched.

Candidates were downloaded to a scratch directory outside the repository, from
the Google Fonts source of record (`github.com/google/fonts/ofl/<family>`), each
with its `OFL.txt` and `METADATA.pb` alongside. Every fact below about licence,
family, axis and coverage is read **from the downloaded binary**, not from a web
page.

Measured with `tools/fonts/compare.mjs` against `main` at `5deda23`.

---

## How the weights were measured

All three ship from Google Fonts as **a single variable file with a `wght` axis
and no static instances**. Measuring them at 400/500/700 therefore meant reading
real per-weight advance widths out of each font — `fvar` for the axis, `avar`
for its non-linear remapping where present, `HVAR` for per-glyph advance deltas.
No weight is synthesized or extrapolated.

That path is verified, not assumed. Against Montserrat's own upstream statics
(`JulietaUla/Montserrat`, `fonts/ttf/`), the HVAR-computed instances agree to
within **2.46 font units of 1000 upem** across Regular, Medium and Bold — 0.05dp
at 22sp, which is integer-rounding noise in the static files:

| weight | worst disagreement |
|---|---|
| Regular 400 | 2.46 units |
| Medium 500 | 2.07 units |
| Bold 700 | 1.71 units |

Independently, Onest's default instance *is* 400, and the HVAR result at
`wght=400` reproduces the default-instance width exactly.

---

## 1–3. Width deltas, worst cases, overflows

Widths in dp at the size each string is actually set at. Slot is the frozen
container. BottomNav slots are item widths **at 320dp**, the tightest case:
the bar pads 23.32/18 with 3×14.6 gaps, leaving 234.88dp shared by the frozen
item weights 79/68/94/64.

| string | wt | Muller | Onest | Manrope | Montserrat | slot |
|---|---|---|---|---|---|---|
| `Главная` | 500 | 47.6 | 47.2 (−0.8%) | 47.4 (−0.3%) | 51.6 (**+8.6%**) | 61 |
| `Плеер` | 500 | 36.8 | 36.6 (−0.5%) | 36.8 (+0.1%) | 40.9 (**+11.1%**) | 52 |
| `Коллекция` | 500 | 63.5 | 62.6 (−1.4%) | 63.1 (−0.7%) | 70.3 (**+10.6%**) | **72** |
| `О нас` | 500 | 32.3 | 32.8 (+1.4%) | 31.9 (−1.3%) | 35.5 (+9.8%) | 49 |
| `Мятные плейлисты` | 700 | 261.1 | 266.2 (+1.9%) | 269.3 (+3.1%) | 298.8 (**+14.4%**) | 358 |
| `Поддержать радио` | 500 | 224.9 | 223.1 (−0.8%) | 221.1 (−1.7%) | 243.8 (+8.4%) | 358 |
| `Экспортировать список` | 400 | 250.1 | 254.0 (+1.6%) | 251.5 (+0.6%) | 275.5 (+10.2%) | 310 |
| `Поддержать эфир` | 400 | 192.6 | 191.8 (−0.4%) | 188.9 (−1.9%) | 209.1 (+8.5%) | 310 |
| `Подписаться` | 400 | 137.0 | 137.8 (+0.6%) | 138.1 (+0.8%) | 150.1 (+9.6%) | 310 |
| `TWO DOOR CINEMA CLUB` | 400 | 180.0 | 175.3 (−2.6%) | 165.5 (**−8.0%**) | 189.7 (+5.4%) | 233 |
| `WHAT YOU KNOW` | 500 | 137.1 | 132.1 (−3.7%) | 124.4 (**−9.3%**) | 143.5 (+4.7%) | 233 |
| `MIAMI HORROR FT. POOLSIDE` | 400 | 207.6 | 200.6 (−3.4%) | 187.7 (**−9.5%**) | 216.9 (+4.5%) | 233 |
| `TWENTY ONE PILOTS` | 400 | 178.1 | 173.1 (−2.8%) | 163.2 (−8.4%) | 184.6 (+3.7%) | 233 |
| `WHAT YOU KNOW` | 900 | 224.1 | 221.8 (−1.0%) | 208.1 (−7.1%) | 240.8 (+7.5%) | 300 |

| | worst widening | worst narrowing | overflows |
|---|---|---|---|
| **Onest** | +5.1dp (+1.9%) `Мятные плейлисты` | −7.0dp (−3.4%) `MIAMI HORROR…` | **none** |
| **Manrope** | +8.1dp (+3.1%) `Мятные плейлисты` | −19.8dp (−9.5%) `MIAMI HORROR…` | **none** |
| **Montserrat** | +37.7dp (+14.4%) `Мятные плейлисты` | — (widens everywhere) | **none** |

No candidate overflows a frozen slot outright. The distinction is margin, below.

## 4. History strings that gain a line

Measured at the canonical 233dp row width. History rows are variable height with
**no ellipsis** by owner decision, so extra width becomes extra rows.

| string | Muller | Onest | Manrope | Montserrat |
|---|---|---|---|---|
| `КРАСНОЗНАМЁННАЯ ДИВИЗИЯ ИМЕНИ МОЕЙ БАБУШКИ` | 2 | 2 | 2 | **3 — gains a line** |
| `Прогулка по воде под дождём в конце ноября` | 2 | 2 | 2 | 2 |

Montserrat is the only candidate that changes a History row's height.

## 5. BottomNav safety at 320dp

The tightest constraint in the app, on the one surface visible from every screen.

| candidate | `Коллекция` | slot | spare |
|---|---|---|---|
| Muller (today) | 63.5dp | 72.4dp | 8.9dp |
| **Onest** | 62.6dp | 72.4dp | **9.8dp** — better than today |
| **Manrope** | 63.1dp | 72.4dp | 9.3dp |
| **Montserrat** | 70.3dp | 72.4dp | **2.1dp** |

2.1dp is roughly one glyph's side bearing. Any font-scale increase, locale change
or rounding difference truncates the label.

## 6. ABOUT US paragraph

At 358dp, all three keep the paragraph at **3 lines**, same as Muller. No frame
height change from wrapping.

That is *not* the whole story — see line height in §13.

## 7. Button-label overflow risk

Buttons are hug-width with 48dp of padding inside a 358dp column. Worst case is
`Экспортировать список`:

| candidate | text | + 48dp padding | column | spare |
|---|---|---|---|---|
| Muller | 250.1 | 298.1 | 358 | 59.9 |
| Onest | 254.0 | 302.0 | 358 | 56.0 |
| Manrope | 251.5 | 299.5 | 358 | 58.5 |
| Montserrat | 275.5 | 323.5 | 358 | 34.5 |

All fit. Montserrat halves the margin.

## 8. 400/500/700 mapping quality

| | 400 | 500 | 700 | 900 | axis |
|---|---|---|---|---|---|
| **Onest** | named `Regular` | named `Medium` | named `Bold` | named `Black` | 100–900 |
| **Manrope** | named `Regular` | named `Medium` | named `Bold` | **absent — axis stops at 800** | 200–800 |
| **Montserrat** | named `Regular` | named `Medium` | named `Bold` | named `Black` | 100–900 |

All three provide real named instances at 400/500/700 — no synthesis needed.

**Manrope has no 900.** Muller's 900 is not marginal on Android: `mullerblack`
is referenced in **11 files** and `mullerheavy` in one. Those would have to move
to ExtraBold 800.

### The variable-only problem

`android:fontVariationSettings` requires **API 26**; this project's `minSdk` is
**24**. A variable font placed in `res/font` renders at its **default instance**
on API 24–25, and the default instances differ sharply:

| candidate | default instance | what API 24–25 would render |
|---|---|---|
| **Onest** | `wght 400` | Regular everywhere — weights flatten, still legible |
| **Manrope** | `wght 200` | **ExtraLight everywhere** |
| **Montserrat** | `wght 100` | **Thin everywhere** |

The correct fix for all three is to ship **static instances** rather than the
variable file. Neither `googlefonts/onest` nor `sharanda/manrope` exposes a
static directory at the path checked, and the Google Fonts entries carry only the
variable file, so statics would need instancing with `fonttools varLib.instancer`
as a build step. Montserrat is the exception: `JulietaUla/Montserrat` ships
statics directly.

Onest is the only candidate whose variable file degrades gracefully if statics
are ever missed.

## 9. TV 300

TV's `fragment_tv_stream_selection.xml` uses `muller_light` (300) — the one weight
the frozen mobile design never defines.

| candidate | 300 |
|---|---|
| Onest | on axis, named `Light` |
| Manrope | on axis, named `Light` |
| Montserrat | on axis, named `Light` |

All three cover TV's requirement. No candidate forces a visible weight change on
the stream-selection screen.

## 10. Cyrillic coverage

Probe: full Russian upper and lower case including `Ё`/`ё`, Latin upper and
lower, digits, `«»—…` — 132 codepoints, read from each font's `cmap`.

| candidate | coverage |
|---|---|
| Onest | **132/132** |
| Manrope | **132/132** |
| Montserrat | **132/132** |

All complete. For reference, current Muller carries 66 Cyrillic codepoints — the
basic Russian set — so none of these is a regression.

## 11. Licence and provenance

| candidate | licence (from the binary's `name` table) | copyright line (from `OFL.txt`) |
|---|---|---|
| Onest | SIL Open Font License 1.1 | Copyright 2021 The Onest Project Authors (`github.com/googlefonts/onest`) |
| Manrope | SIL Open Font License 1.1 | Copyright 2018 The Manrope Project Authors (`github.com/sharanda/manrope`) |
| Montserrat | SIL Open Font License 1.1 | Copyright 2024 The Montserrat.Git Project Authors (`github.com/JulietaUla/Montserrat.git`) |

All three carry the OFL 1.1 grant embedded in the font file itself, and an
`OFL.txt` was retrieved alongside each and kept with the local candidate. OFL 1.1
permits embedding in a commercial application; the obligation is to retain the
licence and copyright notice and not to sell the font by itself.

Contrast with the current state: Muller's own `name` table carries
`Muller is a trademark of Fontfabric LLC` and no licence grant at all.

## 12. File size

| | files | size | vs Muller |
|---|---|---|---|
| Muller (today) | 9 | 805 KB | — |
| Onest | 1 variable | 189 KB | −616 KB |
| Manrope | 1 variable | 162 KB | −643 KB |
| Montserrat | 1 variable | 727 KB | −77 KB |

Those are variable-file sizes. If statics are shipped instead — which §8 says
they must be — the picture changes and Montserrat gets much worse: its upstream
`Montserrat-Regular.ttf` alone is **446 KB**, so four static weights are roughly
**1.8 MB**, more than double Muller's total. Onest and Manrope statics were not
obtainable to measure, but their whole variable families are 189 KB and 162 KB,
so four instanced weights will land far below Montserrat's.

Note also that 175 KB of today's 805 KB is `mullerlight` + `mullerthin`, which
have zero references.

## 13. Line height — the risk width analysis misses

Muller's `hhea` is `780 / −220 / 0`: exactly 1000 units, an unusually tight em.
Every candidate is substantially taller, and all three set `USE_TYPO_METRICS`.

| | ascender | descender | line box | vs Muller |
|---|---|---|---|---|
| Muller | 780 | −220 | 1000 | — |
| **Montserrat** | 968 | −251 | 1219 | **+21.9%** |
| **Onest** | 970 | −305 | 1275 | **+27.5%** |
| **Manrope** | 1066 | −300 | 1366 | **+36.6%** |

Default line spacing grows by roughly a fifth to a third **in every TextView**,
even where the line count is unchanged. This is the single largest layout risk in
the migration and it inverts the width ranking — Montserrat is the *least* bad
here.

Concretely: the BottomNav label sits in a 46dp item under a 20dp icon; at 12sp
Muller's line box is 12.0dp, Montserrat's 14.6dp, Onest's 15.3dp, Manrope's
16.4dp. History rows and the ABOUT US paragraph grow proportionally.

**This is fixable and should be planned for**: set explicit `lineHeight` (or
`lineSpacingExtra` with `includeFontPadding="false"`) on the migrated text styles
rather than accepting the font's default. It does not disqualify any candidate,
but it means "the line count is unchanged" is not the same as "the height is
unchanged".

## Style proximity

| | cap height | x-height | x/cap | comment |
|---|---|---|---|---|
| Muller | 700 | 480 | 0.686 | small x-height, geometric |
| **Montserrat** | **700** | 517 | 0.739 | identical cap height, closest ratio |
| Onest | 707 | 527 | 0.745 | near-identical cap height |
| Manrope | 720 | 540 | 0.750 | tallest caps, largest x-height |

All three have a larger x-height than Muller, so all will read slightly bigger at
the same point size. Montserrat matches Muller's cap height exactly and has the
closest x/cap ratio — its problem is width, not proportion.

---

## Rankings

**A. Metric similarity to Muller**

1. **Onest** — worst deviation +1.9% / −3.4%; nav labels within 1.4%.
2. **Manrope** — good on short strings, but −8 to −9.5% on every track-metadata
   string.
3. **Montserrat** — +3.7% to +14.4% across the board.

**B. Visual/style similarity to Muller**

1. **Montserrat** — exact cap height, closest x/cap, same geometric sans genre.
2. **Onest** — cap height within 1%, slightly larger x-height, similar grotesque
   character.
3. **Manrope** — tallest caps and largest x-height, and its narrower widths make
   it read lighter than Muller at the same weight.

**C. Lowest migration risk**

1. **Onest** — no overflow, no line gain, nav margin *improves*, 900 present,
   safe variable default.
2. **Manrope** — no overflow, no line gain, but no 900 at all, worst line-height
   growth (+36.6%) and an ExtraLight variable default.
3. **Montserrat** — 2.1dp nav margin at 320dp, gains a History line, ~1.8 MB as
   statics.

**D. Suitability for Russian UI**

1. **Onest** — designed with Cyrillic from the start, full coverage, and the
   Cyrillic-heavy strings measure closest to today.
2. **Montserrat** — full coverage and a long track record in Russian interfaces;
   wide, which costs the most on the longest Cyrillic strings.
3. **Manrope** — full coverage, but at −8 to −9.5% Cyrillic runs read noticeably
   lighter and smaller than the frozen design.

---

## Recommendation

### BEST — Onest

- The only candidate that breaks nothing: no slot overflows, no History line
  gained, no button margin lost.
- Closest to Muller where it matters: nav labels within 1.4%, worst case +1.9%.
- **Improves** the tightest constraint in the app — `Коллекция` gains 9.8dp of
  margin at 320dp against Muller's 8.9dp.
- Real named instances at 400/500/700 **and** 900, so `mullerblack`'s 11 Android
  references map without reassignment, plus 300 for TV.
- 132/132 coverage, OFL 1.1 in the binary, and Cyrillic is native to the design.
- Its variable default is 400, so it is the only candidate that stays legible if a
  variable file ever ships to an API 24–25 device.
- Cost: +27.5% line box, which must be handled with explicit line heights.

### SECOND — Manrope

- Also breaks no frozen layout: no overflow, no line gained, nav margin healthy.
- Ruled out of first place by three things: **no 900 on the axis at all**, so
  `mullerblack`'s 11 references must drop to 800; the **worst line-height growth**
  at +36.6%; and a **200 ExtraLight variable default**, the least forgiving of the
  three.
- Its −8 to −9.5% on track metadata is not a layout break but is a visible change
  of character — Russian text will read lighter and smaller than the frozen design.

### REJECT — Montserrat

- **2.1dp of margin on `Коллекция` at 320dp.** The BottomNav is on every screen;
  one rounding difference or font-scale bump truncates the primary label.
- **Gains a line on a History title.** History was deliberately specified as
  variable-height with no ellipsis, so this directly changes the frozen design's
  behaviour rather than just its metrics.
- **+14.4% on headings** — the largest deviation measured anywhere.
- **~1.8 MB as four statics**, more than double Muller, on an app that would
  otherwise shrink by ~600 KB.
- It is genuinely the closest in *proportion* (identical cap height, best x/cap)
  and the best on line height. That is exactly why the decision must not come from
  aggregate width percentage: it looks the most like Muller and fits the frozen
  layout the worst.

---

## Not done

No font added to the app. No `res/font` change, no XML or Kotlin change, no Figma
change, no B2 work. Candidate binaries live in a scratch directory outside the
repository and are not committed; only the harness and this document are.

Before any migration: confirm the family, then instance static 400/500/700 (+900,
+300 for TV) with `fonttools varLib.instancer`, plan explicit line heights, update
Figma first so the canonical widths stay authoritative, and re-run the phone
harnesses and the TV gate.
