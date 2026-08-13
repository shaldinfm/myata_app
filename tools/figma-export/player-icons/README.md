# PLAYER icon vectors — read-only exporter

Pulls the **literal** vector geometry for the frozen PLAYER icons out of Figma and
emits ready-to-paste Android VectorDrawable XML.

## Why it exists

The canonical snapshot exporter (`../canonical`) records a vector's box, position
and fills but never its geometry — it reads no `vectorPaths` and calls no
`exportAsync`. So every icon on the PLAYER screen reached the app as an **exact
box around a reconstructed outline**. This plugin closes that gap, exactly as
`../nav-icons` did for the bottom navigation, and it is a copy of that plugin's
machinery with the PLAYER nodes in it.

## What it exports

| icon | drawable it replaces | frozen box |
|---|---|---|
| `play_pause` | `ic_player_play` | 23.33 × 23.33 |
| `like` | `ic_player_like` | 24.5 × 23.33 |
| `swipe_active` | `ic_player_swipe_active` | 8.53 × 8.42 |
| `swipe_inactive` | `ic_player_swipe_inactive` | 8.42 × 8.42 |
| `dislike` *(deferred)* | — | 24.5 × 23.33 |
| `overflow` *(deferred)* | — | 4 × 16 |

Each is read from both the light and the dark page, and the run reports whether
the two agree.

**There is no separate Pause node.** `Controls > play/pause` holds one glyph per
page — the frozen frame shows a single state and the other one is not in the
file. Whichever state that node turns out to be comes back with its geometry;
the app's other glyph stays derived until a Pause node exists to export.

## Run it

1. Open the Radio Myata file in **Figma Desktop** (browser Figma cannot load local
   plugins).
2. `Plugins` → `Development` → `Import plugin from manifest…`
3. Choose `tools/figma-export/player-icons/manifest.json`.
4. `Plugins` → `Development` → `Radio Myata · PLAYER icon vectors (read-only)`.
5. Press **Extract**, read the summary, then **Download JSON**.
6. Save it as `tools/figma-export/player-icons/player-icons.json` and commit it.

The `drawables` object in that file holds the XML for each icon, colours emitted
as `#FFFFFF` placeholders because the screen tints by role and by state.

## Guarantees

Read-only. It loads pages, resolves nodes and reads properties: no create, no
mutation, no plugin data, `networkAccess: none`. Running it cannot change the
document.

## If an id has gone stale

Ids come from the canonical snapshot and are listed in
[docs/PLAYER-3.6.6.md](../../../docs/PLAYER-3.6.6.md#the-icons-the-export-cannot-give-us).
When one no longer resolves, the plugin walks the page down the node trail by
name instead and says so in `resolvedBy`. Hidden nodes are skipped on the way —
each frozen page carries a second, stale `Controls` row, and the header's leading
button is hidden.
