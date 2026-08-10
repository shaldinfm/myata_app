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
and `TvStreamSelectionFragment` read the same themes and colours, so a token
migration reaches them whether or not TV is in scope. **Decided: it is not.**
See [TV scope](#tv-scope--strict-regression-preservation).

---

## TV scope — strict regression preservation

**3.6.6 is mobile only. Android TV is not being redesigned.**

TV must come out of this release looking and behaving exactly as it does today:
appearance, layout, focus and D-pad navigation, playback, and stream selection.
**Any unintended TV visual or behavioural change is a regression**, not an
acceptable side effect of the token migration.

The risk is concrete: TV and mobile currently share one theme tree, one
`colors.xml` and one font set, so the Phase A migration would silently re-skin TV.
That has to be prevented before shared resources are touched, not noticed after.

Order of work inside Phase A:

1. **Audit first.** Enumerate every theme, style, colour, dimension and font that
   the TV fragments and their layouts actually consume — `fragment_tv_player.xml`,
   `fragment_tv_splash.xml`, `fragment_tv_stream_selection.xml`,
   `activity_tv_main.xml`, and anything they include.
2. **Baseline before changing anything.** Capture TV screenshots and a focus-path
   walkthrough on a TV emulator. Without a before, "unchanged" is unprovable.
3. **Isolate.** Give TV its own aliases, styles and colour resources pinned to the
   current values, so the mobile tokens can move underneath without reaching it.
   TV keeps the light palette it has today.
4. **Migrate mobile.** Only then collapse the 13 `.Light.` styles.
5. **Re-verify.** TV screenshots and focus paths must match the baseline.

TV explicitly does **not** get: dark theme, the new type scale, the new icon set,
or any 3.6.6 screen. Leave `android.software.leanback` and the
`LEANBACK_LAUNCHER` entry as they are.

## A · Foundation

Everything else blocks on this.

- **Muller Medium** — landed in A1. See [FONTS.md](FONTS.md) for the full
  resource map, including the `muller_regular` / `mullerregular` duplicate that
  is deliberately left alone because TV consumes one of them.
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
bugs the new tokens must not reproduce); **TV screenshots and focus paths identical
to the pre-migration baseline.**

PRs: **A0** TV isolation — *merged, `d18ac6d`* · **A1** Muller Medium asset ·
**A2** colours + `values-night` · **A3** theme collapse · **A4** icons.

### A2 boundary — colours only

Introduces the eleven approved v1 semantic roles from
`tools/figma-export/canonical/semantic-tokens.json` plus the v2 roles in
`screens-3.6.6/spec/tokens.mjs`, as `values/colors.xml` additions and a new
`values-night/colors.xml`.

**No screen is redesigned and no theme is restructured.** New roles are added
alongside the existing nine colours rather than replacing them, so nothing that
currently reads `@color/main_fragment` changes meaning. Existing colours are
retired later, once the screens that use them have migrated.

TV is already insulated: it reads `colors_tv.xml`, which holds literals.

### A3 boundary — mobile themes only

Three constraints, and the first is the one most likely to be got wrong.

**`AppTheme0`…`AppTheme9` are LIVE.** `MainActivity` does `(0..9).random()` and
`setTheme(...)`; the ten differ only in `android:windowBackground` →
`@drawable/screen0…screen9`, so the app shows one of ten random window
backgrounds per launch. That is product behaviour. **Preserve it** unless the
owner explicitly decides to retire it — deleting it as "duplicate styles" would
be a silent feature removal.

**`TvTheme` stays isolated and visually unchanged.** It spells out its own parent
precisely so that collapsing `AppTheme` onto a DayNight base does not drag TV
along.

**`<application>` still declares `@style/AppTheme`**, so A3 reaches the
application-level theme even though `TvMainActivity` overrides it. That is enough
of a path to TV to require proof, not reasoning: **re-run the committed TV
regression harness after A3** —

```bash
node tools/qa/tv/capture-tv-baseline.mjs after
node tools/qa/tv/compare-tv-baseline.mjs
```

against `tools/qa/tv/before/`, on the same `Myata_TV_API36` AVD. **Any TV visual,
focus or behavioural difference is a regression.**

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

**Avatar artwork is pending.** Geometry is final and the cells carry
`avatar/m3-01…16`. Assets land in `design-assets/avatars/` under the naming
contract in its README; **nothing is generated and no placeholder avatar goes
into Android resources**. Ship the picker with empty cells or defer G5 — do not
block the phase, and do not invent artwork to unblock it.

Test: full app usable with no account; sign-out keeps the local collection; theme
switch applies without restart.

PRs: **G1** settings shell + appearance · **G2** auth · **G3** profile + sync ·
**G4** Last.fm · **G5** avatar picker (after artwork).

## I · Android Auto MVP

New in 3.6.6. **Android Auto only** — Android Automotive OS is out of scope.

Deliberately small: three stations, transport controls, correct metadata. The
Auto host draws the driver-safe UI; **the mobile Figma screens are not recreated
there**, and no arbitrary custom screen is introduced.

### What has to change in the service

`MediaPlayerService` today extends **`MediaSessionService`**. Auto needs a browse
tree, which means **`MediaLibraryService` / `MediaLibrarySession`** with
`onGetLibraryRoot` and `onGetChildren`. That is the one structural change, and it
must keep the existing player as the **single source of playback** — no second
ExoPlayer instance, no second session.

The three stations already exist as keys in `data/Streams.kt`:
`MYATA = "myata"`, `GOLD = "gold"`, `XTRA = "myata_hits"` (alias `"xtra"`). The
browse tree is one flat level over those three; there is nothing to model.

The manifest has no Auto declaration yet — `com.google.android.gms.car.application`
meta-data plus an `automotive_app_desc.xml` declaring `media` are new.

### In scope

Browse and select the three stations · play/pause · switch station · current
station, track title, artist · artwork when available · correct MediaSession
metadata · reconnect and resume consistent with the phone player · host-provided
steering-wheel and media controls · voice/media integration to the extent the
media architecture gives it for free.

### Explicitly out of scope

Account and profile · settings · report a problem · sleep timer configuration UI ·
full broadcast history UI · collection management · any custom mobile screen.

### Branding

Only where the surface supports it: station artwork, media artwork, app icon and
branding, semantic naming and content. Nothing else from 3.6.6 crosses over.

> **Car App Library is not a dependency for 3.6.6.** Its templated media UI is not
> to be adopted as the production path unless current Google Play production
> eligibility is explicitly verified first. The MVP uses the standard Media3
> browse/session architecture, which is production-safe today.

Files: `service/MediaPlayerService.kt` (base class + library callbacks), a browse
tree source over `data/Streams.kt`, `data/MetadataRepository.kt` for now-playing,
`data/ArtworkRepository.kt` for artwork, `AndroidManifest.xml`,
`res/xml/automotive_app_desc.xml`.

### QA

Connect and disconnect · switch MYATA/GOLD/XTRA · metadata updates on track change ·
artwork updates · audio focus · **becoming-noisy and headphone transitions on the
phone side** · process recreation · **no duplicate playback session** · phone UI
and Auto stay synchronised in both directions.

Test with Desktop Head Unit before any device work.

Dependencies: needs **A** for branding assets only, and is otherwise independent
of B–G. It touches the same service as **D**, so sequence D and I rather than
running them in parallel — both modify `MediaPlayerService`.

PRs: **I1** `MediaLibraryService` migration + browse tree, no UI change ·
**I2** manifest declaration + metadata/artwork polish · **I3** QA fixes.
Keep I1 separate: it is a playback-service change and deserves its own review and
its own regression pass.

## H · QA / regression

- Both themes across all 29 screens, 320–412dp.
- AVD playback regression, including the scenarios from the closed playback
  issues (#13 headphones, #14, #16) — the re-skin must not disturb
  `MediaPlayerService`.
- **Issue #15 stays open** (playback stops by itself, root cause unproven).
  Nothing in 3.6.6 addresses it. **No speculative fix without field evidence** —
  keep the diagnostic logging and wait for logs from an affected device.
- APK check: no Telegram token.
- **TV regression: screenshots and focus paths must match the Phase A0 baseline.**
- Android Auto QA per phase I, on Desktop Head Unit.
- Bump to versionName 3.6.6 / versionCode 202612 at the end.

---

## Sequencing

```
A0 (TV isolate) ── A ──┬── B ── C
                       ├────── D ──── I      D and I both touch the service
                       ├────── E
                       ├────── F   (F needs B)
                       └────── G   (G5 waits on avatar artwork)
                                        all ── H
```

A is the only hard blocker, and **A0 must land before A2/A3** so the TV baseline
exists before shared resources move. E and G are independent of B once A lands.

**I depends on A for branding assets only** — it needs no mobile UI work, so it
can start early. Sequence it after D rather than beside it: both modify
`MediaPlayerService`, and the `MediaLibraryService` migration is not something to
merge alongside a concurrent change to the same file.

## Carried over, not part of this plan

- The 181/179 Figma structure is accepted; do not reinterpret it.
- The canonical mini-player clipping (18px box, 27.5px line height, clipping
  ancestor) is a separate legacy finding on `CURRENT ANDROID UI`.
- PR #23 was closed as superseded by the frozen design.
- **Android TV is not redesigned** — see [TV scope](#tv-scope--strict-regression-preservation).
- **Android Automotive OS is out of scope.** Phase I is Android Auto only.
- **Issue #15 stays open** and gets no speculative fix.
