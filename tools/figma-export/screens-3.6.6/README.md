# 3.6.6 proposal screens

Design proposals for the four flows that have no canonical design, plus the
revised account/settings concepts from PR #23.

**Status: awaiting visual approval. Nothing here is implemented in Android, and
nothing here has been written to Figma yet.**

---

## What is here

```
spec/tokens.mjs       colour roles - v1 copied from canonical/semantic-tokens.json,
                      v2 proposed with the canonical node each value came from
spec/primitives.mjs   canonical layout primitives, every number measured from the real pages
spec/screens.mjs      the 24 screens
build-spec.mjs        validates and emits spec.json
render-preview.mjs    spec.json -> preview.html
create-plugin/        Figma plugin: dry run, then create the frames
```

`spec.json` is the single source both consumers read. The preview and the Figma
frames come from one node tree with only the token table swapped, so light and
dark cannot drift apart, and neither can the preview and the file.

## Running it

```bash
node tools/figma-export/screens-3.6.6/build-spec.mjs && node tools/figma-export/screens-3.6.6/render-preview.mjs
```

`build-spec.mjs` fails the build if any node uses a raw hex instead of a token,
is missing a dimension, or falls outside its parent. It caught ten such
problems on the first run.

Open `preview.html` for review. Muller is not embedded, so unless it is installed
locally the text falls back to a system face — colour, layout and hierarchy are
accurate, letterforms and text widths are not.

## Writing to Figma

`create-plugin/` is a manifest plugin. Import it, Dry Run, then Create.

- Dry Run performs no writes.
- Create runs only what Dry Run listed, and re-checks the live page first.
- Frames go to two **new** pages, `3.6.6 PROPOSALS — DARK` and
  `3.6.6 PROPOSALS - LIGHT`. The plugin **refuses to write to a canonical page**,
  so `figma-canonical-*-normalized.json` stays comparable across exports.
- Re-running skips frames that already exist by name, so a partial run resumes
  cleanly.

After the frames exist, re-export both canonical pages, run
`canonical/normalize-snapshot.mjs`, and commit the updated baselines. The
canonical pages themselves should show **no diff** — that is the check that the
plugin stayed off them.

---

## The 24 screens

### Таймер сна (5)

Entry point is the existing `Menu row / Таймер сна` on the player menu, so the
flow adds no new navigation. Presentation is the canonical bottom sheet.

| id | what |
|---|---|
| `sleep-timer-select` | 15 / 30 / 45 / 60, nothing preselected |
| `sleep-timer-active` | selected option with remaining time, plus a separated destructive "Отключить таймер" |
| `sleep-timer-menu-active` | the player menu with `Таймер сна · 24 мин` |
| `sleep-timer-cancelled` | snackbar, composited over the player |
| `sleep-timer-completed` | snackbar, playback stopped |

**Android requirements this design assumes** (not implemented, stated so the
design can be checked against them):

- Persist the **absolute end time**, not a remaining duration. A countdown that
  restarts is the failure mode this avoids.
- The timer must survive backgrounding and process death; on process recreation
  the remaining time is recomputed from the stored end time.
- Reopening the sheet shows the live remaining time, never a fresh countdown.
- After a device reboot the timer does **not** resume and playback does **not**
  auto-start. A fired timer stops playback and shows the snackbar once.

### Сообщить о проблеме (5)

Five categories exactly as specified: `Музыка не запускается`,
`Музыка остановилась сама`, `Проблема с наушниками`, `Проблема с интерфейсом`,
`Другое`. Optional free-text description, a diagnostics preview, and Send.

States: `report-empty`, `report-filled`, `report-sending`, `report-error`,
`report-success`. **The error state preserves the chosen category and the typed
text** — nothing is cleared on failure.

The diagnostics card lists exactly what leaves the device: app version, device
model, Android version, network type, last error, current stream. No identifiers,
no account data, no location. The card says so on the frame.

> **Architecture constraint.** The app posts to our own endpoint. The Telegram
> bot token lives on that endpoint. **No Telegram credentials ever ship in the
> APK.** No personal data is collected by default.

### История эфира (4)

`history-content`, `history-loading`, `history-empty`, `history-error`.

The row is the canonical `History Item` from the player's Broadcast History
section, promoted to full content width because the standalone screen has no
outer card to sit in. Internal offsets, heights and type are unchanged. Capped
at 30 entries; older ones are dropped rather than paged. Loading uses skeletons
with the exact row geometry so nothing shifts when data lands.

### Коллекция (2)

| id | what |
|---|---|
| `collection-track-sheet` | per-track sheet: four services, divider, destructive delete |
| `collection-overflow-menu` | export to TXT / CSV, from the app-bar overflow |

The sheet extends the canonical `Bottom Sheet / Найти трек` — same handle, title
position, row pitch and icon metrics — and adds the divider and the destructive
row after it.

#### Proposed change to the existing COLLECTION screen

Not drawn here on purpose. Redrawing a canonical frame is how a copy becomes a
fork, so this is written as a repair to be reviewed on its own:

- Remove the permanent per-track action button (`Track Item N > Container > Button`,
  40×40, three instances) in favour of the per-track sheet.
- Remove the permanent `Экспортировать список` button from the header
  (`Main > Header Section > Button`, 321×52 plus 36px of margin) in favour of the
  overflow menu.

That is five permanently visible controls removed from a screen whose job is to
show a list, and 88px of vertical space returned above the fold. **Needs a GO,
and would ship as a repair-plan mutation, not as a new frame.**

### Аккаунт / Настройки (8)

`auth-sign-in`, `auth-create-account`, `profile-guest`, `profile-authenticated`,
`settings`, `settings-appearance`, `settings-sync`, `settings-lastfm`.

Revised from the PR #23 concepts. See [PR23-CONCEPT-REVIEW.md](PR23-CONCEPT-REVIEW.md)
for what was kept, what was replaced and why — in short, the flows survive and
the palette does not, because PR #23 predates the export and reconstructed its
colours.

---

## Related

- [TYPOGRAPHY-AND-COLOUR-OUTLIERS.md](TYPOGRAPHY-AND-COLOUR-OUTLIERS.md) — the
  `История эфира` and `Читать подробнее` questions, two genuine contrast failures
  in Dark, and why the 15 text styles stay unattached.
- [PR23-CONCEPT-REVIEW.md](PR23-CONCEPT-REVIEW.md)
- `../canonical/semantic-tokens.json` — the approved v1 roles.
- `../repair/NEW-SCREEN-PROPOSALS.md` — the product reasoning these screens implement.
