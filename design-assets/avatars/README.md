# Avatar artwork — staging

Owner-supplied only. **Nothing in this folder is generated, and no placeholder
avatar goes into Android resources.** Until the real assets land, the picker
ships with empty cells or the whole avatar feature is deferred — an invented
avatar shipping to users is worse than a missing one.

The folder is expected to stay empty apart from this file for now.

## Naming contract

Sixteen files, matching the sixteen cells in the frozen design. The Figma cell
names carry the same keys, so a file maps to a cell by name alone.

| file | Figma cell key |
|---|---|
| `avatar_m3_01.svg` | `avatar/m3-01` |
| `avatar_m3_02.svg` | `avatar/m3-02` |
| `avatar_m3_03.svg` | `avatar/m3-03` |
| `avatar_m3_04.svg` | `avatar/m3-04` |
| `avatar_m3_05.svg` | `avatar/m3-05` |
| `avatar_m3_06.svg` | `avatar/m3-06` |
| `avatar_m3_07.svg` | `avatar/m3-07` |
| `avatar_m3_08.svg` | `avatar/m3-08` |
| `avatar_m3_09.svg` | `avatar/m3-09` |
| `avatar_m3_10.svg` | `avatar/m3-10` |
| `avatar_m3_11.svg` | `avatar/m3-11` |
| `avatar_m3_12.svg` | `avatar/m3-12` |
| `avatar_m3_13.svg` | `avatar/m3-13` |
| `avatar_m3_14.svg` | `avatar/m3-14` |
| `avatar_m3_15.svg` | `avatar/m3-15` |
| `avatar_m3_16.svg` | `avatar/m3-16` |

Zero-padded two digits, lower snake case, `.svg`. The Android resource name that
follows from it is the same string — `R.drawable.avatar_m3_01` — so the chain
from Figma cell to file to resource never needs a lookup table.

## What the design already fixes

From the frozen `profile-avatar` screen, both themes:

- 4×4 grid, **76px cells, 18px gutters** (4·76 + 3·18 = 358 = content width)
- artwork sits at **64×64** inside the 76px ring, 6px inset
- selected cell: 2px `primary` ring plus a 24px check badge at the bottom right
- the current-avatar preview above the grid is **96px**, artwork 80×80

Geometry is final. Only the artwork is outstanding.

## When assets arrive

1. Drop the sixteen SVGs here.
2. Convert to vector drawables in `app/src/main/res/drawable/` under the same names.
3. Place them in the Figma cells and record the node ids in
   `tools/figma-export/screens-3.6.6/spec/assets.json`, which still lists the
   `avatar/m3-*` family as `PENDING_OWNER`.
4. Re-export and re-run `audit-live.mjs`; the "avatar cells are empty rings"
   finding should disappear.

Licensing note: the intent is the official Material 3 Design Kit avatars. Confirm
the licence permits redistribution in a shipped APK before committing anything
here — the same rule that applied to the Muller files.
