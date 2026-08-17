# PROJECT_STATUS.md — MyataRadio

Last updated: 2026-08-08.

## Current canonical state

- **GitHub `main` (https://github.com/shaldinfm/myata_app) is the single source of truth** — see [SOURCE_OF_TRUTH.md](SOURCE_OF_TRUTH.md).
- `main` builds **versionName 3.6.5 / versionCode 202611**.
- **API 36**: `compileSdk 36`, `targetSdk 36`, `minSdk 24`.
- **Networking is secure**: full TLS validation with per-domain bundled trust anchors (`SecureNetModule`, `network_security_config.xml`). The old trust-all `UnsafeNetModule` is gone. Cleartext remains permitted for the audio stream host only, as a TV/legacy fallback that is now TV-only, TLS-triggered and episode-scoped.
- **Play App Signing is resolved and documented** — see [PLAY_APP_SIGNING_CHECK.md](PLAY_APP_SIGNING_CHECK.md). Release signing comes from a local untracked `keystore.properties` ([RELEASE_SIGNING.md](RELEASE_SIGNING.md)), and the build refuses to produce an unsigned release artifact.
- Local folders D / A / B are **read-only archives**, not development sources.
- All new work: branch off `main` → draft PR → owner merges. Agents follow [../CLAUDE.md](../CLAUDE.md) and [../AGENTS.md](../AGENTS.md).

## Google Play

**Version 3.6.5 / 202611 is handled outside this repository workflow.** Do not modify, build or upload a release from repository tasks. No release AAB is produced by agent work.

## Closed work

- **Issue #1 — source of truth.** Resolved; GitHub `main` declared canonical.
- **Issue #2 — key rotation / repo privacy / history.** Closed.
- **Issue #9 — endless splash screen.** Splash now waits a bounded time, retries with backoff, and shows an offline/error state with Retry.
- **Issue #10 — release signing guard.** The guard fails when the task graph is ready, before any task runs, so no unsigned `.aab` reaches disk.
- **Issue #13 — headphones disconnect.** Playback pauses when the audio output disappears instead of continuing on the phone speaker.
- **Issue #14 — Play sometimes did nothing.** Silent failure paths in the UI → service start sequence removed: stream ids come from an allow-list, invalid/missing keys fall back instead of leaving an empty player, a Play arriving before the MediaController is ready is queued and run once, and service-start failures are recorded.
- **Issue #16 — cleartext downgrade.** A stream error can no longer latch the app into plain HTTP for the session.

## Open work

- **Issue #15 — playback can stop by itself during continuous listening. OPEN.** Recovery was substantially improved (bounded, network-aware reconnect; `STATE_ENDED` on a live stream now treated as a disconnect), but **the root cause of the user reports is not proven**. Closing it needs a `MyataPlayback` log from a real affected device showing the stop with its cause and then automatic recovery.
- **Playback diagnostics are in `main`**: every playback decision is logged under one tag — `adb logcat | grep MyataPlayback`. This is what #15 will be diagnosed with.
- **Phase 2 / 3.6.6 — not implemented.** Design source lives in `tools/figma-export/` (approved dark screens, light/dark semantic tokens, plugin sources, rendered previews). No Phase 2 code exists yet: the app is still Material Components (M2), XML Views, with no `values-night/` and no `dimens.xml`.
- **Repository hygiene, needs owner decision**: `app/release/` and `app/src_backup_best_version/` are still tracked; no CI (build/lint on PRs); no release runbook.
- **Typography audit — RecyclerView rows never receive the typography contract. FUTURE, not fixed.**
  `MyataTypography.Factory` is installed on the Activity's inflater in
  `MainActivity.onCreate`. Adapters inflate their rows with
  `LayoutInflater.from(parent.context)`, which is a **different** inflater object
  and carries no factory, so those rows get neither the token's line height nor
  `includeFontPadding=false` — the two halves the factory exists to add, since
  `android:lineHeight` is unreadable below API 28 and `includeFontPadding` is not a
  text-appearance attribute at all.
  Measured on device while fixing the PLAYER History row (PR #54): its title had
  `includeFontPadding=true` and `lineSpacingExtra=0`, and its height came from
  `minHeight` dimens alone, with the font's overshoot adding 2px to the title and
  3px to the artist. PR #54 set both explicitly in `PlayerHistoryAdapter`'s
  ViewHolder for that one row and deliberately did **not** widen.
  Still on the old path: `FavoritesAdapter`, `HistoryAdapter`, `PlaylistAdapter`.
  Fixing them by routing the inflater through the factory would apply the shared
  tokens' Figma leading, which changes row heights on COLLECTION, the History
  bottom sheet and the HOME playlist row — so this is a measure-then-decide audit
  per surface, not a one-line change, and it needs owner approval before any of
  those surfaces move.
- **Startup performance — deferring the MediaController connection. FUTURE, deliberately not done.**
  `StreamsViewModel.init` calls `setupMediaController()`, which binds to the
  in-process `MediaPlayerService`; that binding creates the service, and its
  `onCreate` builds the whole ExoPlayer stack — `DefaultLoadControl`,
  `AudioAttributes`, the OkHttp data source, `ExoPlayer.Builder`, session and
  notification provider — synchronously on the main thread, between
  `activityResume` and the first drawn frame. Traced at **447ms** on a debug APK
  with no dexopt and **105ms** with dex verified at install
  ([`tools/qa/splash/`](../tools/qa/splash/)).
  It is the only app-controlled main-thread blocker before first frame, and it was
  **not** changed in PR #53 by owner decision: it is 3-12% of a launch whose cost
  is dominated by debug/emulator class loading, and it touches the playback and
  session-restoration ordering that CLAUDE.md flags as fragile. Any attempt needs
  its own plan and a re-run of the launch-with-surviving-playback checks.
- **Cold-start timing must be re-measured on a release build before any startup work. RC/release gate.**
  Every number recorded so far is from a `debuggable` debug APK on a software-GL
  emulator, and that is not a startup measurement. Two findings make it
  unusable as a baseline: `adb install -r` leaves the app at
  `[status=run-from-apk]` with no dexopt, which alone cost **~1.7s of a ~3.9s
  launch** (median 3939ms un-dexopted vs 2222ms verified, 7 and 6 cold launches);
  and ART refuses to AOT-compile a debuggable app at all — a forced
  `compile -m speed` stopped at `verify`. A release build additionally gets R8
  shrinking, which cuts the class graph that dominates `bindApplication`.
  **Decide whether startup optimisation is required only after measuring a
  release / non-debuggable build on a real device.** Owner action: agents cannot
  run `assembleRelease`.

## Phase 2 / 3.6.6 scope (agreed, not started)

New design, Light / Dark / System, new screens, auth / profile / settings, cloud favorites, Supabase. The playback fixes already in `main` ship as part of the same 3.6.6 release. Nothing here has been implemented, and each step needs explicit owner approval.
