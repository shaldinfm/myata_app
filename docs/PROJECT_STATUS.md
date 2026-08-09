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

## Phase 2 / 3.6.6 scope (agreed, not started)

New design, Light / Dark / System, new screens, auth / profile / settings, cloud favorites, Supabase. The playback fixes already in `main` ship as part of the same 3.6.6 release. Nothing here has been implemented, and each step needs explicit owner approval.
