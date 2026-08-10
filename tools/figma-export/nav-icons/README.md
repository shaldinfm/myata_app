# BottomNav icon vectors (read-only extraction)

The four frozen 3.6.6 bottom-navigation icons cannot be rebuilt from anything in
this repository. The canonical snapshot exporter records each icon's name, type,
size and fills, but never its geometry — there is no `vectorPaths`,
`vectorNetwork` or `exportAsync` anywhere in `canonical/code.js`, and no SVG or
PNG render of the canonical pages is committed. So the exact vectors exist only
in the live Figma file.

The two ways to close that gap are the Figma REST API, which the owner has ruled
out, and a plugin run inside the file — this one.

## Running it

1. Open the frozen 3.6.6 Figma file.
2. Plugins → Development → Import plugin from manifest… → pick this
   `manifest.json`.
3. Run **Radio Myata · BottomNav icon vectors**, press **Extract**, then
   **Download JSON**.
4. Hand back `nav-icons.json`.

It never writes to the document.

## How it reads geometry

Geometry comes from `exportAsync` (SVG), not from `vectorPaths`. SVG export is
exact and, unlike `vectorPaths`, it still works when an icon is a boolean
operation, a group or a frame — which is what an owner-edited page can easily
contain. `vectorPaths` is still read when available, because its winding rule
maps onto `android:fillType`, but nothing depends on it.

Three failure modes are handled explicitly, because the first version of this
plugin died on the first of them:

- **Unloaded pages.** Under dynamic page loading, `getNodeByIdAsync` resolves a
  node whose `id`, `type` and `name` are readable while every richer property
  throws on access. Loading is now mandatory: `figma.loadAllPagesAsync()` when it
  exists, otherwise `page.loadAsync()` per page, and the mechanism used is
  reported in the output. Probing for those APIs is itself guarded, since on an
  unloaded node even the probe can throw.
- **Stale node ids.** Owner edits renumber nodes. If an id fails to resolve, or
  resolves to something without geometry, the plugin walks the named page to its
  `BottomNavBar` and takes the Nth item's first geometry-bearing descendant —
  the same route the ids were originally derived from. The output records
  `resolvedBy` as `id` or `structure` for every icon.
- **Refused properties.** Every property read goes through a guard that records
  the node id, name, type, the property and the exact error. A partial run
  therefore says what to fix instead of producing a stack trace.

## What it reads

Each icon is read in three places, so the output settles a question the metadata
could not answer: whether the geometry changes between states, or only the fill.

| icon | active | inactive | dark |
|---|---|---|---|
| `home` | `2393:1632` (HOME) | `2396:30917` (PLAYER) | `2444:10354` (HOME_dark, inactive) |
| `player` | `2396:30922` (PLAYER) | `2393:1637` (HOME) | `2444:18363` (PLAYER_dark, active) |
| `collection` | `2407:31515` (COLLECTION) | `2393:1642` (HOME) | `2444:18443` (COLLECTION_dark, active) |
| `about` | `2417:95` (ABOUT US) | `2393:1647` (HOME) | `2444:18666` (ABOUT US_dark, active) |

Active instances are the ones carrying the `#00723d` pill content colour;
inactive carry `#42474e` in light and `#b3c4d1` in dark.

## What comes back

`nav-icons.json` holds, per node, the size, fills, strokes and `vectorPaths`,
plus a `drawables` map of VectorDrawable XML keyed by target filename
(`ic_nav_home.xml`, `ic_nav_player.xml`, `ic_nav_collection.xml`,
`ic_nav_about.xml`).

Fill colours are emitted as `#FFFFFF` placeholders on purpose: `MainActivity`
tints the icons at runtime for the active and inactive states, so a colour baked
into the drawable would fight that.

## Two things the output will decide

**Whether Home needs one drawable or two.** In the canonical snapshot the Home
icon is 16×18 on every page, in every state, while the other three are 20×20
everywhere. Only its fill colour changes between active and inactive. If the
geometry also matches across those nodes, then the frozen design has a single
Home vector and "filled when active" is a property of that one shape, not a
second outline variant to swap in.

**Whether any icon uses EVENODD winding.** `android:fillType` requires API 24 on
a platform VectorDrawable. This project's `minSdk` is **24** (verified in
`app/build.gradle`; the figure of 21 in `CLAUDE.md` is stale), so even-odd is
supported natively and needs no AppCompat workaround. The plugin still reports it,
because it changes how a path must be read.
