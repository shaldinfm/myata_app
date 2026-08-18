# Supabase foundation

What this is: the backend and the anonymous identity that a later phase will sync
reactions to. Nothing syncs yet. The local reaction model in Room stays the source
of truth, and the app behaves exactly as it did before this landed.

## The client: supabase-kt, not hand-written HTTP

**Chosen: `supabase-kt` Auth + PostgREST, on the Ktor OkHttp engine, with core
library desugaring.**

The alternative was to keep the existing OkHttp stack and call the Auth and
PostgREST REST endpoints directly. PostgREST alone would favour that — it is
ordinary JSON over HTTP and we already have the client for it. Auth is what
decides it:

| | supabase-kt | raw OkHttp |
|---|---|---|
| anonymous sign-in | `auth.signInAnonymously()` | one POST — easy |
| **session persistence** | Android session storage, restored on launch | write it |
| **token refresh** | refresh before expiry, on 401, single-flight | write it |
| account upgrade later | `updateUser` / `linkIdentity`, same uid | write it |
| dependency cost | Ktor 3.3.1 + kotlinx-serialization + desugaring | none |
| maintenance | track the SDK | own the auth code forever |

A refresh loop that is subtly wrong does not fail loudly. It signs a listener out
weeks later, or worse, silently creates a *second* anonymous identity and splits
one person's collection in two — and the reaction data is the thing this whole
programme exists to get right. That is not a place to save a dependency.

What the dependency costs, measured on the **debug** APK:

```
main       21,056,659 bytes (20.1 MB)
branch     30,383,742 bytes (29.0 MB)   +8.9 MB, +44%
```

Debug is unminified. The release build runs R8 with `shrinkResources`, which will
remove most of it, but **that number has not been measured** — release variants are
owner-only in this repo, so nobody has yet run R8 over this dependency set. Getting
a measured release delta, and confirming the ProGuard keeps in
`app/proguard-rules.pro` are right (or unnecessary), is an owner step before sync
ships.

Two consequences worth naming:

- **Version pin.** supabase-kt `3.2.6`, not the current `3.7.0`. 3.7.0 is built
  with Kotlin 2.4 and publishes metadata this project's Kotlin `2.2.21` cannot
  read — it fails the build, and drags `kotlin-stdlib` 2.4 onto every module's
  classpath. 3.2.6 is the last line built with exactly our Kotlin. Moving up means
  moving the project's Kotlin first, which is its own piece of work.
- **`androidx.activity` 1.9.0** arrives transitively, where
  `ComponentActivity.onNewIntent` is `@NonNull`. `MainActivity`'s override changed
  signature to match; nothing else about it changed.

### API 24

supabase-kt documents **API 26** as its minimum, and this app's `minSdk` is **24**.
The documented answer is core library desugaring, which is now enabled. It matters
more than a build flag suggests: when desugaring is wrong the build still succeeds
and the failure is a `NoClassDefFoundError` on an old device. Hence the API 24 gate
below, which is not optional.

The Ktor engine is OkHttp, configured with the app's own `SecureNetModule` client,
so Supabase traffic inherits the connection pool, the timeouts, and the platform
`network_security_config` — including the roots bundled for old Android trust
stores. A second HTTP stack would mean a second TLS configuration to get right on
exactly the devices least able to cope.

## Keys

Supabase's current model is **publishable** (`sb_publishable_…`) and **secret**
(`sb_secret_…`) keys; the legacy `anon` / `service_role` JWTs still work and are
deprecated by the end of 2026.

- The app ships the **publishable** key. It is public by design and grants nothing
  on its own: every table is behind RLS and every row is owned by an `auth.uid()`.
- The **secret** key bypasses RLS entirely and must never be in the APK, this repo,
  or CI. The build fails if `supabase.properties` holds one; `SupabaseConfig` has
  the same check at runtime.

Configuration lives in `supabase.properties` in the project root — untracked, the
same route release signing uses, template in `supabase.properties.example`:

```properties
SUPABASE_URL=https://YOUR-PROJECT-REF.supabase.co
SUPABASE_PUBLISHABLE_KEY=sb_publishable_...
```

**No file is a supported state.** A fresh clone and CI have none, `isConfigured` is
false, `SupabaseModule.client()` returns null, and the app has no Supabase in it.

## Schema

`supabase/migrations/0001_reaction_foundation.sql`, idempotent.

- **`tracks`** — catalogue keyed by TrackKey v1 (`docs/TRACKKEY-V1.md`), with the
  artist and title kept beside the key because the key cannot be reversed.
- **`reactions`** — current state, `primary key (listener_id, track_key)`. That key
  is what makes one-listener-one-opinion structural rather than something the
  client must remember. NEUTRAL is absence: a withdrawn reaction deletes the row.
- **`reaction_events`** — append-only history. `event_id` is a client UUID and the
  idempotency key, so a retried delivery conflicts instead of double-counting.
  This cannot be backfilled later, which is why it exists now.
- **`track_reaction_totals`** — the station's aggregate, **service role only**;
  `anon` and `authenticated` are revoked.

**No `listeners` table.** Supabase Auth already owns identity and `auth.users.id`
*is* the listener; a second table would be a copy that can drift, and every policy
reads `auth.uid()` without one. No profile, follow or comment tables either — this
is programming analytics, not a social network.

## RLS

Enabled on all three tables, because the publishable key is public: whatever a
policy permits, anyone can do.

| table | select | insert | update | delete |
|---|---|---|---|---|
| `tracks` | any signed-in listener | any signed-in listener | **none** | **none** |
| `reactions` | own rows | own rows | own rows | own rows |
| `reaction_events` | own rows | own rows | **none** | **none** |
| `track_reaction_totals` | service role only | — | — | — |

"Own" is `(select auth.uid()) = listener_id`, enforced with `with check` on writes,
so a client cannot insert a row owned by somebody else. `tracks` has no update or
delete policy at all, so nobody can rewrite the words attached to another
listener's key.

`tools/supabase/rls-check.sh` proves this against a real project with two throwaway
anonymous users: A operates on its own rows, A cannot write as B, B cannot see or
change A's rows, events cannot be edited or deleted, and nobody but the service
role can read the totals.

## Anonymous auth

Invisible. No login screen, no prompt, nothing for a listener to decide.
`MyataApplication` calls `AnonymousSession.ensureInBackground()` at startup; it is
one request on `Dispatchers.IO`, every failure path ends in a log line, and a build
with no project configured never starts it. Nothing reads the session yet.

- **Enable it first**: Dashboard → Authentication → Providers → *Enable Anonymous
  Sign-Ins*. Without it, sign-in fails and the app carries on exactly as before.
- The user is a real row in `auth.users` with `is_anonymous: true` in the JWT, so
  policies can distinguish anonymous from registered later without changing shape.
- **The account upgrade is not a migration.** Linking an email or an OAuth identity
  keeps the *same* user id, so rows written today already belong to the account
  made later. That is the whole reason to use anonymous auth instead of a
  self-invented device id.

### Abuse and rate limits — a real, unaddressed concern

Anonymous sign-in is an open door: anyone with the publishable key can mint
identities, and each one is a vote in the aggregates. Supabase applies a default
**30 sign-ups per hour per IP**, and recommends invisible CAPTCHA or Turnstile.

This PR adds no anti-abuse UI, deliberately. What it means for now: read the
aggregates as *one identity, one vote*, not one human. Before those numbers drive
any decision that matters, the owner should turn on CAPTCHA/Turnstile for
anonymous sign-ins and review the rate limits.

## Validation

Automated (`SupabaseFoundationTest`): library loads and classes resolve on the API
level under test — the desugaring gate; an unconfigured build has no client and
does not crash; no secret key is compiled in. With a project configured, it also
covers client construction, anonymous sign-in, uid stability across calls, and that
the session is persisted and reloadable. Those skip, rather than pass, without
`supabase.properties`.

Manual, and part of the API 24 gate, because instrumentation cannot kill its own
process:

1. put `supabase.properties` in place, `./gradlew installDebug`;
2. launch, `adb logcat -s SupabaseAuth` → "signed in anonymously";
3. `adb shell am force-stop dlinemedia.radioplayer.myata`, launch again;
4. the log now reads "session restored", and the uid is the same one.

Then `tools/supabase/rls-check.sh` for the two-user policy matrix.
