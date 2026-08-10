# Typography and colour outliers in the canonical pages

Status: **analysis and recommendations. No Figma mutation is proposed here yet** —
each item below needs a GO before it goes into a repair plan.

Everything is measured from `canonical/figma-canonical-{dark,light}-final.json`,
the post-repair exports. Contrast ratios are WCAG 2.1 relative luminance against
the screen background of the theme in question (`#0f253e` dark, `#f8f9fa` light).

---

## 1. "История эфира" uses body colour in Light

| | font | dark | light |
|---|---|---|---|
| `История эфира` (layer `Heading 2`) | Muller Bold 24 / lh 32 | `#f5f7fa` | **`#191c1d`** |
| `Наши потоки` | Muller Bold 28 | `#f5f7fa` | `#003056` |
| `Мятные плейлисты` | Muller Bold 28 | `#f5f7fa` | `#003056` |
| `Ещё Радио Мята` | Muller Medium 24 | `#f5f7fa` | `#003056` |
| `Моя коллекция` (app bar) | Muller Medium 24 | `#f5f7fa` | `#003056` |

Every other section and screen title in Light is `#003056` = `textHeading`.
`История эфира` alone is `#191c1d` = `textPrimary`.

In Dark the two roles collapse to the same `#f5f7fa`, so the deviation is
invisible there and only shows in Light — which is exactly the signature of a
value that was set once on the Dark page and re-entered by hand on the Light one.

**Recommendation — assign `textHeading`.** It is a `Heading 2` layer, in the same
structural slot as the other section titles, at a heading size and weight.

- Dark: no change (`#f5f7fa` either way).
- Light: `#191c1d` → `#003056`. This is a **visible pixel change** on the Player
  screen, so it needs an explicit GO rather than being folded into a cleanup.
- Contrast is fine either way: `#191c1d` 16.26:1, `#003056` 12.79:1.

---

## 2. "Читать подробнее" is a button label, not a heading

Its raw values are `#f5f7fa` dark / `#003056` light, which happen to be exactly
`textHeading` in both themes. **That coincidence is a trap and the role is not
`textHeading`.**

The Dark UI KIT page defines the button family explicitly:

| component | container fill | stroke | label |
|---|---|---|---|
| `Component / Dark / Button / Primary` | `#5fd9b4` (`primary`) | — | `#0f253e` (`onPrimary`) |
| `Component / Dark / Button / Secondary` | `#1c3f5f` | `#466d8f` | `#5fd9b4` (`primary`) |
| `Component / Dark / Button / Outline` | `#142d47` | `#466d8f` | `#f5f7fa` (`textPrimary`) |

`Читать подробнее` on ABOUT US sits in a container with fill `#142d47` and stroke
`#466d8f` — that is the **Outline** component, byte for byte. Its label `#f5f7fa`
is `textPrimary`, precisely what the kit specifies. It matches `textHeading` only
because `textHeading` and `textPrimary` are the same hex in Dark.

**Recommendation — assign the interactive role `buttonOutlineLabel`**
(dark `#f5f7fa`, light `#003056`), defined in `spec/tokens.mjs` alongside
`buttonOutlineContainer` and `buttonOutlineBorder`. No pixel changes.

### 2a. Light has no Secondary/Outline distinction (follow-up)

| label | screen | container dark | container light |
|---|---|---|---|
| `Показать ещё` | PLAYER | `#1c3f5f` + `#466d8f` → **Secondary** | `#f8f9fa` + `#e1e3e4` |
| `Читать подробнее` | ABOUT US | `#142d47` + `#466d8f` → **Outline** | `#f8f9fa` + `#e1e3e4` |

Dark separates the two variants; Light draws them identically and gives both the
label `#003056`. Not a defect on its own — Light legitimately expresses both as a
bordered button on the page background — but Android will need to know whether
these are one component or two. **Recommend collapsing to one "outline" variant
in both themes**, which costs Dark the `#1c3f5f` fill on `Показать ещё`. Owner call.

---

## 3. Two real contrast failures in Dark

Both are labels that were left at their Light value on the Dark page. Neither is
a typography question — they are unreadable.

| node | screen | colour (both themes) | vs dark bg `#0f253e` | WCAG |
|---|---|---|---|---|
| `Экспортировать список` label **and** its 1px border | COLLECTION, COLLECTION pusto | `#1c4771` | **1.62:1** | fail (needs 4.5:1) |
| `Ещё` chip label | PLAYER › Broadcast History header | `#003056` | **1.15:1** | fail |

`#1c4771` is `primary`-**light** and `#003056` is `textHeading`-**light**; both are
being drawn on a `#0f253e` background. `Ещё` at 1.15:1 is effectively invisible.

**Recommendation — treat as bugs, fix in Dark only:**

| node | dark now | dark proposed | new ratio |
|---|---|---|---|
| `Экспортировать список` label + border | `#1c4771` | `#5fd9b4` (`primary`) | 8.91:1 |
| `Ещё` label | `#003056` | `#5fd9b4` (`primary`) | 8.91:1 |

Light is already correct in both cases and must not change. This is the highest
priority item on the page: items 1 and 2 are naming, this one is legibility.

---

## 4. Sheet and menu borders in Light are navy, not neutral

`Bottom Sheet / Найти трек` and `Menu / Плеер` both use stroke `#1c3f5f` in Light
where every card uses `outline` `#e1e3e4`. Two nodes, consistent with each other,
and the Dark counterpart `#466d8f` is the same design idea one step lighter.

**Recommendation — this is a role, not a slip.** Recorded as `menuOutline`
(dark `#466d8f`, light `#1c3f5f`) and used by the new sheets and menus. No change
to the existing nodes.

---

## 5. The 15 unattached text styles

The repair created 15 text styles and deliberately left them **unattached** — no
text node references them. That stays as it is.

Attaching them means writing `textStyleId` to roughly 200 text nodes across both
pages. Every one is a mutation to a canonical node, for zero visual change, and
Figma resolves a style assignment against the *current* style definition — so a
later edit to one style silently restyles every node bound to it. The exported
baselines would also churn on a property that carries no design information.

**Recommendation — leave all 15 unattached.** They document the type ramp, which
is what they are for. If they are ever attached it should be a separate,
individually reviewed pass, not a mass operation.

---

## Summary of proposed changes

| # | Change | Theme affected | Visible? | Needs GO |
|---|---|---|---|---|
| 3 | `Экспортировать список` label + border → `primary` | dark only | yes | **yes — recommended first** |
| 3 | `Ещё` label → `primary` | dark only | yes | **yes — recommended first** |
| 1 | `История эфира` → `textHeading` | light only | yes | yes |
| 2 | `Читать подробнее` → `buttonOutlineLabel` | — | no | no, naming only |
| 2a | Collapse Secondary into Outline | dark | yes | yes, if wanted |
| 4 | Record `menuOutline` | — | no | no, naming only |
| 5 | Leave 15 text styles unattached | — | no | no, no action |
