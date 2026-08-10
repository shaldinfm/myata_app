# CLAUDE.md — MyataRadio (Android)

Guidance for Claude Code and other AI agents working in this repository.
Read [AGENTS.md](AGENTS.md) (hard rules) before doing anything.

## Project overview

- Android internet-radio app "Myata Radio" (package `com.example.musicplayerapp`).
- Kotlin + Gradle. `minSdk 24`, `targetSdk 36`, `compileSdk 36`.
- Playback: AndroidX **Media3 / ExoPlayer** (`media3-exoplayer`, `media3-session`, `media3-datasource-okhttp`), foreground `MediaPlayerService`.
- **Firebase**: Crashlytics + Analytics (BoM). `app/google-services.json` is present — never print or modify it.
- Current released/imported state: **versionName 3.6.4 / versionCode 202610**.

## Source of truth

- **GitHub `main` of https://github.com/shaldinfm/myata_app is the ONLY source of truth.**
- All work starts from a fresh clone / branch off `main`, and lands via PR (draft by default).
- Local folders **D** (`D:\MyataRadio\`), **A** (`d:\Myata Site\Android\MyataRadio\MyataRadio\`) and **B** (`...\MyataRadio\myata_app\` old clone) are **archives, read-only**. Never edit, build from, or "sync" them; never copy files from them into this repo without explicit owner instruction.
- Details and history: [docs/SOURCE_OF_TRUTH.md](docs/SOURCE_OF_TRUTH.md). Current state and backlog: [docs/PROJECT_STATUS.md](docs/PROJECT_STATUS.md).

## Safe commands and checks

Read-only / low-risk (no GO needed):

- `git status`, `git log`, `git diff`, `git fetch` — repo inspection.
- Reading any source/docs files (except printing secret contents — see below).
- `./gradlew tasks`, `./gradlew help` — build-system inspection.
- `./gradlew assembleDebug` — debug build to verify compilation, only when the task requires it (never release variants).
- `gh pr view` / `gh issue view` — GitHub inspection.

Everything else — especially anything in the next section — needs an explicit GO from the owner.

## Dangerous areas — do not touch without explicit GO

| Area | Why it is dangerous |
|---|---|
| **Release signing** | Configured via local untracked `keystore.properties` (see [docs/RELEASE_SIGNING.md](docs/RELEASE_SIGNING.md), PR #4). Never create/edit/print keystores, `keystore.properties`, passwords or aliases. Never run `assembleRelease`/`bundleRelease`. |
| **Keystore / secrets** | Issue #2 (key rotation, repo privacy, git history cleanup) is still open — old secrets exist in git history. Never print them, never rewrite history yourself. |
| **Google Play / RuStore** | Store listings, uploads, releases — owner-only, always. |
| **Firebase** | `google-services.json`, Crashlytics/Analytics config, Firebase console — do not modify or print. |
| **`UnsafeNetModule` / TLS** | `app/src/main/java/com/example/musicplayerapp/UnsafeNetModule.kt` relaxes TLS validation and is wired into networking (metadata, player datasource). Known security debt — do not extend its use; fixing it is a planned, owner-approved task, not a drive-by change. |
| **ExoPlayer reconnect / error handling** | Stream reconnect and error-recovery logic in `MediaPlayerService` is fragile and user-facing; changes need a plan and testing, not quick patches. |
| **Metadata polling** | Known issue with duplicate/looping now-playing metadata polling (`MetadataRepository`, service, ViewModel). Do not add more pollers; consolidation is a planned task. |
| **Old release artifacts / backups** | `app/release/` (old APK/AAB) and `app/src_backup_best_version/` are legacy leftovers pending owner-approved cleanup. Do not delete, and do not treat backup sources as current code. |

## Working style

- Default modes: **plan-only, review-only, docs-only**. Code changes only when explicitly requested, as small focused PRs.
- Open PRs as **draft**; the owner flips them to ready and merges.
- If something blocks you (missing access, ambiguity, unexpected repo state) — **stop and report**, don't improvise.
