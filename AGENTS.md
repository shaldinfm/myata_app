# AGENTS.md — hard rules for AI agents in myata_app

These rules are absolute. "Explicit GO" means the repository owner explicitly approves the specific action in the current conversation. When in doubt — there is no GO.

## Hard GO rules — never do without explicit GO

1. **No deploy.** No publishing, uploading, or releasing anything anywhere.
2. **No store actions.** No Google Play or RuStore operations of any kind (uploads, listings, tracks, rollouts).
3. **No signing / key / keystore actions.** No creating, editing, copying, printing, or referencing-by-value of keystores, `keystore.properties`, passwords, aliases, or certificates. No `assembleRelease` / `bundleRelease`.
4. **No environment / Firebase / repo-visibility changes.** No edits to Firebase config or console, `google-services.json`, CI secrets, environment variables, or repository public/private settings.
5. **No release builds.** Debug builds (`assembleDebug`) only, and only when the task requires verification.
6. **No database or migration actions**, if any appear in the project in the future.
7. **No branch or history rewrite.** No force-push, rebase of shared branches, `filter-repo`/BFG, branch deletion, or tag manipulation. Git history cleanup is part of issue #2 and is owner-driven.
8. **No cleanup deletion.** Old artifacts (`app/release/`), backup folders (`app/src_backup_best_version/`), archive copies D/A/B — nothing gets deleted, moved, or renamed without explicit GO.

## Working discipline

- **Plan-only / review-only / docs-only by default.** Produce plans, reviews, and documentation. Touch app code only when the owner explicitly asks for a code change, and keep it minimal and scoped.
- **Draft PR by default.** Every change lands via a pull request opened as draft. The owner marks it ready and merges. Agents never merge.
- **Never print secrets.** No keystore names, aliases, passwords, tokens, API keys, or `google-services.json` contents — not in output, commits, PR bodies, logs, or docs. If a secret is encountered, reference it only by role ("the release keystore password"), never by value.
- **Source of truth is GitHub `main` only** ([docs/SOURCE_OF_TRUTH.md](docs/SOURCE_OF_TRUTH.md)). Local folders D/A/B are read-only archives — never edit them or import from them without GO.
- **Blocker rule: stop and report.** On any unexpected state (dirty tree, diverged branches, missing access, ambiguous instruction, suspected secret exposure) — stop immediately, describe what was found, and wait for the owner. Do not work around it.
- **No extra scope.** Do not create issues, labels, workflows, or refactors that were not asked for.
