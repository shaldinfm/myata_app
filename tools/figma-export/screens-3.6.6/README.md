# 3.6.6 proposal screens

Design proposals for the four flows that have no canonical design, plus the
revised account/settings concepts from PR #23.

**Status: ready to be created in Figma. Nothing here is implemented in Android.**
Run the plugin below to place the frames on the two proposal pages, then refine
logos, icons and spacing by hand in Figma — the frames are ordinary editable
layers, and that manual pass is the intended next step.

---

## What is here

```
spec/tokens.mjs       colour roles - v1 copied from canonical/semantic-tokens.json,
                      v2 proposed with the canonical node each value came from
spec/assets.json      brand marks and canonical controls, referenced by node id
spec/primitives.mjs   canonical layout primitives, every number measured from the real pages
spec/screens.mjs      the 29 screens
build-spec.mjs        validates and emits spec.json
render-preview.mjs    spec.json -> preview.html
create-plugin/        Figma plugin: dry run, then create the frames
```

## Brand marks are referenced, never drawn

No logo is authored in this repo. An `ASSET` node carries a key; the plugin looks
the key up in `spec/assets.json`, clones **that exact node out of the open Figma
file**, resizes it and replaces every solid fill and stroke in the clone with one
tint, which is what makes it monochrome. Nothing is downloaded, and no
approximation is drawn.

| key | status | source |
|---|---|---|
| `logo/spotify` | in the file | ABOUT US › Section 3 › Spotify › Vector |
| `logo/yandex-music` | in the file | ABOUT US › Section 3 › ЯМузыка › Vector |
| `logo/youtube-music` | **needs confirmation** | ABOUT US › Section 3 › YouTube — this is the YouTube mark, not the YouTube Music mark |
| `logo/apple-music` | **needs an asset** | nothing in the file |
| `logo/lastfm` | **needs an asset** | nothing in the file |
| `avatar/m3-01 … -16` | **needs assets** | intended to be the Material 3 Design Kit avatars |
| `control/find-track` | in the file | COLLECTION › Track Item 1 › Container › Button |

The `Social Button` components on the UI KIT page look like logo assets but are
not — each contains a plain rectangle with a solid fill, so they are placeholders.
The real marks are on ABOUT US.

Where a key has no node, the preview draws a **red dashed slot** and the plugin
creates an empty frame named `Asset slot / <key> (PENDING)` and lists it as
blocked. The gap stays visible instead of being papered over. To supply one, see
`howToSupply` in `spec/assets.json`.

The dry run also sweeps this file for local components whose name looks like an
avatar and prints their ids, so wiring the Material 3 avatars is a paste rather
than a hunt.

`spec.json` is the single source both consumers read. The preview and the Figma
frames come from one node tree with only the token table swapped, so light and
dark cannot drift apart, and neither can the preview and the file.

## Running it

```bash
node tools/figma-export/screens-3.6.6/build-spec.mjs && node tools/figma-export/screens-3.6.6/render-preview.mjs
```

`build-spec.mjs` fails the build if any node uses a raw hex instead of a token,
names an asset key that is not registered, is missing a dimension, falls outside
its parent, or over-subscribes a horizontal auto-layout. Containment is not
checked inside an auto-layout frame, where Figma computes the positions.

Open `preview.html` for review. Muller is not embedded, so unless it is installed
locally the text falls back to a system face — colour, layout and hierarchy are
accurate, letterforms and text widths are not.

## Writing to Figma

`create-plugin/` is a manifest plugin. Import it, Dry Run, then Create.

- Dry Run performs no writes. It reports the frame count, checks that every
  referenced asset node resolves, lists the ones that need supplying, and refuses
  a canonical page.
- Create runs only what Dry Run listed, and re-checks the live page first.
- Frames go to two pages, `3.6.6 PROPOSALS - LIGHT` and
  `3.6.6 PROPOSALS - DARK`, created if absent. The plugin **refuses to write to
  a canonical page**, so `figma-canonical-*-normalized.json` stays comparable
  across exports.
- Re-running skips frames that already exist by name, so a partial run resumes
  cleanly.
- Everything created is an ordinary editable Figma layer — plain frames, text and
  auto-layout, no flattening, no components — so logos, icons, spacing and
  individual details can be refined by hand afterwards.
- The page is laid out from the sizes the frames actually came out at, because an
  auto-layout frame that hugs a wrapped title ends up taller than its nominal
  height.

### Steps

1. Open the Figma file that holds both canonical pages.
2. **Menu → Plugins → Development → Import plugin from manifest…** and pick
   `tools/figma-export/screens-3.6.6/create-plugin/manifest.json`.
3. Run **Radio Myata · 3.6.6 proposal screens**.
4. Press **1 · Dry Run**. Nothing is written. Check that Muller Regular, Medium
   and Bold all report `AVAILABLE`, that the frame count reads 58, and that the
   asset list shows only the expected `NEEDS ASSET` rows.
5. Press **2 · Create frames**. It stays disabled until a dry run has succeeded,
   and stays disabled if any warning was raised.
6. Stop there. Do not export or normalize the proposal pages yet.

Re-exporting is a later step, and when it happens the canonical pages must show
**no diff** — that is the check that the plugin stayed off them.

---

## The 29 screens

### Таймер сна (8)

Entry point is the existing `Menu row / Таймер сна` on the player menu, so the
flow adds no new navigation. Presentation is the canonical bottom sheet.

| id | what |
|---|---|
| `sleep-timer-select` | 15 / 30 / 45 / 60 presets plus **Своё время**, nothing preselected |
| `sleep-timer-custom` | hours + minutes picker, with the resulting wall-clock time previewed |
| `sleep-timer-custom-invalid` | 0 ч 0 мин — the only invalid input; Установить disabled |
| `sleep-timer-active` | preset running: remaining time, plus a separated destructive "Отключить таймер" |
| `sleep-timer-active-custom` | the same sheet after a custom duration is confirmed |
| `sleep-timer-menu-active` | the player menu with `Таймер сна · 24 мин` |
| `sleep-timer-cancelled` | snackbar, composited over the player |
| `sleep-timer-completed` | snackbar, playback stopped |

**Своё время** allows hours and minutes, so `1 ч 30 мин` is expressible. The
picker resolves the duration to a wall-clock time before anything is committed,
and `Установить` is disabled at `0 ч 0 мин` rather than accepting it and failing
later. Once confirmed it is not a special case — it behaves exactly like a preset:
absolute end time persisted, remaining time shown, cancellable, survives
backgrounding and process recreation, and does not resume after a reboot.

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

### История эфира (5)

`history-content`, `history-loading`, `history-empty`, `history-error`,
`find-track-sheet`.

**Rows are variable height and nothing is ever truncated.** The row is a
horizontal auto-layout frame that hugs its content, and the title and artist are
set to wrap rather than clip, so a track called
*Краснознамённая дивизия имени моей бабушки* is shown in full. Two of the eight
rows on `history-content` wrap on purpose so the behaviour is reviewable.

A uniform 8px gap with 13px padding reproduces every canonical anchor exactly —
time at 13, art at 60, text at 116, action at 305 — which means a single-line row
still comes out at 13 + 48 + 13 = **74**, the canonical height. Cross-axis
alignment is `MIN`, so the time stays on the title's first line instead of
drifting to the middle of a tall row.

The row is the canonical `History Item` from the player's Broadcast History
section, promoted to full content width because the standalone screen has no
outer card to sit in. Capped at 30 entries; older ones are dropped rather than
paged.

**One trailing action, not two.** It is the canonical `control/find-track` — the
same 40×40 primary-stroked control the Collection track item uses — cloned rather
than re-authored, so History does not introduce a second action pattern. Tapping
it opens `find-track-sheet`: the same four services as Collection, without the
destructive row, since there is nothing saved to delete.

**Album art.** The canonical row leaves a 64px gap between the time column
(ends at +39) and the text column (starts at +103) with no child in it. That gap
is the album-art slot: a 48×48 thumbnail centred in it lands the text back on the
canonical x. Thumbnail geometry is the mini player's, 48×48 at radius 8.

**Skeleton.** Same auto-layout frame, same four slots, placeholders instead of
content: a small bar in the real time column, a 48×48 square where the art goes,
two bars in the real text column, and a 40×40 trailing circle for the action.
Every horizontal anchor is identical to the loaded row, verified in the rendered
preview — `13 / 60 / 116 / 305` in both — so nothing appears or shifts sideways
when the data lands.

### Коллекция (2)

| id | what |
|---|---|
| `collection-track-sheet` | per-track sheet: four services, divider, destructive delete |
| `collection-overflow-menu` | export to TXT / CSV, from the app-bar overflow |

The sheet extends the canonical `Bottom Sheet / Найти трек` — same handle, title
position, row pitch and icon metrics — and adds the divider and the destructive
row after it. Structure, spacing and hierarchy are unchanged from the canonical
sheet; only the leading icons differ, being real marks instead of the generic
disc. The canonical frame itself is **not** modified.

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

### Аккаунт / Настройки (9)

`auth-sign-in`, `auth-create-account`, `profile-guest`, `profile-authenticated`,
`profile-avatar`, `settings`, `settings-appearance`, `settings-sync`,
`settings-lastfm`.

**Registration is optional and there is no wall anywhere.** Radio, the player,
Broadcast History, Sleep Timer and a local Collection all work without an
account; `profile-guest` says exactly that on the card. An account *adds*
collection sync between devices, cloud restore, and future profile
functionality — the list is headed "Аккаунт добавляет", not "unlock". No copy
implies that listening requires signing in, and `auth-sign-in` keeps
"Продолжить без аккаунта" as a first-class exit.

**`profile-avatar`** is sixteen predefined avatars in a 4×4 grid (76px cells,
18px gutters, 4·76 + 3·18 = 358), with the current avatar previewed above,
a selected state and a Save action. No photo upload in 3.6.6. Every cell is an
asset slot, so the Material 3 avatars are referenced if their ids are supplied
and left as empty named slots otherwise — nothing is a drawn approximation.

Every row runs on one fixed track, so nothing can grow into anything else:

```
16   icon 24   56   label … status   306 │ 8 gap │ 318   chevron 24   342
```

The chevron slot is reserved whether or not a row has a chevron, the status is
right-aligned and ends at 306, and the label ends at the status edge when there
is a status and at the trailing edge otherwise. Row height is unchanged at 64
(72 where a sub-line replaces a status). Icons are semantic — person, display,
equalizer, clock, the Last.fm mark, message, info — not a generic circle.

Card padding is 16 on every edge, so the Last.fm connect/disconnect button now
clears the card boundary by 16px rather than 4. The guest card is 32/32 with 24
between the avatar and the headline.

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
