# Muller replacement audit

> **Status:** superseded as a decision record — the Montserrat + Onest system is
> FINAL and the Figma migration is complete. See
> [TYPOGRAPHY-3.6.6.md](TYPOGRAPHY-3.6.6.md). The Android inventory and the
> measurements below remain current and unstarted.

Audit only. **No font has been added, removed, downloaded or replaced**, and no
production code changed. The owner has no applicable Fontfabric licence for
Muller and intends to remove it from the app and the design; this records what
that will touch and how to choose the replacement on evidence.

Prepared against `main` at the B1 merge (`5deda23`).

---

## A. Android inventory

### A.1 Files shipped

Every font in the app is Muller. Nine files, **824 KB** in the APK, all under
`app/src/main/res/font/`. The `name` table of each confirms Fontfabric as the
trademark holder, so all nine are in scope.

| resource | file | sfnt | weightClass | bytes | fsType |
|---|---|---|---|---|---|
| `muller_bold` | `muller_bold.ttf` | glyf | 700 | 85,756 | 0 |
| `muller_light` | `muller_light.otf` | CFF | 300 | 90,672 | **8** |
| `muller_regular` | `muller_regular.ttf` | glyf | 400 | 87,080 | 0 |
| `mullerblack` | `mullerblack.ttf` | glyf | 900 | 85,160 | 0 |
| `mullerheavy` | `mullerheavy.ttf` | glyf | 900 | 85,796 | 0 |
| `mullerlight` | `mullerlight.ttf` | glyf | 300 | 87,360 | 0 |
| `mullermedium` | `mullermedium.otf` | CFF | 500 | 127,508 | 0 |
| `mullerregular` | `mullerregular.ttf` | glyf | 400 | 87,080 | 0 |
| `mullerthin` | `mullerthin.ttf` | glyf | 250 | 87,768 | 0 |

All are `unitsPerEm` 1000, `capHeight` 700, `xHeight` 480, `typoAscender` 780,
`typoDescender` -220, `lineGap` 0. Cyrillic coverage is the basic Russian set
(66 codepoints; `muller_light.otf` carries 96).

`fsType` is an embedding *permission bit*, not a licence grant. `muller_light.otf`
carries 8 (editable embedding) and the rest carry 0 (installable embedding).
Neither substitutes for a EULA, and neither changes the conclusion.

### A.2 Duplicates and dead weight

- **`muller_regular.ttf` and `mullerregular.ttf` are effectively the same font**:
  identical byte length (87,080), identical `name` table (`Muller Regular` /
  `MullerRegular`), identical metrics, and they differ in only **36 bytes** —
  consistent with a re-save timestamp. Two resource names for one typeface.
- **`mullerlight.ttf` and `mullerthin.ttf` have zero references.** Dead weight
  shipping in every APK (175 KB).
- `mullerheavy` (900) and `mullerblack` (900) are both used but are distinct
  faces at the same weight class.

### A.3 References

41 references in total: 40 in XML, 1 in Kotlin.

| resource | refs | surface |
|---|---|---|
| `mullerblack` | 11 files | mobile |
| `muller_regular` | 8 files | **mobile + TV** |
| `mullerregular` | 3 files | mobile |
| `muller_bold` | 1 | mobile (`fragment_splash`) |
| `mullerheavy` | 1 | mobile (`fragment_donate`) |
| `mullermedium` | 1 | mobile (`values/themes.xml`, `BottomNavLabel`) |
| `muller_light` | 1 | **TV only** (`fragment_tv_stream_selection`) |
| `mullerlight` | 0 | unused |
| `mullerthin` | 0 | unused |

The single Kotlin reference is
`InfoFragment.kt:101` — `ResourcesCompat.getFont(requireContext(), R.font.mullerblack)`,
used to bold `GOLD` and `XTRA` inside the About Us description via a span. It is
the only place a typeface is resolved in code; there is no
`Typeface.createFromAsset`, no `assets/fonts`, and no `setTypeface` anywhere.

`mullermedium` has exactly one reference but the widest reach: it is the
`BottomNavLabel` style, so it renders on every screen of the app.

### A.4 Non-layout surfaces

**Muller does not reach any of them.**

- The playback notification is a plain `NotificationCompat.Builder` with
  `setContentTitle`/`setContentText` and no `RemoteViews`, no
  `setCustomContentView`. It renders in the system font.
- Media3 supplies the media-session notification; no custom layout is installed.
- There are no app widgets, no `assets/` directory, and no runtime typeface
  loading beyond the single `InfoFragment` span.

So the replacement is confined to `res/font/`, 40 XML attributes, one Kotlin
line, and the Figma design.

---

## B. Frozen Figma typography

Across the canonical pages and the owner-edited proposal pages, **every one of
the 250 text nodes is Muller**. There is no second family to preserve.

| weight | nodes | share |
|---|---|---|
| Medium | 134 | 53.6% |
| Regular | 86 | 34.4% |
| Bold | 27 | 10.8% |
| Black | 3 | 1.2% |

The design needs **400 / 500 / 700**. Black is used three times and can fall
back to 700 if a candidate has no 900.

### B.1 Reflow risk classes

Of the light-theme text nodes:

| count | class | consequence of a metric change |
|---|---|---|
| 22 | fixed width + hugging height | **wrapping changes frame height** — the dangerous class |
| 47 | hugging width | element grows or shrinks, pushing neighbours |
| 22 | fixed both | clipping or truncation only |

The height-elastic nodes, by frame:

| frame | nodes | widest | longest string |
|---|---|---|---|
| ABOUT US | 10 | 358dp | 185 chars — the description paragraph |
| COLLECTION | 6 | 233dp | `MIAMI HORROR FT. POOLSIDE` |
| HOME | 3 | 358dp | `TWO DOOR CINEMA CLUB` |
| COLLECTION pusto | 3 | 233dp | `TWO DOOR CINEMA CLUB` |

### B.2 The surfaces called out

| surface | nodes | styles | sizing | risk |
|---|---|---|---|---|
| **BottomNav labels** | 20 | Medium 12 only | all HUG width | Widest is `Коллекция` at 63.5dp inside a 94dp item at 390dp — but at 320dp that item is only ~72dp. A wider font eats that margin first. |
| **Screen headings** | 17 | Medium 24, Bold 28, Black 24, Regular 16/24/28 | 14 fixed / 3 HUG | Fixed-width headings clip rather than reflow; `Мятные плейлисты` is 261dp of a 358dp column. |
| **Mini-player** | 12 | Medium 15, Regular 14 | **all 12 fixed** | Deferred to B2, so it is the cheapest to get right — decide the font before building it. Overflow truncates rather than reflows. |
| **History long titles** | 12 | Regular 14/17/22, Bold 24 | 9 HUG / 3 fixed | Variable-height rows with **no ellipsis** by owner decision, so a wider font adds lines and grows the list. Highest reflow risk in the app. |
| **Buttons** | 7 | Regular 22 (×6), Regular 12 | all HUG | Hug width + 48dp padding. `Экспортировать список` is 249dp of text; the button must stay inside a 358dp column. |
| **Profile / settings** | 61 across `settings`, `settings-*`, `profile-*`, `auth-*` | mostly Medium 15 / Regular 14 | mixed | Row-based, so widening pushes values against chevrons rather than reflowing. |

### B.3 Frames to check after any font change

Canonical: `HOME`, `PLAYER`, `COLLECTION`, `COLLECTION pusto`, `ABOUT US`
(and their `_dark` twins).

Proposals: `history-content` (26 text nodes — the densest frame in the set),
`settings` (19), `report-filled` / `report-error` (19–20), `profile-authenticated`
(13), `settings-sync` (13), `settings-lastfm` (11), `auth-sign-in` (11),
`sleep-timer-custom` (9), `collection-track-sheet` (7), `find-track-sheet` (6).

---

## C. Replacement comparison plan

### C.1 Requirements

- free/open licence permitting commercial embedding in an Android APK;
- full Cyrillic;
- at least 400 / 500 / 700;
- designed for UI;
- proportions reasonably close to Muller — a geometric sans with `capHeight` near
  700/1000 and `xHeight` near 480/1000.

### C.2 Baseline, already measured

`tools/fonts/measure.mjs` reads a font's `name`, `OS/2`, `head`, `cmap` and
`hmtx` tables and reports the advance width of representative frozen strings,
normalised to a 1000-unit em. Muller today:

| string | weight | size | rendered | frozen node | delta |
|---|---|---|---|---|---|
| `Главная` | Medium | 12 | 47.6dp | 47dp | +0.6 |
| `Плеер` | Medium | 12 | 36.8dp | 38dp | −1.2 |
| `Коллекция` | Medium | 12 | 63.5dp | 64dp | −0.5 |
| `О нас` | Medium | 12 | 32.3dp | 33dp | −0.7 |
| `Мятные плейлисты` | Bold | 28 | 261.1dp | 261dp | +0.1 |
| `Экспортировать список` | Regular | 22 | 250.1dp | 249dp | +1.1 |
| `Поддержать эфир` | Regular | 22 | 192.6dp | 191dp | +1.6 |
| `Подписаться` | Regular | 22 | 137.0dp | 136dp | +1.0 |
| `WHAT YOU KNOW` | Black | 24 | 224.1dp | 219dp | **+5.1** |

Eight of nine land within 1.6dp of what Figma actually rendered, which is what
makes the method trustworthy for predicting fit. The Black outlier is the one
caveat — most likely letter-spacing on that node or a different Black cut in the
file — so the now-playing title should be confirmed on device rather than on the
number alone.

Kerning is not applied (it needs GPOS). For Cyrillic UI strings the effect is
small next to the differences between these families, but it means the numbers
are close estimates, not exact.

### C.3 Procedure

`tools/fonts/compare.mjs` implements this. It has been self-tested by pointing it
at Muller as its own candidate, which correctly reports 0.0% on every string.

1. Obtain the three families **outside the repo** (e.g. `vendor/onest`) — do not
   add anything to `app/src/main/res/font` during evaluation.
2. `node tools/fonts/compare.mjs vendor/onest vendor/manrope vendor/montserrat`
3. Read, in order:
   - **Licence, from the binary.** The harness prints the `name` table licence
     string of each file. Confirm OFL there, not from a web page. Reject anything
     whose embedded licence is absent or unclear.
   - **Coverage.** 132/132 on the probe (full Russian upper and lower, Latin,
     digits, `«»—…`). Anything less is disqualifying — the UI is Russian.
   - **Weights.** 400/500/700 must all be present as real cuts.
   - **Fit.** Any `OVERFLOWS SLOT` is a blocker. `nav-collection` at 320dp is the
     tightest case in the app.
   - **Reflow.** `about-para` must keep the same line count at 358dp, or the
     ABOUT US frame changes height.
4. Shortlist, then confirm on device at 320/360/390/412dp × Light/Dark before
   committing to one.

### C.4 The three candidates

Expectations to verify, **not** verified facts — nothing has been downloaded:

| | expected licence | Cyrillic | weights | proportions vs Muller | main worry |
|---|---|---|---|---|---|
| **Onest** | SIL OFL 1.1 | designed with Cyrillic from the start | 100–900 | closest of the three; geometric with similar cap/x proportions | youngest of the three, so fewer field-hours |
| **Manrope** | SIL OFL 1.1 | yes | 200–800 variable | slightly narrower, larger apertures | 500 comes from a variable axis; confirm a real static 500 exists |
| **Montserrat** | SIL OFL 1.1 | yes | 100–900 | **notably wider**, larger x-height | widest of the three; the likeliest to overflow nav labels and buttons |

On proportions alone Onest is the closest starting point and Montserrat the
riskiest for this layout. The measurement decides it, not this table.

---

## D. TV

TV touches Muller in exactly two files:

| file | resource | uses |
|---|---|---|
| `fragment_tv_player.xml` | `muller_regular` | 4 |
| `fragment_tv_stream_selection.xml` | `muller_light` | 1 |

`activity_tv_main.xml` and `fragment_tv_splash.xml` use no explicit font.

**`muller_light` is TV-only.** It is the one weight the mobile design never uses,
and it is a 300 — a weight the frozen mobile design does not define at all. If a
candidate family ships a 300, TV can keep its look; if not, the stream-selection
screen needs a deliberate decision (most likely 400, which will read heavier).

The five TV references cannot be assumed safe. Removing Muller means removing it
from TV too, so `muller_light.otf` and the TV uses of `muller_regular` are in
scope. TV should preserve its appearance as closely as practical, but "keep the
existing TV Muller files" is not an option if no valid licence exists.

The A0 harness (`tools/qa/tv/`) gates this: it compares the focus chain, node
ids, classes and bounds across a 15-step walk, so a font change that alters text
bounds will show up as a structural difference rather than needing to be spotted
by eye.

---

## E. Stale minSdk documentation

Actual values in `app/build.gradle`: **`minSdk 24`**, `targetSdk 36`,
`compileSdk 36`.

| location | claim | status |
|---|---|---|
| `CLAUDE.md:9` | ``minSdk 21``, ``targetSdk 35`` | **both wrong** — should be 24 and 36 |
| `MediaPlayerService.kt:165` | "minSdk here is 21" | stale comment; the API 26 guard it explains is still correct |
| `SecureNetModule.kt:24` | "honoured from API 24; on API 21-23 …" | describes a range that can no longer occur at minSdk 24 |

Correct already: `docs/PROJECT_STATUS.md:9` and `docs/ANDROID-3.6.6-PLAN.md:18`.

`CLAUDE.md` is **not edited** — it is the agent instruction file and the owner has
not authorised a change. The two code comments are also left alone; they are
cosmetic and belong in whichever change next touches those files.

---

## Migration risks

1. **History rows grow.** Variable-height rows with no ellipsis, by owner
   decision. A wider font adds lines rather than truncating, so the list gets
   taller and scroll positions shift. `history-content` is the densest frame in
   the frozen set.
2. **BottomNav at 320dp.** Labels are HUG width inside proportionally weighted
   items. `Коллекция` has ~8dp of slack at 320dp today. A font 15% wider
   consumes it and the label truncates — on the one surface visible from every
   screen.
3. **ABOUT US paragraph reflow.** 358dp fixed width, hugging height, 185
   characters. One extra line changes the frame height and pushes the donation
   CTA down.
4. **Buttons are hug-width with fixed padding.** `Экспортировать список` at 249dp
   plus 48dp padding is close to the 358dp column. Widening overflows rather than
   wrapping.
5. **Two weights at 900.** `mullerheavy` and `mullerblack` are distinct faces at
   the same weight class. Most OFL families ship one 900, so one of the two uses
   must be reassigned deliberately.
6. **`muller_light` has no mobile equivalent.** TV's 300 is not part of the
   frozen mobile scale; if the replacement lacks a 300, TV changes appearance.
7. **The design must move too.** All 250 Figma text nodes are Muller. Android and
   Figma have to change together or the canonical reference stops matching the
   app — and the frozen widths in this document become invalid as a baseline.
8. **APK size will change.** Muller is 824 KB across nine files; 175 KB of that
   is unreferenced. A four-weight replacement should land smaller, but variable
   fonts and wide Cyrillic coverage can offset that.
9. **`fsType` is not a licence.** Both values present in the current files permit
   embedding technically. That is not the question the owner is answering.

## Suggested sequence

1. Owner confirms the replacement family from the `compare.mjs` output.
2. Update the Figma text styles first, so the canonical reference stays the
   source of truth and new frozen widths can be exported.
3. Migrate Android: add the new family, repoint 40 XML attributes and the one
   Kotlin line, delete all nine Muller files including the two unused ones.
4. Re-run the phone harnesses and the TV gate against the new widths.

None of this is started. No fonts added, no production code changed.
