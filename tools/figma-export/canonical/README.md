# Canonical design snapshot — read-only Figma exporter

Exports the **real Figma project** to machine-readable JSON, so the canonical design
stops being something we reconstruct from PNGs and becomes something we can diff.

Schema version **1.0.0**.

## Why this exists

The repository holds three representations of the dark design — `dark-theme/code.js`
(the plugin that builds the frames), `dark-theme/preview.html` (which produced the
approved PNGs) and `dark-theme/dark-screens.json` (tokens and inventory). None of them is
the Figma file itself, and none is guaranteed to still match it.

This exporter produces the missing fourth thing: a snapshot of what Figma actually
contains right now. **Figma wins on any disagreement.**

## Guarantees

- **Read-only.** The plugin never assigns to a node property, never creates or deletes a
  node, and never writes plugin data. Running it cannot change the document.
- **No network.** `networkAccess` is `none` in the manifest.
- **No artwork.** Image fills are recorded as hash, scale mode and transform only. Image
  bytes are never requested (`getBytesAsync` / `exportAsync` are not called), so no
  copyrighted cover art can reach the repository.
- **Nothing hardcoded.** No file key, no node ids, no filesystem paths. Whatever page is
  open when you press Export is what gets exported.

## Install and run (Figma Desktop)

1. Open the Radio Myata file in **Figma Desktop** (browser Figma cannot load local plugins).
2. Open the **page you consider canonical** for the existing screens.
3. `Plugins` → `Development` → `Import plugin from manifest…`
4. Choose `tools/figma-export/canonical/manifest.json` from this repository.
5. `Plugins` → `Development` → `Radio Myata · Canonical Snapshot (read-only)`.
6. Choose the scope:
   - **Whole current page** (default) — everything on the open page.
   - **Selected frames only** — select the frames first; falls back to the whole page if
     the selection is empty.
7. Press **Export snapshot**, check the summary (file, page, frame count, variable count),
   then press **Download JSON**.

## Where the file goes

Save the download as:

```
tools/figma-export/canonical/figma-canonical.json
```

Exact filename, exact location. Commit it — it is the reference every later comparison is
made against.

If you export more than one page (for example a light page and a dark page), save each as
`figma-canonical-<page>.json` and say which one is canonical for the existing screens.

## What the snapshot contains

Top level:

| Field | Meaning |
|---|---|
| `schemaVersion`, `pluginVersion` | Format and exporter version |
| `exportedAt` | ISO timestamp |
| `source` | File name, file key when Figma exposes it, page name and id, scope, top-level frame count, the page's explicit variable modes |
| `variables` | Every bound variable, resolved once (see below) |
| `frames` | The top-level frames, each with its full subtree |

Per node, recursively:

- **Identity** — `id`, `name`, `type`, `visible`, `locked`
- **Geometry** — `x`, `y`, `width`, `height`, `rotation`, `opacity`, `blendMode`, `clipsContent`
- **Auto layout** — `layoutMode`, `itemSpacing`, `counterAxisSpacing`, per-side `padding`,
  primary/counter axis align and sizing modes, `layoutWrap`, plus `layoutAlign`,
  `layoutGrow`, `layoutSizingHorizontal/Vertical`
- **Constraints** — horizontal and vertical
- **Corners** — a single `cornerRadius`, or all four corners when they differ
- **Fills / strokes / effects** — paint type, colour as hex, opacity, blend mode, gradient
  stops and transform; stroke weight (per side when mixed), align and dash pattern; effect
  type, colour, offset, radius, spread
- **Text** — `characters`, font family and style, size, weight, line height, letter
  spacing, horizontal and vertical alignment, auto-resize, case, decoration, paragraph
  spacing and indent, and `textStyleId`
- **Components** — for `COMPONENT` / `COMPONENT_SET`: key, description, variant properties
  and variant group properties; for `INSTANCE`: the main component (id, name, key),
  `variantProperties` and `componentProperties`
- **Bindings** — raw `boundVariables` on the node, plus `fillStyleId`, `strokeStyleId`,
  `effectStyleId`

Anything Figma reports as mixed is exported as the string `"MIXED"`, except corner radius
and stroke weight, which are expanded per side.

### Variables

Every variable referenced anywhere in the export is resolved once into the top-level
`variables` map, and nodes reference it by id. Each entry carries:

`name`, `resolvedType`, its `collection` (id and name), all `modes` in that collection,
the `exportedModeId` / `exportedModeName` this run resolved against, the
`resolvedValueForExportedMode`, and the complete `valuesByMode`. Colour values are
converted to hex; aliases to other variables are kept as `{ alias: id }`.

The exported mode comes from the page's `explicitVariableModes` when set, otherwise the
collection default — and either way it is recorded in the snapshot, so a light/dark pair
can be told apart later.

## After the snapshot exists

1. Compare it against `dark-theme/code.js`, `dark-theme/preview.html` and
   `dark-theme/dark-screens.json`.
2. Write down every difference. **Figma takes precedence on all of them.**
3. Only then produce light duplicates of the existing screens — identical layout, content,
   elements and states, changing nothing but semantic tokens and the contrast-driven
   details already agreed in `../concepts-3.6.6/design.json`.
