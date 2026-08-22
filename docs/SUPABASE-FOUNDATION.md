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

What the dependency costs, measured with R8 and `shrinkResources` on:

```
release APK   9.60 MB -> 10.38 MB    +815,164 bytes  (+0.78 MB,  +8.1%)
release AAB  12.77 MB -> 14.70 MB  +2,020,885 bytes  (+1.93 MB, +15.1%)
```

The debug APK grows by 8.9 MB, and that number is worth ignoring: it is
unminified, and R8 removes nine tenths of the difference. The AAB grows more than
the APK because it carries every split; what a listener actually downloads is
derived from it per device and is smaller again.

The ProGuard keeps in `app/proguard-rules.pro` were written blind and are still
unproven: the release build succeeds, but no auth call has been exercised through
an R8-processed binary, because nothing calls the sync boundary yet. That check
belongs to the phase that does.

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

A modern `sb_secret_…` key is an **API key, not a JWT**, so owner-side calls send it
in the `apikey` header only:

```bash
curl -s "$SUPABASE_URL/rest/v1/track_reaction_totals?limit=5" -H "apikey: $SECRET"
```

`Authorization: Bearer sb_secret_…` is wrong and does not authenticate. `Bearer` is
for user access tokens, which *are* JWTs — that is what `rls-check.sh` sends for its
two anonymous listeners.

Configuration lives in `supabase.properties` in the project root — untracked, the
same route release signing uses, template in `supabase.properties.example`:

```properties
SUPABASE_URL=https://YOUR-PROJECT-REF.supabase.co
SUPABASE_PUBLISHABLE_KEY=sb_publishable_...
```

**No file is a supported state.** A fresh clone and CI have none, `isConfigured` is
false, `SupabaseModule.client()` returns null, and the app has no Supabase in it.

## Schema — Model C

`supabase/migrations/`, applied in order and each idempotent. Two tables, one
owner-only view, **no catalogue table and no client-callable function**.

| migration | what it does |
|---|---|
| `0001_reaction_foundation.sql` | the two tables, the RLS policies, the view |
| `0002_neutral_is_a_value.sql` | widens `reactions.reaction` to admit NEUTRAL; excludes all-NEUTRAL tracks from the view |

`0001` is left exactly as it ran and carries a banner saying which of its comments
`0002` supersedes. A fresh project applies both.

- **`reactions`** — current state. `primary key (listener_id, track_key)`, which
  makes one-listener-one-opinion structural rather than something the client has to
  remember. Carries `artist` and `title` as observed on that listener's device.
  `reaction` is **`NEUTRAL | LIKED | DISLIKED`**: a withdrawal stores NEUTRAL and
  keeps its row, because an absent row has no `updated_at` and therefore cannot take
  part in the last-writer-wins comparison that protects every other state
  (`docs/SUPABASE-SYNC.md`). Tombstones are kept indefinitely for v1.
- **`reaction_events`** — append-only history, carrying its own `artist`/`title`
  because the row is immutable and the words it was written with are part of what
  happened. `event_id` is a client UUID and the idempotency key, so a retried
  delivery conflicts instead of double-counting. It cannot be backfilled later,
  which is why it exists now.
- **`track_reaction_totals`** — aggregated straight from `reactions`, **service
  role only**; `anon` and `authenticated` revoked. The displayed words are the most
  common spelling observed (`mode()`), which changes no counts. Since `0002` a track
  appears only while at least one listener currently holds a LIKED or DISLIKED: a
  0/0 row is sync metadata, not a programming signal. Likes and dislikes count those
  two states and nothing else.

Both tables constrain `track_key` to a TrackKey v1 shape (64 lowercase hex, or the
`legacy:` namespace) and bound artist/title to 1–300 characters.

### Why there is no `tracks` table

The station's metadata API has no track id, so identity is a hash the **device**
computes from artist and title (`docs/TRACKKEY-V1.md`). Anything a device can
compute, a device can fabricate — and validating the *shape* of a key proves
nothing about its *truth*.

The first version of this schema had a `tracks` catalogue that clients could write
to. Its INSERT policy was `with check (true)`, and the live project proved what
that meant: an anonymous client inserted a junk key with an artist of
`<script>whatever` and a 300-character title, having reacted to nothing. The
attempted fix — drop the policy, add a validating `SECURITY DEFINER` RPC — was
abandoned because it narrowed the door while keeping the room: any anonymous
identity could still create arbitrary valid-*looking* rows, so the table still
could not honestly be called the station's catalogue.

Model C removes the problem instead of guarding it. A reaction carries its own
words, so:

- there is **no shared writable table** between listeners, and nothing for one
  listener to pollute for everybody else;
- junk can exist only as somebody's **own** reaction row, already bounded by RLS
  and by the `(listener_id, track_key)` primary key;
- the write path is **one row** — no foreign key, no ordering, no RPC in front of
  it, which is one less thing for the offline sync phase to get wrong;
- the trust boundary is **structural rather than editorial**. A row saying "this
  listener saw these words and reacted" is a claim the row can support. "The
  station's catalogue" was not.

**A trusted catalogue is still possible, and is a separate thing.** When the
station wants one it arrives as owner/server-managed `station_tracks`, populated
from the playout system, with no client policies at all, joining this data by
`track_key`. The key is stable, so no reaction data has to move for that to happen.

### RLS

| table | select | insert | update | delete |
|---|---|---|---|---|
| `reactions` | own rows | own rows | own rows | own rows |
| `reaction_events` | own rows | own rows | **none** | **none** |
| `track_reaction_totals` | service role only | — | — | — |

"Own" is `(select auth.uid()) = listener_id`, enforced with `with check` on every
write, so a client cannot insert a row owned by somebody else. `reaction_events`
has no update or delete policy for any client role — history a client can edit is
not history.

`reactions` keeps its DELETE policy even though normal sync stopped using it at
`0002`. What still needs it is a listener erasing their own rows, and the
retirement half of the future identity handoff below; removing it would take the
first of those away to tidy up the second.

**No `listeners` table.** Supabase Auth already owns identity and `auth.users.id`
*is* the listener; a second table would be a copy that can drift. No profile,
follow or comment tables either — this is programming analytics, not a social
network.

`tools/supabase/rls-check.sh` proves this against a real project with two throwaway
anonymous users, including a guard that fails if a client-writable catalogue is
ever reintroduced.

**On reading its results:** when RLS filters rows away, PostgREST answers 204 — the
statement matched nothing — not 403. So for a write that policy is meant to stop,
the status code says nothing useful and the script reads the row back instead. Its
first version trusted the codes and reported three false failures.

### Re-baselining the live project

The live project was created against the earlier schema and still holds it. Because
it contains only test data and no listener reactions, the correction is a
re-baseline rather than a migration chain whose first entry is a security hole:

1. `supabase/rebaseline/00_preflight_readonly.sql` — **read-only**. Counts rows in
   `reactions`, `reaction_events` and `tracks`, lists the objects that exist, and
   flags anything that does not look like test data. Changes nothing.
2. `supabase/rebaseline/01_rebaseline_model_c.sql` — **destructive**. Drops the
   five reaction-foundation objects and recreates Model C in one transaction. It
   touches nothing in `auth`, so existing anonymous listeners keep their uids, and
   nothing outside the objects it names.

The reset file's schema half is generated from `0001`, so the two cannot drift —
which also means it reproduces the pre-`0002` model, and `0002` must be run after it
if it is ever used again.

**Once any real reaction exists, the re-baseline must never be run again** — after
that only a forward migration is acceptable. `0002` is the first of those: it drops
no table, deletes no row and rewrites no data.

## Anonymous auth

Invisible, and **created only when there is something to own**.

Opening the radio is not a reason to exist in a database. Most listeners never
react to anything, so signing each of them in would fill `auth.users` with
identities that own nothing, add a request to every cold launch, and hand an open
sign-up endpoint one call per app start. So the two entry points differ:

| | called by | may sign in? |
|---|---|---|
| `AnonymousSession.restore()` | `MyataApplication` at startup | **no** - loads an existing session or does nothing |
| `AnonymousSession.ensureAuthenticatedListener()` | the sync boundary, later | yes, but only if this install has never had an identity |

Nothing calls `ensureAuthenticatedListener` yet. It exists so the phase that syncs
reactions has a tested boundary to call instead of inventing one.

### Never a second identity

The rule that matters most is what happens when a session cannot be refreshed -
offline, project paused, token expired. Signing in again would mint a **second**
`auth.uid()` for one person and split their data permanently, on exactly the flaky
networks this app is used on. So an install records that it has had an identity,
and once that marker is set `ensureAuthenticatedListener` returns null rather than
minting a replacement. Losing one sync is recoverable; splitting a listener is not.

That marker is written with `commit()`, not `apply()`, and the difference is the
guarantee itself. `apply()` flushes on a background thread, so a process death
right after a sign-in can lose it - **which is exactly what an API 36 force-stop
did**, leaving a zero-length preferences file and an install that believed it had
never signed in. The next call would have minted a second identity. It is one short
string, already off the main thread, so the synchronous write costs nothing worth
having.

### Abuse and rate limits — real, decided, and deferred on purpose

Anonymous sign-in is an open door: anyone with the publishable key can mint
identities, and each one is a vote in the aggregates. Supabase applies a default
**30 sign-ups per hour per IP**, and recommends invisible CAPTCHA or Turnstile.

**Owner decision, G-A1: CAPTCHA stays off, and this is not an oversight.** A
project-wide CAPTCHA toggle would break every client already in the field, because
the anonymous identity can be minted from a background WorkManager run — the sync
boundary, with no Activity and no user present — which has no way to produce a
challenge token. Turning it on would silently stop reactions syncing for existing
installs, which is worse than the abuse it prevents at this scale.

Also decided: **anonymous listeners are not excluded from the station aggregate.**
They are the overwhelming majority of the audience and their feedback is the data
this programme exists to collect; dropping it to sidestep an abuse question would
throw away the signal to protect the metric.

So anti-abuse and rate-limit hardening stay a **separate production concern**, to
be taken as its own piece of work — most likely CAPTCHA gated on a foreground
sign-in path once accounts exist, so a background mint is never asked for a token
it cannot supply. What it means until then: read the aggregates as *one identity,
one vote*, not one human, and treat that as a caveat on the numbers rather than a
reason to distrust them.

## Accounts and identity handoff — the frozen contract for G-A7

**Nothing here is implemented.** It is written down now because `0002` changes what
a correct handoff looks like, and the wrong version of it is easy to reach for and
impossible to undo once it has run. G-A1 implements none of it; G-A7 inherits it.

The situation: an install has been reacting as anonymous identity **X**. The
listener then signs into an existing account **Y**, which already has its own
reactions and its own history from another device.

### The rule

> **Adopting state must never fabricate history.**

Concretely, when X signs into Y:

1. **X's `reaction_events` stay under X.** They are what actually happened, to that
   identity, at those times. They are not moved, not rewritten and not re-attributed.
   The table has no UPDATE and no DELETE policy for any client role, so this is
   structural rather than a promise.
2. **X's `reactions` are retired before the identity switch**, as already designed —
   X's current state stops asserting an opinion once X is no longer the listener
   using this device.
3. **After switching to Y, the CURRENT local reaction states are adopted into Y's
   `reactions`** through a **state-only reconciliation path**: the same guarded
   upsert the sync engine already uses, over the local `track_reaction` rows, writing
   nothing but current state.
4. **No synthetic `LIKE` / `UNLIKE` / `DISLIKE` / `UNDISLIKE` is created by the
   adoption.** Not one.

### Why re-enqueueing every local reaction as an event is wrong

The obvious implementation — walk `track_reaction` and push each row into the outbox
as an event under Y — is wrong twice over, and the second reason is the one that
makes it unfixable rather than merely untidy:

- **It fabricates history.** Y would acquire a burst of events all stamped now, for
  opinions formed over months by a different identity. `reaction_events` is the table
  that answers "when did the audience change its mind", and this is precisely the
  answer it would corrupt. It is append-only, so the corruption is permanent.
- **It cannot be done correctly for NEUTRAL at all.** Since `0002` a local NEUTRAL row
  is a real state that must be adopted — and **the state row does not record how it
  got there.** NEUTRAL after an UNLIKE and NEUTRAL after an UNDISLIKE are the same
  row. There is no honest event to synthesise for it: any choice invents a transition
  that may never have happened, and omitting it leaves the adoption incomplete.

That second point is why the rule is a hard separation rather than a preference. The
information needed to fake the history is not merely inconvenient to obtain — it does
not exist in the data being adopted. A state-only path needs none of it.

### What this leaves open for G-A7

- **Merge policy when X and Y both hold an opinion on one track.** The reconciliation
  is guarded by `updated_at`, so it resolves by timestamp today — which is the same
  device-clock caveat named below, now with real weight behind it, since two devices
  is exactly the case accounts create.
- **Cross-device clock skew.** `updated_at` is the device wall clock, and comparing
  two of them is only as good as the two clocks agree. This is an explicit G-A7
  conflict-resolution item, to be answered there with a server-assigned time or a
  version counter. It was deliberately **not** touched in G-A1: the schema change did
  not need it, and putting an unproven ordering scheme underneath it would have made
  both harder to trust.
- **What happens to X's `auth.users` row** once it owns no current state — retained
  for its history, which is the only thing left pointing at it.

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
