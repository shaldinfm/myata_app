# Targeted repair — content CTA size → 22px

Repairs exactly one defect from the typography migration: content CTAs shrunk to
19 or 20px instead of grown at 22px, because the earlier fit pass mistook a
button's own wrapper for its width constraint.

This is not a re-migration. It is the narrowest tool that can fix those labels.

## Scope, enforced in code

| | |
|---|---|
| pages opened | **only** `2388:366` and `2436:531`, by id. Proposal pages are never read, so the owner's one-line correction cannot be disturbed |
| eligible nodes | classifier role `button/CTA`, family Montserrat, weight Medium |
| eligible sizes | **19 and 20 only**. 21px compact actions and correct 22px labels are skipped |
| written | `fontSize` → 22, and `layoutSizingHorizontal` on a wrapper if the larger label needs the room |
| never written | family, weight, text, `lineHeight`, `letterSpacing`, fills, strokes, effects |
| never done | shrinking anything, creating a page, cloning a page, touching a `(pre-typography)` page |

Size only ever goes up, so a second run is a no-op.

Roles are judged at the contract's reference size (22px), not at the size the
defect left behind — so each node is classified as the role it is meant to be.

## Running it

1. Plugins → Development → Import plugin from manifest… → this `manifest.json`.
2. **Dry run** — expect 11 changes and 0 problems.
3. **Apply**.
4. Run the read-only preflight again and hand back its JSON.

## Verified before release

A mock built from the real preflight report — the 11 violations plus decoys —
asserts all of:

- dry run writes nothing;
- exactly 11 nodes change, every one to 22px;
- the only properties written are `fontSize` and `layoutSizingHorizontal`;
- the 21px compact action, the already-correct 22px CTA and non-CTA body text are
  untouched;
- the proposal page and the clone page are never even read;
- a second run changes nothing.

The first version of that mock reported three failures which turned out to be
artefacts of the harness: building the tree assigns `parent`, and the proxy was
recording that as plugin activity. Construction writes are now excluded, so a
failure means the plugin did something.
