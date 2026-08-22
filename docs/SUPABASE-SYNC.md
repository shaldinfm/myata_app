# Reaction sync: outbox → Supabase

What this is: the delivery half. `reaction_outbox` (PR #60) is drained to the Model C
schema (PR #59) by a WorkManager job. Room stays the source of truth, the reaction
path stays offline-first, and **the network never blocks a tap**.

Google Sheets telemetry is untouched and independent. A real transition now updates
Room, emits its Sheets report, enters the outbox, and asynchronously reaches
Supabase. Neither reporting path waits on the other and neither can fail the other.

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
local and was never the cloud's copy. They go out on the next drain after an explicit
sign-in. See `docs/SUPABASE-FOUNDATION.md` for the state machine itself.

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
