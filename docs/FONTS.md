# Muller font resources

The 3.6.6 design is set entirely in Muller. This records which file is which,
because the folder has grown two naming conventions and one genuine duplicate,
and picking the wrong resource is easy.

Licence: Fontfabric LLC, designed by Radomir Tinkov. Owner-supplied.

## What is in `app/src/main/res/font/`

| resource | file | format | weight | face name | Cyrillic |
|---|---|---|---|---|---|
| `mullerthin` | `mullerthin.ttf` | TrueType | 250 | Muller Thin | 66/66 |
| `muller_light` | `muller_light.otf` | CFF | 300 | Muller / Light | 66/66 |
| `mullerlight` | `mullerlight.ttf` | TrueType | 300 | Muller Light | 66/66 |
| `muller_regular` | `muller_regular.ttf` | TrueType | 400 | Muller Regular | 66/66 |
| `mullerregular` | `mullerregular.ttf` | TrueType | 400 | Muller Regular | 66/66 |
| **`mullermedium`** | **`mullermedium.otf`** | **CFF** | **500** | **Muller / Medium** | **66/66** |
| `muller_bold` | `muller_bold.ttf` | TrueType | 700 | Muller Bold | 66/66 |
| `mullerheavy` | `mullerheavy.ttf` | TrueType | 900 | Muller Heavy | 66/66 |
| `mullerblack` | `mullerblack.ttf` | TrueType | 900 | Muller Black | 66/66 |

All nine carry the full Cyrillic range А–Я, а–я, Ё and ё at 1000 units/em.

## The design ramp maps to

| design | weight | resource |
|---|---|---|
| Muller Regular | 400 | `mullerregular` |
| Muller Medium | 500 | `mullermedium` |
| Muller Bold | 700 | `muller_bold` |

## Two conventions, one duplicate

Names come in `muller_x` and `mullerx` forms. That is historical, not meaningful,
and it hides a real duplicate:

- **`muller_regular` and `mullerregular` are both Muller Regular 400.** Not
  byte-identical — different builds of the same face — but the same weight and
  the same metrics, so they render the same and ship twice.
- `muller_light` and `mullerlight` are the same weight in different formats
  (CFF vs TrueType).

**Neither is deduplicated here.** `muller_light` and `muller_regular` are the two
fonts the TV surface uses, and A1 is not allowed to change TV. Collapsing them is
typography work for the mobile migration, and it needs the TV regression harness
run afterwards.

## Unreferenced today

`mullerlight`, `mullerthin`, and `mullermedium` until the migration uses it.
`mullermedium` is landed ahead of its first use on purpose: the design is frozen
against it, and the asset should be reviewable on its own rather than buried in a
screen change.

## Do not remove

`muller_light` and `muller_regular` are consumed by the TV layouts
(`fragment_tv_stream_selection.xml`, `fragment_tv_player.xml`). Deleting or
repurposing either changes TV, which is a regression until TV is deliberately
taken into scope.
