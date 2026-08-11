# Typography migration — 3.6.6

Applies the **approved** Montserrat + Onest role typography, and the approved
heading and button geometry corrections, to the live 3.6.6 design source.

This is the first tool here that writes to the real design. Everything before it
worked on copies.

## Read this before running

The frozen canonical design lives on the pages **`CURRENT ANDROID UI - LIGHT`**
and **`CURRENT ANDROID UI — DARK`** — the pages an earlier instruction placed
off-limits while the proposals were being drawn. Applying approved typography to
the active 3.6.6 source necessarily touches them. That is intentional here and
was authorised, but it is worth knowing before pressing Apply.

Target pages, all four:

| page | id | frames |
|---|---|---|
| `CURRENT ANDROID UI - LIGHT` | `2388:366` | 10 |
| `CURRENT ANDROID UI — DARK` | `2436:531` | 10 |
| `3.6.6 PROPOSALS - LIGHT` | `2517:1936` | 29 |
| `3.6.6 PROPOSALS - DARK` | `2517:2903` | 29 |

Pages are resolved by id first and by name only as a fallback, so a renamed page
still matches and an unrelated page never does. Anything not in that list —
including the `FONT TRIAL …` pages — is never opened for writing.

## Safety

- **Dry run is the default.** It reports every change it would make and writes
  nothing.
- **Apply duplicates each page first** as `<page> (pre-typography)`, unless you
  untick the box. Reverting is then: delete the migrated page, rename the backup.
- Colours, icons, content, structure and unrelated layout are never written. The
  parity validator below proves that rather than asserting it.

## Running it

1. Plugins → Development → Import plugin from manifest… → this `manifest.json`.
2. **Dry run**, and read the report.
3. **Apply**, and confirm the prompt.
4. Re-export all four pages with the canonical snapshot plugin.
5. Hand the JSON back for parity validation and baseline regeneration.

## What it changes

Only three things per text node, and only where the role demands it:

| change | where |
|---|---|
| font family | every text node, per the frozen classifier |
| font weight | button/CTA and the action link → Medium; the two ABOUT US card headings → Medium |
| font size | compact actions only, 22 → 21px |

Plus the approved geometry corrections: a heading that would wrap keeps its size
and grows its box into space that was already empty; a button that outgrows its
frozen width grows if its container has room. Shrinking is the last resort and is
not expected to trigger anywhere in this design.

`lineHeight` and `letterSpacing` are preserved. The one `AUTO` line height in the
frozen set — `PLAYER` → "PAUSE" — is pinned to Muller's resolved value before the
swap so the new font cannot redefine it.

## The classifier cannot drift

`code.js` is **generated**. `build.mjs` splices the classifier verbatim out of
`../font-trial/code.js`, so the migration can only ever apply the rules that were
approved visually.

```bash
node tools/figma-export/typography-migration/build.mjs          # regenerate
node tools/figma-export/typography-migration/build.mjs --check  # fail if stale
```

## Parity validation

After the migration and a fresh export:

```bash
node tools/figma-export/typography-migration/verify-parity.mjs before.json after.json
```

It matches every node by id and classifies every property difference as expected
or not. Typography and geometry may move; a missing node, an added node, an edited
character, a changed fill, stroke or effect, or any other property change is a
failure, reported with the node's path.

It is negative-controlled: against a synthetic migration that also flipped one
fill and edited one label, it reports `PARITY FAIL` and names both nodes.
