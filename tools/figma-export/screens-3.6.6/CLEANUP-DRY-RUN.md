# Bounded structural cleanup — DRY RUN

**Nothing has been applied, and no apply tool exists yet.** This plan is derived from the
exported snapshots, so every "current" value below is what the live file actually contains.

| | |
|---|---|
| light | `3.6.6 PROPOSALS - LIGHT`, exported 2026-08-10T07:44:22.219Z |
| dark | `3.6.6 PROPOSALS - DARK`, exported 2026-08-10T07:45:07.213Z |
| mutations | **77**, of which 0 blocked |
| pixels move | 0 |
| wrapping changes | 0 |
| visual output changes | 0 |

## Out of scope, deliberately

- the 709 default constraints
- the 1px history row anchor difference (rows 1-2 vs 3-8)
- clipsContent on the bottom sheets
- title/artist width 181 -> 179 (see the wrap analysis - it would re-wrap)
- manual stacks elsewhere, and every other owner-positioned node

## 1 · Broadcast History rows — restore the auto-layout contract (16)

| page · frame | node | current | proposed | px | wrap | visual |
|---|---|---|---|---|---|---|
| 3.6.6 PROPOSALS - LIGHT · history-content | `History Item / CRYOGEN` | layoutMode NONE, height 76 fixed, children positioned absolutely | layoutMode HORIZONTAL, itemSpacing 8, padding 14/13/14/15, primaryAxisAlignItems MIN, counterAxisAlignItems CENTER, primaryAxisSizingMode FIXED (width 358), counterAxisSizingMode AUTO (hug height) | no | no | no |
| 3.6.6 PROPOSALS - LIGHT · history-content | `History Item / Краснознамённая дивизия имени моей бабушки` | layoutMode NONE, height 116 fixed, children positioned absolutely | layoutMode HORIZONTAL, itemSpacing 8, padding 14/13/14/15, primaryAxisAlignItems MIN, counterAxisAlignItems CENTER, primaryAxisSizingMode FIXED (width 358), counterAxisSizingMode AUTO (hug height) | no | no | no |
| 3.6.6 PROPOSALS - LIGHT · history-content | `History Item / CITY WALLS` | layoutMode NONE, height 76 fixed, children positioned absolutely | layoutMode HORIZONTAL, itemSpacing 8, padding 14/14/14/14, primaryAxisAlignItems MIN, counterAxisAlignItems CENTER, primaryAxisSizingMode FIXED (width 358), counterAxisSizingMode AUTO (hug height) | no | no | no |
| 3.6.6 PROPOSALS - LIGHT · history-content | `History Item / WHAT YOU KNOW` | layoutMode NONE, height 76 fixed, children positioned absolutely | layoutMode HORIZONTAL, itemSpacing 8, padding 14/14/14/14, primaryAxisAlignItems MIN, counterAxisAlignItems CENTER, primaryAxisSizingMode FIXED (width 358), counterAxisSizingMode AUTO (hug height) | no | no | no |
| 3.6.6 PROPOSALS - LIGHT · history-content | `History Item / Прогулка по воде под дождём в конце ноября` | layoutMode NONE, height 104 fixed, children positioned absolutely | layoutMode HORIZONTAL, itemSpacing 8, padding 14/14/14/14, primaryAxisAlignItems MIN, counterAxisAlignItems CENTER, primaryAxisSizingMode FIXED (width 358), counterAxisSizingMode AUTO (hug height) | no | no | no |
| 3.6.6 PROPOSALS - LIGHT · history-content | `History Item / NORTHERN LINE` | layoutMode NONE, height 76 fixed, children positioned absolutely | layoutMode HORIZONTAL, itemSpacing 8, padding 14/14/14/14, primaryAxisAlignItems MIN, counterAxisAlignItems CENTER, primaryAxisSizingMode FIXED (width 358), counterAxisSizingMode AUTO (hug height) | no | no | no |
| 3.6.6 PROPOSALS - LIGHT · history-content | `History Item / PAPER BOATS` | layoutMode NONE, height 76 fixed, children positioned absolutely | layoutMode HORIZONTAL, itemSpacing 8, padding 14/14/14/14, primaryAxisAlignItems MIN, counterAxisAlignItems CENTER, primaryAxisSizingMode FIXED (width 358), counterAxisSizingMode AUTO (hug height) | no | no | no |
| 3.6.6 PROPOSALS - LIGHT · history-content | `History Item / SLOW BURN` | layoutMode NONE, height 76 fixed, children positioned absolutely | layoutMode HORIZONTAL, itemSpacing 8, padding 14/14/14/14, primaryAxisAlignItems MIN, counterAxisAlignItems CENTER, primaryAxisSizingMode FIXED (width 358), counterAxisSizingMode AUTO (hug height) | no | no | no |
| 3.6.6 PROPOSALS - DARK · history-content_dark | `History Item / CRYOGEN` | layoutMode NONE, height 76 fixed, children positioned absolutely | layoutMode HORIZONTAL, itemSpacing 8, padding 14/13/14/15, primaryAxisAlignItems MIN, counterAxisAlignItems CENTER, primaryAxisSizingMode FIXED (width 358), counterAxisSizingMode AUTO (hug height) | no | no | no |
| 3.6.6 PROPOSALS - DARK · history-content_dark | `History Item / Краснознамённая дивизия имени моей бабушки` | layoutMode NONE, height 116 fixed, children positioned absolutely | layoutMode HORIZONTAL, itemSpacing 8, padding 14/13/14/15, primaryAxisAlignItems MIN, counterAxisAlignItems CENTER, primaryAxisSizingMode FIXED (width 358), counterAxisSizingMode AUTO (hug height) | no | no | no |
| 3.6.6 PROPOSALS - DARK · history-content_dark | `History Item / CITY WALLS` | layoutMode NONE, height 76 fixed, children positioned absolutely | layoutMode HORIZONTAL, itemSpacing 8, padding 14/14/14/14, primaryAxisAlignItems MIN, counterAxisAlignItems CENTER, primaryAxisSizingMode FIXED (width 358), counterAxisSizingMode AUTO (hug height) | no | no | no |
| 3.6.6 PROPOSALS - DARK · history-content_dark | `History Item / WHAT YOU KNOW` | layoutMode NONE, height 76 fixed, children positioned absolutely | layoutMode HORIZONTAL, itemSpacing 8, padding 14/14/14/14, primaryAxisAlignItems MIN, counterAxisAlignItems CENTER, primaryAxisSizingMode FIXED (width 358), counterAxisSizingMode AUTO (hug height) | no | no | no |
| 3.6.6 PROPOSALS - DARK · history-content_dark | `History Item / Прогулка по воде под дождём в конце ноября` | layoutMode NONE, height 104 fixed, children positioned absolutely | layoutMode HORIZONTAL, itemSpacing 8, padding 14/14/14/14, primaryAxisAlignItems MIN, counterAxisAlignItems CENTER, primaryAxisSizingMode FIXED (width 358), counterAxisSizingMode AUTO (hug height) | no | no | no |
| 3.6.6 PROPOSALS - DARK · history-content_dark | `History Item / NORTHERN LINE` | layoutMode NONE, height 76 fixed, children positioned absolutely | layoutMode HORIZONTAL, itemSpacing 8, padding 14/14/14/14, primaryAxisAlignItems MIN, counterAxisAlignItems CENTER, primaryAxisSizingMode FIXED (width 358), counterAxisSizingMode AUTO (hug height) | no | no | no |
| 3.6.6 PROPOSALS - DARK · history-content_dark | `History Item / PAPER BOATS` | layoutMode NONE, height 76 fixed, children positioned absolutely | layoutMode HORIZONTAL, itemSpacing 8, padding 14/14/14/14, primaryAxisAlignItems MIN, counterAxisAlignItems CENTER, primaryAxisSizingMode FIXED (width 358), counterAxisSizingMode AUTO (hug height) | no | no | no |
| 3.6.6 PROPOSALS - DARK · history-content_dark | `History Item / SLOW BURN` | layoutMode NONE, height 76 fixed, children positioned absolutely | layoutMode HORIZONTAL, itemSpacing 8, padding 14/14/14/14, primaryAxisAlignItems MIN, counterAxisAlignItems CENTER, primaryAxisSizingMode FIXED (width 358), counterAxisSizingMode AUTO (hug height) | no | no | no |

**Why this is safe:** every child is already centred on the row's vertical midline (true); gaps are uniformly 8; padding top/bottom 14 reproduces the current height 76 from the tallest child 48

## 1b · Broadcast History — the inner text column must hug its height (16)

| page · frame | node | current | proposed | px | wrap | visual |
|---|---|---|---|---|---|---|
| 3.6.6 PROPOSALS - LIGHT · history-content | `Text` | VERTICAL auto-layout, primaryAxisSizingMode FIXED (height 48 locked) | primaryAxisSizingMode AUTO (hug height); width stays 179 FIXED | no | no | no |
| 3.6.6 PROPOSALS - LIGHT · history-content | `Text` | VERTICAL auto-layout, primaryAxisSizingMode FIXED (height 88 locked) | primaryAxisSizingMode AUTO (hug height); width stays 179 FIXED | no | no | no |
| 3.6.6 PROPOSALS - LIGHT · history-content | `Text` | VERTICAL auto-layout, primaryAxisSizingMode FIXED (height 48 locked) | primaryAxisSizingMode AUTO (hug height); width stays 179 FIXED | no | no | no |
| 3.6.6 PROPOSALS - LIGHT · history-content | `Text` | VERTICAL auto-layout, primaryAxisSizingMode FIXED (height 48 locked) | primaryAxisSizingMode AUTO (hug height); width stays 179 FIXED | no | no | no |
| 3.6.6 PROPOSALS - LIGHT · history-content | `Text` | VERTICAL auto-layout, primaryAxisSizingMode FIXED (height 76 locked) | primaryAxisSizingMode AUTO (hug height); width stays 179 FIXED | no | no | no |
| 3.6.6 PROPOSALS - LIGHT · history-content | `Text` | VERTICAL auto-layout, primaryAxisSizingMode FIXED (height 48 locked) | primaryAxisSizingMode AUTO (hug height); width stays 179 FIXED | no | no | no |
| 3.6.6 PROPOSALS - LIGHT · history-content | `Text` | VERTICAL auto-layout, primaryAxisSizingMode FIXED (height 48 locked) | primaryAxisSizingMode AUTO (hug height); width stays 179 FIXED | no | no | no |
| 3.6.6 PROPOSALS - LIGHT · history-content | `Text` | VERTICAL auto-layout, primaryAxisSizingMode FIXED (height 48 locked) | primaryAxisSizingMode AUTO (hug height); width stays 179 FIXED | no | no | no |
| 3.6.6 PROPOSALS - DARK · history-content_dark | `Text` | VERTICAL auto-layout, primaryAxisSizingMode FIXED (height 48 locked) | primaryAxisSizingMode AUTO (hug height); width stays 179 FIXED | no | no | no |
| 3.6.6 PROPOSALS - DARK · history-content_dark | `Text` | VERTICAL auto-layout, primaryAxisSizingMode FIXED (height 88 locked) | primaryAxisSizingMode AUTO (hug height); width stays 179 FIXED | no | no | no |
| 3.6.6 PROPOSALS - DARK · history-content_dark | `Text` | VERTICAL auto-layout, primaryAxisSizingMode FIXED (height 48 locked) | primaryAxisSizingMode AUTO (hug height); width stays 179 FIXED | no | no | no |
| 3.6.6 PROPOSALS - DARK · history-content_dark | `Text` | VERTICAL auto-layout, primaryAxisSizingMode FIXED (height 48 locked) | primaryAxisSizingMode AUTO (hug height); width stays 179 FIXED | no | no | no |
| 3.6.6 PROPOSALS - DARK · history-content_dark | `Text` | VERTICAL auto-layout, primaryAxisSizingMode FIXED (height 76 locked) | primaryAxisSizingMode AUTO (hug height); width stays 179 FIXED | no | no | no |
| 3.6.6 PROPOSALS - DARK · history-content_dark | `Text` | VERTICAL auto-layout, primaryAxisSizingMode FIXED (height 48 locked) | primaryAxisSizingMode AUTO (hug height); width stays 179 FIXED | no | no | no |
| 3.6.6 PROPOSALS - DARK · history-content_dark | `Text` | VERTICAL auto-layout, primaryAxisSizingMode FIXED (height 48 locked) | primaryAxisSizingMode AUTO (hug height); width stays 179 FIXED | no | no | no |
| 3.6.6 PROPOSALS - DARK · history-content_dark | `Text` | VERTICAL auto-layout, primaryAxisSizingMode FIXED (height 48 locked) | primaryAxisSizingMode AUTO (hug height); width stays 179 FIXED | no | no | no |

**Why this is safe:** height 48 already equals the sum of its children, so hugging resolves to the same number. Without this the row can hug but the column inside it still cannot grow.

## 2 · Text boxes shorter than one line (10)

| page · frame | node | current | proposed | px | wrap | visual |
|---|---|---|---|---|---|---|
| 3.6.6 PROPOSALS - LIGHT · sleep-timer-custom | `value` | height 28, lineHeight 32, textAutoResize NONE, textAlignVertical TOP | textAutoResize HEIGHT (height resolves to 32) | no | no | no |
| 3.6.6 PROPOSALS - LIGHT · sleep-timer-custom | `value` | height 28, lineHeight 32, textAutoResize NONE, textAlignVertical TOP | textAutoResize HEIGHT (height resolves to 32) | no | no | no |
| 3.6.6 PROPOSALS - LIGHT · sleep-timer-custom-invalid | `value` | height 28, lineHeight 32, textAutoResize NONE, textAlignVertical TOP | textAutoResize HEIGHT (height resolves to 32) | no | no | no |
| 3.6.6 PROPOSALS - LIGHT · sleep-timer-custom-invalid | `value` | height 28, lineHeight 32, textAutoResize NONE, textAlignVertical TOP | textAutoResize HEIGHT (height resolves to 32) | no | no | no |
| 3.6.6 PROPOSALS - LIGHT · profile-authenticated | `initial` | height 28, lineHeight 32, textAutoResize NONE, textAlignVertical TOP | textAutoResize HEIGHT (height resolves to 32) | no | no | no |
| 3.6.6 PROPOSALS - DARK · sleep-timer-custom_dark | `value` | height 28, lineHeight 32, textAutoResize NONE, textAlignVertical TOP | textAutoResize HEIGHT (height resolves to 32) | no | no | no |
| 3.6.6 PROPOSALS - DARK · sleep-timer-custom_dark | `value` | height 28, lineHeight 32, textAutoResize NONE, textAlignVertical TOP | textAutoResize HEIGHT (height resolves to 32) | no | no | no |
| 3.6.6 PROPOSALS - DARK · sleep-timer-custom-invalid_dark | `value` | height 28, lineHeight 32, textAutoResize NONE, textAlignVertical TOP | textAutoResize HEIGHT (height resolves to 32) | no | no | no |
| 3.6.6 PROPOSALS - DARK · sleep-timer-custom-invalid_dark | `value` | height 28, lineHeight 32, textAutoResize NONE, textAlignVertical TOP | textAutoResize HEIGHT (height resolves to 32) | no | no | no |
| 3.6.6 PROPOSALS - DARK · profile-authenticated_dark | `initial` | height 28, lineHeight 32, textAutoResize NONE, textAlignVertical TOP | textAutoResize HEIGHT (height resolves to 32) | no | no | no |

**Why this is safe:** vertical alignment is TOP, so the glyph keeps its current top edge and the box grows 4px downward into empty space. Content "1" is a single line; x/y, font, size and line height are untouched.

## 3 · Hidden Last.fm leftovers (3)

| page · frame | node | current | proposed | px | wrap | visual |
|---|---|---|---|---|---|---|
| 3.6.6 PROPOSALS - LIGHT · settings-lastfm | `Asset slot / logo/lastfm (PENDING)` | hidden placeholder frame 24x24 at 16,18, visible=false | delete | no | no | no |
| 3.6.6 PROPOSALS - LIGHT · settings-lastfm | `Asset slot / logo/lastfm (PENDING)` | hidden placeholder frame 24x24 at 16,18, visible=false | delete | no | no | no |
| 3.6.6 PROPOSALS - DARK · settings_dark | `Asset slot / logo/lastfm (PENDING)` | hidden placeholder frame 24x24 at 16,20, visible=false | delete | no | no | no |

**Why this is safe:** already invisible, and a visible real mark sits beside it in the same parent (true). Deleting an invisible node cannot change the rendering.

## 5 · Name the 16 future avatar locations (32)

| page · frame | node | current | proposed | px | wrap | visual |
|---|---|---|---|---|---|---|
| 3.6.6 PROPOSALS - LIGHT · profile-avatar | `Avatar cell 1` | name "Avatar cell 1", 76x76 at 16,244, 0 children | name "Avatar cell 01 · avatar/m3-01" — geometry, fills and strokes untouched | no | no | no |
| 3.6.6 PROPOSALS - LIGHT · profile-avatar | `Avatar cell 2` | name "Avatar cell 2", 76x76 at 110,244, 0 children | name "Avatar cell 02 · avatar/m3-02" — geometry, fills and strokes untouched | no | no | no |
| 3.6.6 PROPOSALS - LIGHT · profile-avatar | `Avatar cell 3` | name "Avatar cell 3", 76x76 at 204,244, 0 children | name "Avatar cell 03 · avatar/m3-03" — geometry, fills and strokes untouched | no | no | no |
| 3.6.6 PROPOSALS - LIGHT · profile-avatar | `Avatar cell 4` | name "Avatar cell 4", 76x76 at 298,244, 0 children | name "Avatar cell 04 · avatar/m3-04" — geometry, fills and strokes untouched | no | no | no |
| 3.6.6 PROPOSALS - LIGHT · profile-avatar | `Avatar cell 5` | name "Avatar cell 5", 76x76 at 16,338, 0 children | name "Avatar cell 05 · avatar/m3-05" — geometry, fills and strokes untouched | no | no | no |
| 3.6.6 PROPOSALS - LIGHT · profile-avatar | `Avatar cell 6` | name "Avatar cell 6", 76x76 at 110,338, 1 children | name "Avatar cell 06 · avatar/m3-06" — geometry, fills and strokes untouched | no | no | no |
| 3.6.6 PROPOSALS - LIGHT · profile-avatar | `Avatar cell 7` | name "Avatar cell 7", 76x76 at 204,338, 0 children | name "Avatar cell 07 · avatar/m3-07" — geometry, fills and strokes untouched | no | no | no |
| 3.6.6 PROPOSALS - LIGHT · profile-avatar | `Avatar cell 8` | name "Avatar cell 8", 76x76 at 298,338, 0 children | name "Avatar cell 08 · avatar/m3-08" — geometry, fills and strokes untouched | no | no | no |
| 3.6.6 PROPOSALS - LIGHT · profile-avatar | `Avatar cell 9` | name "Avatar cell 9", 76x76 at 16,432, 0 children | name "Avatar cell 09 · avatar/m3-09" — geometry, fills and strokes untouched | no | no | no |
| 3.6.6 PROPOSALS - LIGHT · profile-avatar | `Avatar cell 10` | name "Avatar cell 10", 76x76 at 110,432, 0 children | name "Avatar cell 10 · avatar/m3-10" — geometry, fills and strokes untouched | no | no | no |
| 3.6.6 PROPOSALS - LIGHT · profile-avatar | `Avatar cell 11` | name "Avatar cell 11", 76x76 at 204,432, 0 children | name "Avatar cell 11 · avatar/m3-11" — geometry, fills and strokes untouched | no | no | no |
| 3.6.6 PROPOSALS - LIGHT · profile-avatar | `Avatar cell 12` | name "Avatar cell 12", 76x76 at 298,432, 0 children | name "Avatar cell 12 · avatar/m3-12" — geometry, fills and strokes untouched | no | no | no |
| 3.6.6 PROPOSALS - LIGHT · profile-avatar | `Avatar cell 13` | name "Avatar cell 13", 76x76 at 16,526, 0 children | name "Avatar cell 13 · avatar/m3-13" — geometry, fills and strokes untouched | no | no | no |
| 3.6.6 PROPOSALS - LIGHT · profile-avatar | `Avatar cell 14` | name "Avatar cell 14", 76x76 at 110,526, 0 children | name "Avatar cell 14 · avatar/m3-14" — geometry, fills and strokes untouched | no | no | no |
| 3.6.6 PROPOSALS - LIGHT · profile-avatar | `Avatar cell 15` | name "Avatar cell 15", 76x76 at 204,526, 0 children | name "Avatar cell 15 · avatar/m3-15" — geometry, fills and strokes untouched | no | no | no |
| 3.6.6 PROPOSALS - LIGHT · profile-avatar | `Avatar cell 16` | name "Avatar cell 16", 76x76 at 298,526, 0 children | name "Avatar cell 16 · avatar/m3-16" — geometry, fills and strokes untouched | no | no | no |
| 3.6.6 PROPOSALS - DARK · profile-avatar_dark | `Avatar cell 1` | name "Avatar cell 1", 76x76 at 16,244, 0 children | name "Avatar cell 01 · avatar/m3-01" — geometry, fills and strokes untouched | no | no | no |
| 3.6.6 PROPOSALS - DARK · profile-avatar_dark | `Avatar cell 2` | name "Avatar cell 2", 76x76 at 110,244, 0 children | name "Avatar cell 02 · avatar/m3-02" — geometry, fills and strokes untouched | no | no | no |
| 3.6.6 PROPOSALS - DARK · profile-avatar_dark | `Avatar cell 3` | name "Avatar cell 3", 76x76 at 204,244, 0 children | name "Avatar cell 03 · avatar/m3-03" — geometry, fills and strokes untouched | no | no | no |
| 3.6.6 PROPOSALS - DARK · profile-avatar_dark | `Avatar cell 4` | name "Avatar cell 4", 76x76 at 298,244, 0 children | name "Avatar cell 04 · avatar/m3-04" — geometry, fills and strokes untouched | no | no | no |
| 3.6.6 PROPOSALS - DARK · profile-avatar_dark | `Avatar cell 5` | name "Avatar cell 5", 76x76 at 16,338, 0 children | name "Avatar cell 05 · avatar/m3-05" — geometry, fills and strokes untouched | no | no | no |
| 3.6.6 PROPOSALS - DARK · profile-avatar_dark | `Avatar cell 6` | name "Avatar cell 6", 76x76 at 110,338, 1 children | name "Avatar cell 06 · avatar/m3-06" — geometry, fills and strokes untouched | no | no | no |
| 3.6.6 PROPOSALS - DARK · profile-avatar_dark | `Avatar cell 7` | name "Avatar cell 7", 76x76 at 204,338, 0 children | name "Avatar cell 07 · avatar/m3-07" — geometry, fills and strokes untouched | no | no | no |
| 3.6.6 PROPOSALS - DARK · profile-avatar_dark | `Avatar cell 8` | name "Avatar cell 8", 76x76 at 298,338, 0 children | name "Avatar cell 08 · avatar/m3-08" — geometry, fills and strokes untouched | no | no | no |
| 3.6.6 PROPOSALS - DARK · profile-avatar_dark | `Avatar cell 9` | name "Avatar cell 9", 76x76 at 16,432, 0 children | name "Avatar cell 09 · avatar/m3-09" — geometry, fills and strokes untouched | no | no | no |
| 3.6.6 PROPOSALS - DARK · profile-avatar_dark | `Avatar cell 10` | name "Avatar cell 10", 76x76 at 110,432, 0 children | name "Avatar cell 10 · avatar/m3-10" — geometry, fills and strokes untouched | no | no | no |
| 3.6.6 PROPOSALS - DARK · profile-avatar_dark | `Avatar cell 11` | name "Avatar cell 11", 76x76 at 204,432, 0 children | name "Avatar cell 11 · avatar/m3-11" — geometry, fills and strokes untouched | no | no | no |
| 3.6.6 PROPOSALS - DARK · profile-avatar_dark | `Avatar cell 12` | name "Avatar cell 12", 76x76 at 298,432, 0 children | name "Avatar cell 12 · avatar/m3-12" — geometry, fills and strokes untouched | no | no | no |
| 3.6.6 PROPOSALS - DARK · profile-avatar_dark | `Avatar cell 13` | name "Avatar cell 13", 76x76 at 16,526, 0 children | name "Avatar cell 13 · avatar/m3-13" — geometry, fills and strokes untouched | no | no | no |
| 3.6.6 PROPOSALS - DARK · profile-avatar_dark | `Avatar cell 14` | name "Avatar cell 14", 76x76 at 110,526, 0 children | name "Avatar cell 14 · avatar/m3-14" — geometry, fills and strokes untouched | no | no | no |
| 3.6.6 PROPOSALS - DARK · profile-avatar_dark | `Avatar cell 15` | name "Avatar cell 15", 76x76 at 204,526, 0 children | name "Avatar cell 15 · avatar/m3-15" — geometry, fills and strokes untouched | no | no | no |
| 3.6.6 PROPOSALS - DARK · profile-avatar_dark | `Avatar cell 16` | name "Avatar cell 16", 76x76 at 298,526, 0 children | name "Avatar cell 16 · avatar/m3-16" — geometry, fills and strokes untouched | no | no | no |

**Why this is safe:** a layer name is not rendered. This restores the record of which asset belongs in which cell now that the PENDING slots were deleted.

