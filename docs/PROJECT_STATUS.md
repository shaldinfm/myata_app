# PROJECT_STATUS.md — MyataRadio

Last updated: 2026-07-03.

## Current canonical state

- **GitHub `main` (https://github.com/shaldinfm/myata_app) is the single source of truth** — see [SOURCE_OF_TRUTH.md](SOURCE_OF_TRUTH.md).
- `main` contains the current app code: **versionName 3.6.4 / versionCode 202610** (imported from the D working copy, PR #5).
- Release signing lives only in a local untracked `keystore.properties` (PR #4) — see [RELEASE_SIGNING.md](RELEASE_SIGNING.md). No secrets in the tracked tree.
- Local folders D / A / B are **read-only archives**, not development sources.
- All new work: fresh clone → branch → draft PR into `main`. Agents follow [../CLAUDE.md](../CLAUDE.md) and [../AGENTS.md](../AGENTS.md).

## Closed work

- **Issue #1 — source of truth: completed.** The divergence between four project copies was investigated and resolved; GitHub `main` declared canonical (PR #3 documented the problem, PR #6 finalized the docs).
- **Current code imported to GitHub.** The 3.6.4 / 202610 working copy from folder D was imported into `main` via an allowlist, without secrets/artifacts/backups (PR #5, commit `d145ef9`); `assembleDebug` from a fresh clone was verified.
- **Signing removed from the current tree.** Hardcoded signing values were replaced with a local untracked `keystore.properties` (PR #4, commit `0bf9880`).

## Open work

- **Issue #2 — key rotation / repo privacy / git history cleanup.** Old signing secrets remain in git history; keys need rotation and the history needs owner-driven cleanup. **Open, highest priority.**
- **Cleanup of old APK/AAB and backup folders.** `app/release/` (old release artifacts) and `app/src_backup_best_version/` are still tracked in the repo; the archive folders D/A/B still exist on disk. Removal/archiving needs an explicit owner decision.
- **`UnsafeNetModule` / TLS security issue.** The app relaxes TLS certificate validation via `UnsafeNetModule.kt`, used across networking (metadata, player datasource). Needs a proper fix (correct certificates or scoped trust), planned and tested.
- **Player reliability / ExoPlayer errors.** Stream reconnect and error handling in `MediaPlayerService` need hardening (recovery after network loss, error surfacing).
- **Metadata polling loops.** Duplicate/looping now-playing metadata polling should be consolidated into a single well-behaved poller.
- **Release runbook / CI.** No documented step-by-step release process and no CI (build/lint checks on PRs) yet.

## Recommended next steps — NOT STARTED, require owner GO

1. Resolve issue #2 first: rotate the signing key, decide on repo privacy, clean git history (owner-driven).
2. Decide and execute cleanup of tracked artifacts (`app/release/`, `app/src_backup_best_version/`) and physical archiving of D/A/B folders.
3. Plan and implement the `UnsafeNetModule`/TLS fix.
4. Add minimal CI (debug build + lint on PRs), then write a release runbook.
5. Address player reliability and metadata polling as separate, scoped PRs.

None of the above has been started; each item needs explicit owner approval before any agent acts on it.
