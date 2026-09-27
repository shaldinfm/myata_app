# Reaction sync: outbox → Supabase

What this is: the delivery half. `reaction_outbox` (PR #60) is drained to the Model C
schema (PR #59) by a WorkManager job. Room stays the source of truth, the reaction
path stays offline-first, and **the network never blocks a tap**.

Google Sheets telemetry is untouched and independent. A real transition now updates
Room, emits its Sheets report, enters the outbox, and asynchronously reaches
Supabase. Neither reporting path waits on the other and neither can fail the other.

## Migration status

| applied to production | SHA256 of the applied file | post-apply verification |
|---|---|---|
| **`0003_rev_and_atomic_apply.sql`** | `4ac40f03a1e93d862f61864a034a72b480fa8abe21ad04388803b11bb778b37f` | **PASS** (`verdict.overall = true`) |
| **`0004_account_deletion.sql`** | `a641e694550791e66e63a27a12d39ae712e6faa8ed35a8ada2ac197b37031b97` | **PASS** (see below) |
| `0005_causal_guard.sql` | - | **NOT APPLIED.** Must be applied before any client that sends `p_base_rev` ships; see [the causal guard](#a-queued-act-never-overwrites-a-newer-server-state) |

Each file named above, as it stands in this repository, is the exact file that was
executed, byte for byte - which is why `.gitattributes` pins the migration directory
to LF, so a checkout on any platform still hashes to the value recorded here.

0003 adds `reactions.liked_at`, the server-assigned `reactions.rev`, its sequence and
`SECURITY DEFINER` trigger, the `reaction_event_applications` log, and the
`apply_reaction_event_batch` RPC.

0004 adds account deletion: `account_deletion_receipts`, `delete_my_account` and
`account_deletion_status`. It is not part of the reaction sync path and is recorded
here only because this is where applied-migration provenance lives; the design is
[ACCOUNT-DELETION.md](ACCOUNT-DELETION.md).

Post-apply verification covered schema and privilege posture, one functional probe,
and data preservation. Specifically: `account_deletion_receipts` has RLS enabled,
FORCE RLS disabled, zero policies, no foreign key, and no direct privileges for
`anon`, `authenticated` or `PUBLIC`; `delete_my_account` takes `p_request_id uuid`
and no uid, is `SECURITY DEFINER` with an empty `search_path`, and its only client
EXECUTE is `authenticated` - `anon` and `PUBLIC` have none, while the production ACL
also showed the administrative roles `postgres` and `service_role`;
`account_deletion_status` takes two `uuid` arguments, is `SECURITY DEFINER` and
`STABLE` with an empty `search_path`, grants client EXECUTE to `anon` and
`authenticated` but not `PUBLIC`, and returned `{"outcome": "UNKNOWN"}` for one
random non-existent pair. The three reaction tables were unchanged across the apply
at 6 / 13 / 13.

**The deletion path itself was not exercised.** No verification step called
`delete_my_account`, so nothing here is evidence about how it behaves against a real
account. End-to-end validation belongs to the double-gated live instrumentation test
in a later PR, against a fixture account.

## G-A7 status

| stage | |
|---|---|
| **G-A7a** server `rev`, `liked_at`, application log, `apply_reaction_event_batch` | live on production, verified |
| **G-A7b** atomic push cutover, per-track LEGACY inheritance | merged |
| **G-A7c** full-scan pull | merged |
| **G-A7d** automatic triggers | merged |
| **G-A7e** initial-restore marker and profile sync state | this change |

**Cross-device reaction and Collection restore is satisfied.** A listener who signs in
on a second device gets their reactions back, and the Collection follows from them -
LIKED rows restore it, a stored NEUTRAL removes a stale membership, and there is no
separate Collection table to keep in step.

**What this is not.** There is no realtime subscription, no periodic worker and no
foreground or resume trigger, so changes made on another device are not visible the
instant they happen. The v1 convergence model is exactly:

```
a successful sign-in, registration or handoff   ->  one full scan
an ordinary app start with a restored session   ->  one full scan
```

A listener with two devices sees the other one's changes when they next open the app.
That is a deliberate trade: nobody pays for a poller, and the failure mode is
staleness rather than a wrong answer.

The **direct `reactions` INSERT/UPDATE policies deliberately remain**. Installed
pre-G-A7 clients write the table directly and must keep working for the whole
rollout; revoking them in favour of RPC-only writes is a separate, later hardening
step, and must not happen until the old population has drained.

## Whose Collection it is, and why a reinstall now restores it

One registered account has one Collection, and it lives in the account on the server.
The same account sees the same Collection on any phone, after a reinstall, after Clear
Data and sign-in, and from any future client. The local Room tables are a cache of it,
plus that account's own unsynced acts.

Before this, reinstalling gave inconsistent results. Liked rows that had never had an
outbox event (chiefly favourites migrated from the 3.6.4 `favorites` table) were never
uploaded after a direct sign-in, so a reinstall lost them. Meanwhile Auto Backup
restored whatever daily snapshot it had taken.

**Ownership (Room v5).** The active tables (`track_reaction`, `reaction_outbox`) hold
exactly one scope, recorded in `collection_scope`. Every other scope's rows and pending
acts are *parked* in `parked_reaction` / `parked_outbox`, each stamped with its owner:

| scope | whose |
|---|---|
| `device` | this install's own guest / anonymous Collection |
| `account:<uid>` | exactly one account's rows and unsynced acts |
| `legacy` | pre-v5 rows whose owner cannot be proven. Never active, never uploaded |

`CollectionScope.enterAccount` is the only way the active scope changes:

| active | entering account U |
|---|---|
| U | resume. Nothing moves |
| account X | park X intact, bring U's parked rows back |
| device | park it, bring U's back. **Adopt** the device rows into U only if this install was never an account before this authentication (identity `None`, or the anonymous handoff). A track U already holds locally keeps U's row, and the device's row and acts for it stay parked. Adopted rows carry the baseline `0` ("U had no row"), so on the server they land only where U holds nothing (see below). Nothing is overwritten or deleted |

When the account leaves, its rows and pending acts are parked under `account:U` and the
parked device Collection (or an empty one) becomes active. That covers an explicit
sign-out, a stored session that is gone (signed out elsewhere, or refused by the
server), and a restored database whose account isn't signed in here. A guest never sees
or changes an account's rows, and a guest's likes are the device's. Signing back into U
does not adopt them.

So signing into Z never shows, uploads or deletes Y's rows. Signing back into Y brings
them back, with Y's pending acts in their original order, still bound to Y. Switching
needs no network.

`CollectionScope.ensureScope` keeps the active scope in step with who the install
provably is: a registered identity plus a *stored* session. An offline listener whose
token can't be refreshed yet keeps their session in storage, and their Collection. It
runs at every start and after sign-out. The Collection screen and the PLAYER's
reaction additionally combine with `CollectionScope.visibility`, so they show nothing
of an account that isn't the signed-in one even before the rows have moved. A local
write first calls `prepareLocalWrite`.

Entering an account runs at every point an install becomes one: direct sign-in,
registration, recovery, the anonymous handoff and its recovery, reconciliation's
promotion, and pull eligibility. Sign-in and recovery are refused while an account
deletion is unresolved. The drain asks `CollectionScope` before it asks who it is. It never sends
a scope's rows under another session, and it mints no anonymous identity for an
account's rows. Account deletion removes that account's parked rows, and its active
rows only when the active scope is provably that account. It never removes another
account's, the device's or legacy rows.

**Pre-v5 databases.** `MIGRATION_4_5` marks the database `migrated` instead of guessing
an owner. The first use settles it from evidence:

| evidence | pre-v5 rows become |
|---|---|
| fresh install, never upgraded in place (`firstInstallTime == lastUpdateTime`): the database came from a backup or device transfer, and its identity did not | `legacy` |
| upgraded in place: handoff pending, or no account | `device` |
| upgraded in place: registered or signed out as U, and U is the only account this install ever pulled | U's |
| upgraded in place, but another account was pulled here too | `legacy` |

No account adopts `legacy` rows automatically. They are kept so that a future,
explicitly confirmed import can offer them. That needs a product decision and UI, and
neither exists yet.

**Local-only upload.** Before every pull, `LocalOnlyUpload` publishes the *active
account's* rows that have no revision and no pending act. It uses a batched
insert-if-absent (`ON CONFLICT (listener_id, track_key) DO NOTHING`): never an update,
and no events. A refused batch is retried row by row, so one bad row stays local and
doesn't block the rest. The scan that follows then adopts the account's version of
every track.

**Precedence per track, strongest first:**

1. A pending local act wins.
2. A server row above the local watermark wins, even over a restored or clock-ahead
   local row.
3. An unconfirmed local row is published if the account has none.
4. An empty or short server answer erases nothing.

**Registered without a session** (for example, a revoked token): sign-in and recovery
are allowed again, the same as signing in from `SIGNED_OUT`.

**Backup.** `supabase_identity`, supabase-kt's session in
`<applicationId>_preferences`, and `myata_last_sync` are excluded from Auto Backup and
device transfer. A restored refresh token has usually already been rotated. Restoring
it alongside the identity file produced the "registered, no session" dead end. The
restoring app's exclusions also apply at restore time, so these files don't come back
from older snapshots either (verified on API 36).

`myata_database` is **retained** on purpose. For a guest it is the only copy of their
Collection, and every row in a v5 copy carries its owner. A v4 copy is settled as
`legacy` by the table above.

## A queued act never overwrites a newer server state

One account has one Collection across every phone and the website, so the order that
matters is the order in which states reached the server, not the order in which
requests arrive. A Like tapped on an offline phone and delivered weeks later must not
land on top of a Dislike recorded elsewhere in between. Migration 0003 wrote state
unconditionally for any unseen event; migration 0005 adds a causal guard.

- **The baseline.** Each batch carries `p_base_rev`: the server revision this device
  last saw for the track (`track_reaction.remote_rev`). Local acts carry it unchanged
  (like and dislike no longer reset it). The pull doesn't advance it while acts are
  pending, and the drain sends all of a track's pending acts in one batch. So a whole
  offline chain (LIKE, UNLIKE, LIKE) is judged against the state it began from, and its
  own acts never conflict with each other.
- **Our own write advances it.** After an `APPLIED` answer the revision is recorded
  even if something was tapped during the call, so that act builds on this chain's
  write instead of conflicting with it.
- **The server decides, atomically.** It applies if the row is absent, at the
  baseline, or at a revision this batch's own already-applied events produced (an
  answer lost in transit). Otherwise it answers `CONFLICT`: nothing is written to
  `reactions`, the events stay in history, and they are marked
  (`reaction_event_applications.state_applied = false`). A retry is then
  `ALREADY_APPLIED`.
- **The client resolves, it doesn't retry.** On `CONFLICT` the batch's outbox rows
  settle, and the server's row is adopted if nothing else is pending for the track.
  An act tapped during a conflicting call keeps the old baseline, so it conflicts on
  its next run and the server's newer state wins.

No device clock takes part. Rollout: apply 0005 first; installed 3.6.6 clients keep
calling the unchanged 8-argument function and behave as before. A client that sends
`p_base_rev` to a database without 0005 gets PGRST202 and parks its outbox until the
migration lands.

**Acts before the account's initial restore.** On a fresh install, a reinstall or after
Clear Data, a tap made before the first pull of the account completes has no baseline:
the device has never seen the server's row. Sent as it is, it would get `CONFLICT` and
be replaced by the older server state. So until `LastSyncStore.isInitialRestoreComplete`
is true for the account, the drain holds the account's acts (`AwaitingRestore`). The
tap stays visible locally. The worker asks for the pull and retries on its usual backoff,
so a restore that failed on a bad network is retried.

The pull that completes the initial restore **rebases** each pending track that has no
baseline onto the revision it reads. The listener's new act is then judged as the newer
act it is, and wins; local state is untouched. A completed restore schedules the held
acts at once. Later pulls never rebase, so an unknown baseline after the restore is still
judged by the guard.

**Guest state adopted at sign-in never overwrites the account.** Rows adopted from the
device carry `CollectionScope.ADOPTED_BASELINE` (`0`; server revisions start at 1), not
`null`:
- a pending guest act is judged against "no row": it applies only if the account has
  nothing for the track, and otherwise gets `CONFLICT`, so the account's reaction wins;
- guest state with no pending act is published by the insert-if-absent upload;
- the anonymous handoff now adopts with that same insert-if-absent write, instead of the
  old `updated_at`-guarded upsert.

The initial restore rebases only `null`, which is reserved for acts made after signing
in.

Not covered, and unchanged: pre-cutover LEGACY outbox rows (the two-call path, guarded by
`updated_at`). Once an account's restore is
complete, a row whose revision this device never learned (`remote_rev` null) while the
server holds one is still judged a conflict. That can happen only in the short window
after a handoff or a device adoption, before the next pull.

## What the app can say about its own syncing

Three pieces of state, kept apart because they answer different questions — and all
three **keyed by listener uid**, because every one of them is a statement about an
account rather than about a phone:

| key | means | set by |
|---|---|---|
| `last_upload_<uid>` | something of *that account's* reached the cloud | a drain that delivered at least one row |
| `last_pull_<uid>` | this device read *that account* back in full | a **completed** full scan, never a partial one |
| `initial_restore_complete_<uid>` | it has done so at least once, ever | the same completed scan |

`Последняя синхронизация` shows the **more recent of the first two, for the account
on the screen**. They are never written into each other: an install can have pushed
without ever restoring, or restored without ever pushing, and collapsing them would
make the second read as `Ещё не синхронизировалось` on a device that had just pulled
a whole Collection down.

### Why per account, and why not cleared

An install that signs out of X and into Y has synchronised nothing as Y. A global
timestamp would show Y a moment earned by X — the row would be true of the device and
false of the account it is printed under, which is the same class of untruth this
phase exists to remove.

Scoping rather than clearing, deliberately. Wiping the timestamps at the identity
boundary would also answer the question wrongly, just less often: X *did* sync, that
stays true while Y is signed in, and switching back to X should find X's own history
where it was. Signing out is not evidence about the past.

Which account an upload belongs to comes from the drain that delivered it —
`DrainResult.Drained.listenerId`, carried out on the result — and never from whichever
identity is current when the worker does its bookkeeping. The drain has released
`SyncLease` by then, so a sign-out and a sign-in as another account can land in the
gap; asking "who am I now" would file X's delivery under Y.

Pre-G-A7e installs have two unscoped keys, `last_success_at` and `last_pull_at`.
They are **orphaned, never read and never migrated**: whose sync they record is not
recoverable, and guessing the current uid would attribute one account's history to
another. Such an install reads `Ещё не синхронизировалось` once, until its next
real sync.

`initialRestoreComplete` is durable and per account. It is **not** a cursor, **not**
the sixty-second trigger debounce, and **not** a claim that the account is current -
nothing in the pull or the trigger reads it, so a later app start full-scans exactly
as it would have. It exists so that "there is a registered account" and "this device
has actually restored it" stop being the same question.

## The data contract

| table | what it is | how it is written |
|---|---|---|
| `reaction_events` | immutable transition history | append, idempotent on `event_id` |
| `reactions` | current listener opinion | reconciled from the **current** `track_reaction` row |

```
local LIKED     ->  reactions row = LIKED
local DISLIKED  ->  reactions row = DISLIKED
local NEUTRAL   ->  reactions row = NEUTRAL      <- migration 0002
no local row    ->  reactions row deleted        <- data removal only
```

### NEUTRAL is a value, not a gap

The third line used to read `reactions row absent (deleted)`, and absence is a
tempting way to spell "no opinion" — it needs no vocabulary and the aggregate
counts rows for free. It fails on one thing: **a deleted row has no
`updated_at`.**

Every other state is protected by the last-writer-wins guard below, which asks
"is what is already there newer than what I am about to write". A delete cannot
be asked that question. It carries no timestamp to lose with, so a withdrawal
that had been stuck in an outbox for a week would remove a Like tapped five
minutes ago on another device, and the only tie-break left was delivery order —
the one thing this whole design refuses to depend on.

So migration `0002` widens the `reactions.reaction` CHECK to `NEUTRAL | LIKED |
DISLIKED`, and a withdrawal writes a row like everything else. Three
consequences, all deliberate:

- **the tombstone stays, indefinitely, for v1.** It is small, it is what makes
  reconciliation total, and a sweeper is a decision to take with real data in
  hand rather than up front;
- **`track_reaction_totals` hides tracks whose current rows are all NEUTRAL.** A
  0-like/0-dislike line is sync metadata wearing the costume of a programming
  signal. Likes and dislikes still count only those two states, and the track
  reappears the moment one listener holds an opinion again;
- **`reaction_events` is untouched.** Its four names are still exactly the four
  real transitions. Nothing manufactures a fifth event to go with the new state —
  state and history stay two different questions, which is the point of having
  two tables.

**DELETE is still a policy, and normal sync no longer uses it.** The only caller
left is a track whose *local* row is gone — clearing data, or the retirement half
of a future identity handoff — where there are no words left to write a row with
and absence really is the intent. Taking the policy away would leave a listener
unable to erase their own rows.

The second row of that table is the whole design. Remote current state is **never**
folded from the queued events; it is read from Room at send time. A row that has sat
in someone's pocket for a week still delivers its week-old history entry — that is
what history is — but the state it then writes is what the listener thinks *now*. So
delivery order is not load-bearing for correctness of the current state, and a
delayed, retried or out-of-order event cannot restore a stale opinion.

## The delivery algorithm

For each pending row, in local insertion order:

1. **deliver the event** to `reaction_events` with its original `event_id`,
   `occurred_at`, `artist`, `title` and `stream`;
2. **reconcile current state** from `track_reaction` read now;
3. **only then delete the outbox row.**

Step 3 last is the crash contract. A kill anywhere before it leaves the row pending
and the next run repeats both writes, which is safe because both are idempotent.
Deleting first would lose a reaction to a badly timed kill.

### Idempotency, as the server actually behaves

Verified against the live project before any of this was written:

| call | result |
|---|---|
| `POST reaction_events?on_conflict=event_id`, `Prefer: resolution=ignore-duplicates` | `201` + the row |
| the same call again | `201` + `[]` — nothing inserted, still success |
| plain `POST` of a duplicate `event_id` | `409` / `23505` |
| client `PATCH`/`DELETE` on `reaction_events` | `200` + `[]`, row unchanged — append-only holds |

`ignore-duplicates`, not `merge-duplicates`: `reaction_events` has an INSERT policy
and deliberately **no** UPDATE policy, so a merge would be refused by RLS on exactly
the retry path it exists to serve. Ignore-duplicates is `ON CONFLICT DO NOTHING`,
which needs only the INSERT policy. A retry never mints a new `event_id`.

### Last-writer-wins on current state

PostgREST cannot express a conditional merge in one call, so it is two:

1. `PATCH reactions … &updated_at=lte.<ours>` — if a newer row is there this matches
   nothing and we have correctly declined to go backwards;
2. if nothing matched, `POST … Prefer: resolution=ignore-duplicates` — creates the
   row if it is missing, does nothing if the newer one exists.

Both halves were probed live: a stale guard matched 0 rows and left the row alone; a
fresh guard matched 1; an ignore-duplicates insert against a newer row did not
clobber it.

All three states go through those two steps, NEUTRAL included — which is the
point of storing it. Only a missing local row still deletes, and **a delete that
matches nothing is success**: the desired state is "no row", and there being no
row already is that state.

> **Timestamps must end in `Z`.** `Instant.toString()` renders UTC that way. An
> offset written `+00:00` contains a `+`, which decodes as a space when the value is
> used as a query-string filter; Postgres then rejects it outright. A live probe
> reproduced exactly that: `invalid input syntax for type timestamp with time zone:
> "…T08:32:26 00:00"`.

### The clock is the device's, and that is a known G-A7 item

`updated_at` is the device wall clock at the moment of the tap, and the guard
above compares two of them. Within one device that is a total order and the guard
is exact. **Across devices it is only as good as the two clocks agree**, so a
phone running some minutes fast can win a comparison it should have lost.

This is unchanged by 0002 and deliberately not addressed here. It costs nothing
today — one identity has one device in practice, and the local Room state is the
source of truth the listener actually sees. It becomes real the moment accounts
let one person react from two devices, so it is an explicit conflict-resolution
item for **G-A7**, to be answered there with a server-assigned time or a version
counter. Widening this PR into a clock redesign would put an unproven ordering
scheme underneath a schema change that does not need one.

**Answered, server side, by migration 0003.** `reactions.rev` is assigned from a
global sequence by a trigger on every insert and update, so cross-device ordering no
longer depends on any device clock. `updated_at` is deliberately untouched - old
clients still guard their pushes with it, and changing its meaning underneath them
would break their writes. The client half of that answer is G-A7b and has not
shipped.

## FIFO order: `rowid`, not the clock

Pending order is `ORDER BY rowid ASC`. Not `occurred_at`, which is a device wall
clock that an NTP correction or a timezone change can move backwards between two
taps; not `event_id`, which is a random UUID and therefore arbitrary.

Rows are only ever inserted inside the reaction transaction, one per committed
transition, so SQLite's implicit `rowid` is causal insertion order. **It is strictly
local and ephemeral**: SQLite reuses the values of deleted rows, so it is never sent
to Supabase, never persisted elsewhere and never treated as a global sequence. The
only property relied on is that among rows pending *at the same time*, `rowid` order
is insertion order — which holds because a new row always gets one more than the
largest currently in the table.

## Scheduling, and why a row cannot be stranded

Two races, and a design that closes only one of them is the easy mistake.

**Race A — the row commits, then the process dies before anything is scheduled.**
Nothing inside a Room transaction can close this: WorkManager has its own database,
so an enqueue cannot join the commit. Enqueueing *before* the write is worse. So the
window is covered from the other side — `ReactionSyncScheduler.onAppStart` asks the
outbox on every cold start and schedules a drain if anything is pending. One indexed
`COUNT(*)` on a table that is almost always empty.

**Race B — a reaction commits while the worker is already RUNNING.**

| policy | a request arriving mid-run | verdict |
|---|---|---|
| `KEEP` | dropped | **loses race B** |
| `REPLACE` | cancels the running worker | a burst of taps starves every run |
| `APPEND` | queued behind the current run | closes B, but a failed run blocks the chain forever |
| `APPEND_OR_REPLACE` | queued behind it; replaces the chain if it failed or was cancelled | **chosen** |

Having the worker re-check the queue before returning does *not* close B: the check
and the return are not atomic with respect to KEEP, so a row committed after the last
check is still dropped while the worker is still RUNNING. The window shrinks; it does
not go.

Two things keep the append cheap rather than a pile-up: a run with an empty outbox
costs one `COUNT(*)` and no network and no identity; and **the worker never returns
`failure()`**, so the chain has nothing to poison it. A row the server refuses is
parked in the database, not turned into a failed work request that would cancel
everything chained behind it.

Constraint: `NetworkType.CONNECTED`. Backoff: exponential from 30s. Batch: 50 rows,
after which the run reports `MoreWorkDue` and appends its own follow-up.

### Waking a parked row

`APPEND_OR_REPLACE` closes both commit races, but **it is not a timer**. A row that
failed is given a `next_attempt_at` in the future, which makes it invisible to the
`due` query until its moment — and once a chain has finished, nothing in WorkManager
schedules anything by itself. A single row parked for an hour by a 4xx would otherwise
sit there until the listener happened to react again or restart the app.

So every run reports the moment anything it left behind becomes eligible —
`DrainResult.Waiting(until)` when nothing could be sent, or `Drained.nextAttemptAt`
when some rows went and others were parked — and the worker turns it into a delayed
request via `ReactionSyncScheduler.scheduleWakeUp`, taken from
`SELECT MIN(next_attempt_at) FROM reaction_outbox`.

Two properties of that timer are deliberate:

- **It has its own unique name** (`reaction-outbox-retry`), not the main chain. A
  delayed request appended to the main chain would put every reaction tapped
  afterwards behind it — a fresh Like could wait the full backoff, up to a day.
- **Its policy is `REPLACE`.** There is at most one meaningful "next wake-up", and a
  newly computed one always supersedes the pending one. Appending would build a queue
  of stale timers.

Overlap between the two chains is harmless: both remote writes are idempotent and the
outbox row is deleted only after both succeed, so the worst case of two runs meeting
is a duplicate round trip that changes nothing.

WorkManager persists a delayed request in its own database and reschedules it across
process death and reboot, so the timer survives everything short of an uninstall — and
`onAppStart` is still there behind it.

A run that finds only parked rows also **does not request an identity**: there is
nothing it may send, so asking would mint an anonymous user for somebody whose only
pending row is one the server has already refused.

## Signed out: paused, not failed

`SIGNED_OUT` is the one state where the right answer is to stop rather than retry.
The drain checks the identity **before** it reads the batch, so a paused run touches
no row: nothing delivered, no `attempts` incremented, no `next_attempt_at` moved.

| | auth temporarily unavailable | deliberately signed out |
|---|---|---|
| identity | `ListenerIdentity.Unavailable` | `ListenerIdentity.Paused` |
| drain | `DrainResult.RetryLater` | `DrainResult.Paused` |
| worker | `Result.retry()` | `Result.success()`, no reschedule |
| scheduler | enqueues normally | enqueues nothing |
| rows | untouched, retried later | untouched, wait for sign-in |

Fresh reactions still commit to Room and the outbox while paused — the Collection is
local and was never the cloud's copy. They go out on the next drain after signing back
into the same account. Signing into a different account parks them for this one; see
[Whose Collection it is](#whose-collection-it-is-and-why-a-reinstall-now-restores-it). See `docs/SUPABASE-FOUNDATION.md` for the state machine itself.

## Retry and failure policy

Classification, from what the live project actually returned:

| provoked | status | code | class | action |
|---|---|---|---|---|
| delivered, or already delivered | 201 | — | success | delete the outbox row |
| duplicate `event_id` on a plain insert | 409 | 23505 | success | history is correct |
| garbage/expired token | 401 | PGRST301 | **auth** | stop the run, **do not penalise the row** |
| event owned by another listener | 403 | 42501 | **permanent** | park ~1h→24h, **continue to the next row** |
| malformed `track_key` / unknown `event_type` | 400 | 23514 | **permanent** | same |
| rate limited | 429 | — | transient | park 30s→1h, stop the run |
| server error | 5xx | — | transient | same |
| timeout / DNS / reset | — | — | transient | same |
| anything unrecognised | — | — | transient | the safe direction |

Nothing is ever discarded. A row that cannot sync is the only evidence that something
is wrong, so it is kept, counted in `attempts`, and retried on a capped schedule —
fast enough that a server-side fix heals it without an app update.

One poison row cannot block unrelated later events: a permanent failure parks that
row and the loop **continues**. And it cannot leave its own track's remote state
wrong either, because the next event on that track reconciles from Room.

Logging is deliberately thin: the first eight characters of the key (a hash), the
transition, the attempt count and the server's reason. Never the artist, the title or
the listener id. Enough to find a stuck row in a bug report; not a record of what
somebody listens to. No analytics framework was added.

## Auth lifecycle

`ListenerSession.identity` is called **once per run, and only
after `count()` has proved there is work**. Three gates stand between opening the
radio and existing in `auth.users`:

1. `onAppStart` schedules nothing when the outbox is empty;
2. the worker returns `Idle` after one `COUNT(*)` when it is empty;
3. only then is the identity boundary reached.

`listener_id` is never stored in an outbox row — the identity is attached at send
time, because an anonymous identity may simply not exist when somebody reacts
offline, and blocking a Like on a sign-in round trip is what this refuses. A
temporary auth failure returns null and the run defers; it never mints a replacement
uid. Observed on device, in order:

```
D SupabaseAuth: no stored session; not signing in     <- startup, creates nothing
D ReactionSync: 1 reaction(s) pending from a previous run
D ReactionSync: drain scheduled (startup)
D SupabaseAuth: signed in anonymously                 <- the sync boundary, not before
D ReactionSync: delivered 1 reaction(s)
```

## Validating by hand

Instrumentation cannot kill its own process, so the process-death cases are driven
from `adb`. The debug build is debuggable, so the outbox can be seeded exactly as a
kill between "transaction committed" and "work enqueued" would leave it:

```bash
adb shell am force-stop dlinemedia.radioplayer.myata
adb shell "run-as dlinemedia.radioplayer.myata sqlite3 databases/myata_database" < seed.sql
adb shell monkey -p dlinemedia.radioplayer.myata -c android.intent.category.LAUNCHER 1
adb logcat -s ReactionSync SupabaseAuth
```

Expect `N reaction(s) pending from a previous run`, `drain scheduled (startup)`, then
`delivered N reaction(s)`, and the outbox at zero. For the offline case, put the
device in airplane mode first: the row is scheduled, the `NetworkType.CONNECTED`
constraint holds it, and it drains by itself when the network returns.

## The owner-facing aggregate

`track_reaction_totals` is service-role only — `anon` and `authenticated` are
revoked — so no instrumentation test can read it and its behaviour is checked
with owner-side SQL:

```sql
-- an all-NEUTRAL track must not appear
select count(*) from public.track_reaction_totals
 where track_key = '<the key an UNLIKE test used>';        -- expect 0

-- a track with an opinion appears normally
select track_key, likes, dislikes, last_activity
  from public.track_reaction_totals
 where track_key = '<the key a LIKE test used>';           -- expect 1 / 0

-- and no 0/0 rows exist anywhere
select count(*) from public.track_reaction_totals
 where likes = 0 and dislikes = 0;                         -- expect 0
```

The NEUTRAL rows are still inside each surviving group, on purpose:
`last_activity` counts a withdrawal as activity, because it is, and `mode()` gets
the spellings carried by the current rows, NEUTRAL ones included, which changes no
count.

Those tombstones are **current state, not history.** `reactions` holds one row per
listener per track; a NEUTRAL row says "this listener has no opinion now", not
"here is what they withdrew". The record of who changed their mind and when is
`reaction_events`, and it is the only place that record exists.

## Running the instrumentation suite

**The normal suite cannot reach the live project.** Live Supabase is opt-in, stated
per run on the command line:

```bash
./gradlew connectedDebugAndroidTest
```

```bash
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.liveSupabase=true "-Pandroid.testInstrumentationRunnerArguments.class=com.example.musicplayerapp.ReactionSyncLiveTest"
```

The first writes nothing to Supabase and reaches no Supabase endpoint. The second is
the deliberate live validation, and is the only way to run it.

### Why a per-test guard was not enough

A configured `supabase.properties` used to be the whole condition, so the ordinary
way to run the tests was also the way to write rows into production. The leak was not
in any one test: **instrumentation runs inside the app's process**, so
`MyataApplication.onCreate` fires before the first test does, and it calls
`ReactionSyncScheduler.onAppStart`. Any outbox row a previous test left behind was
delivered to the live project by *the app*, outside every `@Before`, `@After` and
skip condition. A guard in a test method is already too late for that.

So the gate is installed by `MyataTestRunner`, a custom `testInstrumentationRunner`,
in `onCreate` — which the framework calls before `Application.onCreate`. Unless the
run opted in, it replaces `ReactionSyncBackend`'s two network-facing collaborators
with an offline stand-in that reports `AuthUnavailable`, which the drain treats as
nobody's fault: the run stops, no row is penalised or discarded, and nothing leaves
the device. Every layer above the socket — the config gate, the database, the engine,
the drain verdicts and the rescheduling they trigger — still runs for real, so the
scheduling assertions keep their teeth.

Two independent things enforce it, and `LiveSupabaseIsolationTest` asserts the gate
is actually installed, in both modes, so an unregistered runner fails a test instead
of quietly writing to production.

## Test data

Two suites write fixture rows, and both use `ZZ_` identifiers so a narrow cleanup
predicate can find them:

| suite | identifier | reaches Supabase |
|---|---|---|
| `ReactionSyncLiveTest` | `ZZ_SYNC_TEST <case>` | only in opt-in mode |
| `ReactionSyncSchedulerTest` | `ZZ_SCHED_FIXTURE <nanos>` | never — local Room only |

The scheduler fixture used to be spelled `ZZ Sync Fixture`, **with spaces**, which
`artist like 'ZZ\_%'` does not match — so the rows it leaked were invisible to every
cleanup pass aimed at them. Keep new fixtures on the `ZZ_` spelling.

The validation suites mark every row they write with `ZZ_` in `artist` and `title`.
This matters because of an asymmetry that is deliberate: `reactions` rows the client
can delete, and the suite does. **`reaction_events` rows it cannot** — there is no
DELETE policy for any client role, because history a client can edit is not history.

So validation permanently adds history rows, and removing them is owner-side SQL. The
current cleanup statement lives in the pull request that added this document.

Since 0002 the suite's `reactions` rows also survive as NEUTRAL tombstones where
they used to vanish — `tidy()` still deletes them, and that is now the only thing
standing between a validation run and a handful of permanent 0/0 rows. They would
be invisible in the aggregate either way, which is the safety net.
