# Recovery cleanup — DRY RUN

**Nothing applied.** Generated from the post-Apply snapshots.

| | |
|---|---|
| light | `3.6.6 PROPOSALS - LIGHT`, exported 2026-08-10T08:37:52.026Z |
| dark | `3.6.6 PROPOSALS - DARK`, exported 2026-08-10T08:37:33.568Z |
| recovery mutations | **26**, 0 blocked |
| already complete | history-text-hug 16, lastfm-leftover 3, avatar-naming 32 |

## Root cause

- **History rows.** Figma measures auto-layout padding from the inside of an INSIDE-aligned stroke and adds the stroke to a hugged size. The rows carry a 1px INSIDE stroke, so padding copied from the measured child offsets placed every child at stroke+padding (+1,+1) and hugged to 2*stroke+padTop+content+padBottom (+2). Padding is now reduced by the stroke weight on every side.
- **Text boxes.** Any write to a TEXT node requires its font to be loaded first. The plan now records each node's family and style, the plugin loads them before writing, and a MIXED fontName blocks the mutation rather than being guessed at.
- **Revert gap.** The failed revert restored layoutMode and child offsets but not the frame height, so all 16 rows are still 2px taller than the owner designed. Recovery targets history-baseline.json, which restores them.

## history-row-autolayout (16)

| frame | node | current | proposed | px vs owner | wrap | height Δ vs live |
|---|---|---|---|---|---|---|
| history-content | `History Item / CRYOGEN` | layoutMode NONE, 358x78, stroke 1px INSIDE — 2px taller than the owner's 76 | HORIZONTAL auto-layout, gap 8, padding 13/12/13/14 (stroke-compensated), counterAxisAlignItems CENTER, height hugs to 76 | no | no | -2 |
| history-content | `History Item / Краснознамённая дивизия имени моей бабушки` | layoutMode NONE, 358x118, stroke 1px INSIDE — 2px taller than the owner's 116 | HORIZONTAL auto-layout, gap 8, padding 13/12/13/14 (stroke-compensated), counterAxisAlignItems CENTER, height hugs to 116 | no | no | -2 |
| history-content | `History Item / CITY WALLS` | layoutMode NONE, 358x78, stroke 1px INSIDE — 2px taller than the owner's 76 | HORIZONTAL auto-layout, gap 8, padding 13/13/13/13 (stroke-compensated), counterAxisAlignItems CENTER, height hugs to 76 | no | no | -2 |
| history-content | `History Item / WHAT YOU KNOW` | layoutMode NONE, 358x78, stroke 1px INSIDE — 2px taller than the owner's 76 | HORIZONTAL auto-layout, gap 8, padding 13/13/13/13 (stroke-compensated), counterAxisAlignItems CENTER, height hugs to 76 | no | no | -2 |
| history-content | `History Item / Прогулка по воде под дождём в конце ноября` | layoutMode NONE, 358x106, stroke 1px INSIDE — 2px taller than the owner's 104 | HORIZONTAL auto-layout, gap 8, padding 13/13/13/13 (stroke-compensated), counterAxisAlignItems CENTER, height hugs to 104 | no | no | -2 |
| history-content | `History Item / NORTHERN LINE` | layoutMode NONE, 358x78, stroke 1px INSIDE — 2px taller than the owner's 76 | HORIZONTAL auto-layout, gap 8, padding 13/13/13/13 (stroke-compensated), counterAxisAlignItems CENTER, height hugs to 76 | no | no | -2 |
| history-content | `History Item / PAPER BOATS` | layoutMode NONE, 358x78, stroke 1px INSIDE — 2px taller than the owner's 76 | HORIZONTAL auto-layout, gap 8, padding 13/13/13/13 (stroke-compensated), counterAxisAlignItems CENTER, height hugs to 76 | no | no | -2 |
| history-content | `History Item / SLOW BURN` | layoutMode NONE, 358x78, stroke 1px INSIDE — 2px taller than the owner's 76 | HORIZONTAL auto-layout, gap 8, padding 13/13/13/13 (stroke-compensated), counterAxisAlignItems CENTER, height hugs to 76 | no | no | -2 |
| history-content_dark | `History Item / CRYOGEN` | layoutMode NONE, 358x78, stroke 1px INSIDE — 2px taller than the owner's 76 | HORIZONTAL auto-layout, gap 8, padding 13/12/13/14 (stroke-compensated), counterAxisAlignItems CENTER, height hugs to 76 | no | no | -2 |
| history-content_dark | `History Item / Краснознамённая дивизия имени моей бабушки` | layoutMode NONE, 358x118, stroke 1px INSIDE — 2px taller than the owner's 116 | HORIZONTAL auto-layout, gap 8, padding 13/12/13/14 (stroke-compensated), counterAxisAlignItems CENTER, height hugs to 116 | no | no | -2 |
| history-content_dark | `History Item / CITY WALLS` | layoutMode NONE, 358x78, stroke 1px INSIDE — 2px taller than the owner's 76 | HORIZONTAL auto-layout, gap 8, padding 13/13/13/13 (stroke-compensated), counterAxisAlignItems CENTER, height hugs to 76 | no | no | -2 |
| history-content_dark | `History Item / WHAT YOU KNOW` | layoutMode NONE, 358x78, stroke 1px INSIDE — 2px taller than the owner's 76 | HORIZONTAL auto-layout, gap 8, padding 13/13/13/13 (stroke-compensated), counterAxisAlignItems CENTER, height hugs to 76 | no | no | -2 |
| history-content_dark | `History Item / Прогулка по воде под дождём в конце ноября` | layoutMode NONE, 358x106, stroke 1px INSIDE — 2px taller than the owner's 104 | HORIZONTAL auto-layout, gap 8, padding 13/13/13/13 (stroke-compensated), counterAxisAlignItems CENTER, height hugs to 104 | no | no | -2 |
| history-content_dark | `History Item / NORTHERN LINE` | layoutMode NONE, 358x78, stroke 1px INSIDE — 2px taller than the owner's 76 | HORIZONTAL auto-layout, gap 8, padding 13/13/13/13 (stroke-compensated), counterAxisAlignItems CENTER, height hugs to 76 | no | no | -2 |
| history-content_dark | `History Item / PAPER BOATS` | layoutMode NONE, 358x78, stroke 1px INSIDE — 2px taller than the owner's 76 | HORIZONTAL auto-layout, gap 8, padding 13/13/13/13 (stroke-compensated), counterAxisAlignItems CENTER, height hugs to 76 | no | no | -2 |
| history-content_dark | `History Item / SLOW BURN` | layoutMode NONE, 358x78, stroke 1px INSIDE — 2px taller than the owner's 76 | HORIZONTAL auto-layout, gap 8, padding 13/13/13/13 (stroke-compensated), counterAxisAlignItems CENTER, height hugs to 76 | no | no | -2 |

stroke 1px INSIDE: content box is inset by 1px, so padding 14 puts the first child at 15 and the hugged height is 2x1 + 13 + 48 + 13 = 76. All 4 predicted child positions match the owner's geometry.

## text-box-hug (10)

| frame | node | current | proposed | px vs owner | wrap | height Δ vs live |
|---|---|---|---|---|---|---|
| sleep-timer-custom | `value` | height 28, lineHeight 32, textAutoResize NONE, font Muller Medium | textAutoResize HEIGHT (height resolves to 32); font loaded first, family and style untouched | no | no | 0 |
| sleep-timer-custom | `value` | height 28, lineHeight 32, textAutoResize NONE, font Muller Medium | textAutoResize HEIGHT (height resolves to 32); font loaded first, family and style untouched | no | no | 0 |
| sleep-timer-custom-invalid | `value` | height 28, lineHeight 32, textAutoResize NONE, font Muller Medium | textAutoResize HEIGHT (height resolves to 32); font loaded first, family and style untouched | no | no | 0 |
| sleep-timer-custom-invalid | `value` | height 28, lineHeight 32, textAutoResize NONE, font Muller Medium | textAutoResize HEIGHT (height resolves to 32); font loaded first, family and style untouched | no | no | 0 |
| profile-authenticated | `initial` | height 28, lineHeight 32, textAutoResize NONE, font Muller Medium | textAutoResize HEIGHT (height resolves to 32); font loaded first, family and style untouched | no | no | 0 |
| sleep-timer-custom_dark | `value` | height 28, lineHeight 32, textAutoResize NONE, font Muller Medium | textAutoResize HEIGHT (height resolves to 32); font loaded first, family and style untouched | no | no | 0 |
| sleep-timer-custom_dark | `value` | height 28, lineHeight 32, textAutoResize NONE, font Muller Medium | textAutoResize HEIGHT (height resolves to 32); font loaded first, family and style untouched | no | no | 0 |
| sleep-timer-custom-invalid_dark | `value` | height 28, lineHeight 32, textAutoResize NONE, font Muller Medium | textAutoResize HEIGHT (height resolves to 32); font loaded first, family and style untouched | no | no | 0 |
| sleep-timer-custom-invalid_dark | `value` | height 28, lineHeight 32, textAutoResize NONE, font Muller Medium | textAutoResize HEIGHT (height resolves to 32); font loaded first, family and style untouched | no | no | 0 |
| profile-authenticated_dark | `initial` | height 28, lineHeight 32, textAutoResize NONE, font Muller Medium | textAutoResize HEIGHT (height resolves to 32); font loaded first, family and style untouched | no | no | 0 |

vertical alignment is TOP, so the glyph keeps its top edge and the box grows 4px down into empty space. "1" is one line.

