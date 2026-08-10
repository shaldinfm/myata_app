# Android 3.6.6 — phased implementation plan

Source of truth: the frozen Figma pages `3.6.6 PROPOSALS - LIGHT` / `- DARK`,
baselines in `tools/figma-export/screens-3.6.6/baselines/`, constraints in
[IMPLEMENTATION-NOTES.md](../tools/figma-export/screens-3.6.6/IMPLEMENTATION-NOTES.md).

**Nothing here is implemented yet.** 29 screens × 2 themes.

## Where the code actually stands

| | |
|---|---|
| UI | Views + XML fragments, `Theme.MaterialComponents.Light.NoActionBar`, Material 1.12.0 |
| themes | **13 style variants, every one `.Light.`** |
| colours | `values/colors.xml`, **9 colours, no `values-night/` at all** |
| existing | history, favourites (= Collection), player, donate, info, splash, TV surface |
| missing | dark theme, sleep timer, report a problem, account/profile/settings |
| current | minSdk 24, versionName 3.6.5, versionCode 202611 |

Two things dominate the estimate and are worth stating before anything else.

**Dark mode does not exist today.** The design ships two themes; the app has one,
spread across 13 hardcoded `.Light.` styles. Phase A is not a colour rename, it is
the only phase everything else depends on.

**The TV surface shares these resources.** `TvPlayerFragment`, `TvSplashFragment`
and `TvStreamSelectionFragment` read the same themes and colours. A token
migration touches TV whether or not TV is in scope — decide up front whether TV
follows 3.6.6 or gets pinned to the old palette.

---

## A · Foundation

Everything else blocks on this.

- **Muller Medium** — `app/src/main/res/font/mullermedium.otf` is present but
  untracked. Commit it here with the rest of the type work. Provenance verified:
  Fontfabric, `usWeightClass` 500, 542 glyphs, Cyrillic complete.
- **Semantic colours** — `values/colors.xml` + new `values-night/colors.xml` from
  `tools/figma-export/canonical/semantic-tokens.json` (11 approved v1 roles) plus
  the v2 roles in `spec/tokens.mjs`. Raw hex disappears from layouts.
- **Themes** — collapse the 13 `.Light.` variants onto one `Theme.Material3.DayNight`
  base with attribute overrides. This is the risky step; it changes every screen.
- **Type scale** — `values/styles.xml` text appearances from the canonical ramp
  (Medium 24/32, Bold 24/32, Medium 16/22, Regular 17/28, Regular 14/20, Medium 12/16).
- **Shared icons** — the semantic set from the design as vector drawables.

Test: both themes render every existing screen; no hardcoded colour survives
`grep`; contrast spot-checks against the two known dark failures already
documented (`Экспортировать список` 1.62:1, `Ещё` 1.15:1 — both are canonical-page
bugs the new tokens must not reproduce).

PRs: **A1** font + type scale · **A2** colours + `values-night` · **A3** theme
collapse (largest blast radius, keep it alone) · **A4** icons.

## B · Migrate existing UI

Depends on A. Player, home, favourites, about, splash re-skinned to the frozen
design — no behaviour change.

Files: `fragment_main.xml`, `fragment_player.xml`, `fragment_favorites.xml`,
`fragment_info.xml`, `fragment_donate.xml`, `fragment_splash.xml`,
`item_history_track.xml`, `item_favorite_track.xml`, `rw_playlist_item.xml`.

Test: screenshot diff per screen per theme against the Figma frames; TV surface
still launches and plays.

PRs: one per screen family. Do not batch.

## C · Broadcast History

Depends on A, B. Existing: `HistoryRepository`, `HistoryAdapter`,
`HistoryBottomSheet`, `item_history_track.xml`.

- Row height `wrap_content`; title and artist **no `maxLines`, no `ellipsize`**.
- Time, album art and find-track **vertically centred on the row** — `center_vertical`,
  not `top`. This is a deliberate design decision, not an oversight.
- One trailing action, the same control Collection uses → the streaming-service
  sheet (F). `MusicSearchHelper` already opens Spotify / Apple Music / YouTube /
  Яндекс, so the logic exists.
- Loading skeleton with the same anchors, plus empty and error states.
- Cap 30 entries.

> **On the 181px reference width.** That is the text column at Figma's **390dp**
> reference frame. It is *not* a fixed 181dp — the column is `0dp` + `weight=1`
> and narrows on a 360dp device. Do not hardcode it. Its only use is checking that
> a rendering matches the design at 390dp.

Test: a title of 60+ Cyrillic characters renders in full across 320/360/390/412dp;
row grows; nothing ellipsises; skeleton→loaded causes no horizontal shift.

PRs: **C1** row + variable height · **C2** states · **C3** find-track wiring.

## D · Sleep Timer

Depends on A. New. Entry from the player menu, presented as a bottom sheet.

Presets 15/30/45/60 plus **Своё время** (hours + minutes, `0 ч 0 мин` invalid).

Non-negotiable behaviour:

- Persist the **absolute end timestamp**, never a remaining duration.
- Remaining time is always recomputed from that timestamp.
- Survives backgrounding and process death.
- **Does not resume after a device reboot**, and never auto-starts playback.
- Firing stops playback and shows the snackbar once.

Files: new `SleepTimerController` alongside `MediaPlayerService`, SharedPreferences
or DataStore for the timestamp, `AlarmManager`/`Handler` for the fire, player menu
row with trailing remaining time.

Test: set 15 min → background 10 → reopen shows ~5 left, not 15. Kill the process
→ remaining still correct. Reboot → no timer, no playback. Cancel → row clears.

PRs: **D1** persistence + service · **D2** UI/sheet + custom picker.

## E · Report a Problem

Depends on A. `FeedbackRepository` exists and currently has **no** Telegram
reference — keep it that way.

Five categories, optional description, diagnostics card, form / sending / success
/ error, and **the error state preserves the category and typed text**.

> **The app posts to our own endpoint. The Telegram bot token lives there and
> never ships in the APK.** No personal data by default. Diagnostics are exactly:
> app version, device model, Android version, network type, last error, stream.

Test: token absent from the built APK (`unzip`/`strings` check in CI); failure
keeps user input; airplane mode gives the error state, not a crash.

PRs: **E1** endpoint contract + repository · **E2** UI states. The serverless
endpoint is separate work outside this repo.

## F · Collection

Depends on A, B. Existing: `FavoritesFragment`, `FavoritesAdapter`,
`FavoriteDao`, `item_favorite_track.xml`.

- Per-track bottom sheet: four services, divider, destructive delete.
- Overflow menu: export TXT / CSV.
- Design proposes removing the five permanently visible inline controls
  (three per-track buttons + the header export button). **Needs a GO** — it is a
  behaviour change, not a re-skin.

Test: sheet opens per track; delete removes only that track; export produces a
file with the right row count.

PRs: **F1** sheet · **F2** export · **F3** inline-control removal (separate, gated).

## G · Account / Profile / Settings

Depends on A. All new. Largest phase, and the only one that needs a backend.

**Registration is optional and there is no wall anywhere.** Radio, player,
history, sleep timer and the local collection all work signed out. An account
*adds* sync, cloud restore and profile. Copy stays benefit-oriented.

Screens: sign in, create account, profile guest, profile authenticated, avatar
picker, settings, appearance, sync, Last.fm.

**Avatar artwork is pending** — geometry is final, cells carry `avatar/m3-01…16`.
Ship the picker with placeholders or defer G-avatar; do not block the phase.

Test: full app usable with no account; sign-out keeps the local collection; theme
switch applies without restart.

PRs: **G1** settings shell + appearance · **G2** auth · **G3** profile + sync ·
**G4** Last.fm · **G5** avatar picker (after artwork).

## H · QA / regression

- Both themes across all 29 screens, 320–412dp.
- AVD playback regression, including the scenarios from the closed playback
  issues (#13 headphones, #14, #16) — the re-skin must not disturb
  `MediaPlayerService`.
- **Issue #15 is still open** (playback stops by itself, root cause unproven).
  Nothing in 3.6.6 addresses it; keep the diagnostic logging and check field logs.
- APK check: no Telegram token.
- TV surface smoke test.
- Bump to versionName 3.6.6 / versionCode 202612 at the end.

---

## Sequencing

```
A ──┬── B ── C
    ├────── D
    ├────── E
    ├────── F   (F needs B)
    └────── G
                 all ── H
```

A is the only hard blocker. D, E and G are independent of B once A lands and can
run in parallel.

## Carried over, not part of this plan

- The 181/179 Figma structure is accepted; do not reinterpret it.
- The canonical mini-player clipping (18px box, 27.5px line height, clipping
  ancestor) is a separate legacy finding on `CURRENT ANDROID UI`.
- PR #23 is superseded by the frozen design and should be closed rather than merged.
