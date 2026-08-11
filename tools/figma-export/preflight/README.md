# Typography preflight (read-only)

Audits the four **original unsuffixed** pages before deciding whether a targeted
repair is safe or the migration has to be redone.

**It writes nothing.** No pages are created, cloned or modified; the only Figma
calls are `loadAllPagesAsync` and property reads. A mock harness that throws on
any property write confirms zero mutations across all three scenarios.

The `(pre-typography)` clones are deliberately excluded from the audit — cloning
detached the component masters, so they are no longer a trustworthy reference.
They are listed at the end only so you can see what is still lying around.

## Running it

1. Plugins → Development → Import plugin from manifest… → this `manifest.json`.
2. **Run preflight**.
3. **Download report JSON** and hand it back.

## What it answers

| # | question | how |
|---|---|---|
| 1 | do the four pages still exist under their original ids | resolved by id first; a name fallback matches only the exact unsuffixed name, never a clone |
| 2 | what typography is on them now | per-page counts of Muller / Montserrat / Onest, and a verdict: migrated, pre-migration, or MIXED |
| 3 | is the structure intact | COMPONENT vs INSTANCE per page, plus a UI KIT breakdown flagged against the expected 40 masters |
| 4 | which nodes are off contract | every text node re-classified with the frozen classifier; family, weight and size checked, and any 19/20px content CTA reported |
| 5 | does the manual correction survive | `sleep-timer-menu-active` → "Сообщить о проблеме", with its measured line count |

## How it reads the result

| verdict | meaning |
|---|---|
| **CASE C** | a page is missing, an id changed, or UI KIT masters are below 40 — stop, report, write nothing |
| **CASE B** | Muller still present — report before re-running the migration |
| **CASE A** | migrated and intact, with contract violations — a targeted repair is appropriate |
| clean | migrated, intact, no violations — nothing to repair |

A size-conditional rule has to be probed at the pre-migration size, so a node
already at 21px is classified as if it were 22px. Otherwise a correctly migrated
compact action stops matching its own rule and reports as a violation.

## Kept in sync

`code.js` is generated. `build.mjs` splices the classifier verbatim from
`../font-trial/code.js`, so the contract this audits against is the one that was
approved.

```bash
node tools/figma-export/preflight/build.mjs          # regenerate
node tools/figma-export/preflight/build.mjs --check  # fail if stale
```
