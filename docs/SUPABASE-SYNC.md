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
local NEUTRAL   ->  reactions row absent (deleted)
```

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

NEUTRAL deletes the row, and **a delete that matches nothing is success** — the
desired state is "no row", and there being no row already is that state.

> **Timestamps must end in `Z`.** `Instant.toString()` renders UTC that way. An
> offset written `+00:00` contains a `+`, which decodes as a space when the value is
> used as a query-string filter; Postgres then rejects it outright. A live probe
> reproduced exactly that: `invalid input syntax for type timestamp with time zone:
> "…T08:32:26 00:00"`.

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

`AnonymousSession.ensureAuthenticatedListener` is called **once per run, and only
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

## Test data

The validation suites mark every row they write with `ZZ_` in `artist` and `title`.
This matters because of an asymmetry that is deliberate: `reactions` rows the client
can delete, and the suite does. **`reaction_events` rows it cannot** — there is no
DELETE policy for any client role, because history a client can edit is not history.

So validation permanently adds history rows, and removing them is owner-side SQL. The
current cleanup statement lives in the pull request that added this document.
