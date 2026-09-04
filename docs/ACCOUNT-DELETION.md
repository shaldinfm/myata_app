# Account deletion — the contract

Permanent deletion of a **registered** Radio Myata account: the authentication
identity and every app-owned row keyed to it, removed together, provably.

This document is the frozen design, and the whole of it now ships. The server half is
[`supabase/migrations/0004_account_deletion.sql`](../supabase/migrations/0004_account_deletion.sql),
applied to production; the client boundary, the sync gates, the orchestrator, the
recovery, the local cleanup and the destructive UI are implemented and **validated
against production** — see [Implementation status](#implementation-status) and
[Live validation](#live-validation--recorded-result-2026-09-04).

Related: [SUPABASE-FOUNDATION.md](SUPABASE-FOUNDATION.md) (schema, RLS, grants),
[SUPABASE-SYNC.md](SUPABASE-SYNC.md) (drain, pull, the lease).

## Deletion is not logout

Two operations that must never converge.

| | Logout | Delete account |
|---|---|---|
| auth identity | kept | **permanently removed** |
| cloud reactions and history | kept | **permanently removed** |
| local Collection | **kept, untouched** | **wiped** |
| local identity state | `SIGNED_OUT(uid)`, sync paused | `NONE`, a clean guest |
| way back | sign in | none |

The local Collection is wiped on deletion and preserved on logout, and that
asymmetry is deliberate. If deletion left the rows on the device, the next account
this install registers would adopt them through
[`IdentityHandoff`](../app/src/main/java/com/example/musicplayerapp/data/supabase/IdentityHandoff.kt)
and push data the listener was told had been deleted back into the cloud. The
confirmation copy states this in as many words; the listener is never surprised by it.

## Why one database transaction

Deletion must never leave an auth user with orphaned app data, and must never delete
app data while the auth account lives. The first is data nobody can reach or erase;
the second is a listener told they were deleted and lied to.

`delete_my_account` removes the app rows **and** the `auth.users` row in one
transaction, so neither state is representable. The alternative — an Edge Function
holding the service-role key, calling PostgREST for the rows and the GoTrue admin API
for the user — is two systems with no shared transaction, and a crash between them
produces exactly the forbidden state. That is the whole reason for this shape.

It is available only because production allows it, which was verified rather than
assumed ([`supabase/preflight/00_account_deletion_readonly.sql`](../supabase/preflight/00_account_deletion_readonly.sql),
2026-08-29): the migration owner `postgres` holds DELETE on `auth.users`, and every
foreign key pointing at `auth.users` is `ON DELETE CASCADE`.

**No service-role secret exists anywhere in this design.** The app calls two
functions with the publishable key and the listener's own session.

## What is deleted

| Row set | Removed by |
|---|---|
| `auth.users` row, and `auth.identities` / `sessions` / `refresh_tokens` | GoTrue's own cascades |
| `public.reactions where listener_id = uid` | explicit delete, and the FK cascade |
| `public.reaction_events where listener_id = uid` | explicit delete, and the FK cascade |
| `public.reaction_event_applications where listener_id = uid` | explicit delete, and the cascade from `reaction_events` |

The explicit deletes are redundant against the cascades and are kept so the function
states what it removes rather than depending on a cascade definition surviving a
future migration.

> **Rule for future work.** Every new table keyed by a listener uid is added to the
> delete block in `delete_my_account`, in the same change that creates it.

`reaction_event_applications` is the reason deletion cannot be a loop of ordinary
table deletes issued by Android: it is server-owned, with no client policy of any
kind. A client has no route to those rows and is not given one.

## The lost-response problem

Deleting `auth.users` invalidates the refresh credentials immediately; the access
token the caller holds merely stops being renewable and expires on its own schedule.
That produces a sequence with no client-side answer:

1. the device commits its intent to delete;
2. `delete_my_account` commits successfully;
3. the response is lost;
4. the app does not run again until the access token has expired;
5. the account is gone, so nothing can authenticate;
6. **there is no session with which to ask whether step 2 happened.**

The device must not guess. Absence of a session is not evidence of deletion — a token
can be missing because storage was cleared, because a refresh failed, or because the
network is down. Neither is a failed sign-in, nor elapsed time.

### The receipt

The device mints a high-entropy `request_id` (UUIDv4, 122 bits) and stores it durably
**before** calling, and the deleting transaction writes a receipt for the pair
`(request_id, deleted_uid)`. Afterwards an unauthenticated caller may ask
`account_deletion_status(request_id, deleted_uid)` and learn exactly one bit.

**Keyed by the pair, never the token alone.** An earlier draft keyed receipts by
`request_id` only, which let a second account poison the answer: an attacker who
learned a device's token could call from their own registered session, destroying
their own account, and the receipt would report COMPLETED to a device whose account
was still alive — which would then wipe its local Collection. The pair closes it,
because `deleted_uid` comes from `auth.uid()` inside the deleting transaction and is
never a parameter. Writing a row that certifies X requires a session as X, and
anybody holding one can simply delete the account outright.

Neither half leaks alone: a uid without its token is useless against 122 bits, and a
token without its uid is equally useless. No rate limiting is needed and none is
proposed.

**Multiple receipts per uid are normal.** Two devices can each start a deletion of the
same account; one wins the advisory lock and gets `DELETED`, the other finds the row
gone and gets `ALREADY_DELETED`. Both results are definitive and both devices must be
able to prove theirs later, so both write their own pair. An earlier draft capped
receipts at one per uid and thereby stranded the second device forever. There is no
cap, deliberately — any ceiling can be exhausted on purpose, turning noise into a
targeted denial of recovery.

**There is no client acknowledgement route.** An RPC that removes a receipt is a
destructive capability handed to anybody holding the pair, and using it would let them
erase the only completion proof before the original device reads it — stranding
exactly the device the mechanism exists to rescue.

## Retention

Receipts are **permanent in v1**: no expiry, no sweeper, no pruning job, no
acknowledgement, and no documented prune interval.

Elapsed time is not evidence that no device is still waiting to read a pair. A device
offline longer than any window we could pick still needs its receipt, so no
time-based rule is safe without independent proof of resolution — which v1
deliberately does not collect.

A row is three fixed-width values; a million deletions is under 100 MB. This will not
become operationally significant for this project. If it ever does, it is an owner
decision taken then, under that same constraint: **no pruning rule may remove a
receipt any device might still be waiting to read.**

Receipt retention is not part of the device's lifecycle. Nothing on the client reads,
writes, or waits on a receipt once deletion is confirmed; normal guest operation would
resume identically if the receipts table were unreachable.

Owner-side observability, if a single account ever accumulates absurd receipt counts
(possible only from a holder of a still-valid token for an account they have already
destroyed — noise, since the pair binding means they cannot certify anybody else):

```sql
select deleted_uid, count(*) as receipts, min(completed_at), max(completed_at)
  from public.account_deletion_receipts
 group by deleted_uid having count(*) > 8 order by 2 desc;
```

## Verified Storage invariant

**Production has zero Storage buckets and zero Storage objects**, and therefore no
user-owned `storage.objects` rows (preflight, 2026-08-29). The app cannot create one
either: `app/build.gradle` pulls only `auth-kt` and `postgrest-kt`, and
`SupabaseModule` installs only `Auth` and `Postgrest` — there is no Storage plugin in
the APK.

The planned avatar picker does not change this. Its design is frozen as sixteen
predefined avatars bundled as drawables with no photo upload, so a chosen avatar is an
index in `user_metadata` and is deleted with the auth row.

> **This invariant is load-bearing, not incidental.** `storage.objects` rows are
> metadata; deleting them with SQL orphans the physical files in the storage backend,
> so owned objects must be removed through the Storage API — which cannot run inside
> this transaction. **The first user-owned Storage object in this project forces the
> Edge Function design and gives up the atomicity that is the reason for choosing this
> one.** Introducing one is a decision about account deletion, not only about avatars.

## Client state machine

Frozen here; **implemented across G-A8b and G-A8c.** One durable marker in the
`supabase_identity` preferences file, written with `commit()`.

| Durable marker | + identity state | Meaning | Sync |
|---|---|---|---|
| — | `Registered(X)` | normal | live |
| `REQUESTED(R, X)` | `Registered(X)` | intent committed, outcome **not known** | **dead** |
| `CONFIRMED(R, X)` | `Registered(X)` | server confirmed, local cleanup owed | **dead** |
| — | `None` | cleanup complete, clean guest | live |

`REQUESTED` and "outcome unknown" are the same durable stage on purpose. Once the RPC
is dispatched the device cannot distinguish "never left" from "committed, response
lost", so persisting them apart would persist a guess. "Unknown" is derived at
runtime — `REQUESTED` plus an inconclusive attempt or no usable session — and is never
used as evidence of anything.

### Exclusion boundary

No previously-started drain or pull may overlap the deletion transaction, and none may
start after `REQUESTED` is committed.

```
SyncLease.withExclusive {                            // 1 in-flight drains/pulls have finished
    verify Registered(X) and session uid == X        // 2 the read inside the section decides
    verify no handoff record on disk                 // 3 a PREPARED record no lock can see
    IdentityStore.markDeletionRequested(R, X)        // 4 durable gate opens here
    result = api.deleteAccount(R)                    // 5 network, still under the lease
    when (result) {
        Deleted, AlreadyDeleted -> {
            IdentityStore.markDeletionConfirmed(R, X)    // 6 before anything local is touched
            api.signOutLocal()                           // 7 NETWORK - outside the gate
            //    false here -> Deferred: nothing local is touched at all
            ReactionWriteGate.withDeliveryStep {         // 8 one section, no network inside
                purge reaction_outbox; clear track_reaction
                LastSyncStore.forget(X)
                IdentityStore.forgetDeletedAccount()     // 9 -> None, marker cleared. LAST
            }
        }
        Inconclusive      -> { /* REQUESTED stands; nothing local changes */ }
        DefinitiveRefusal -> { IdentityStore.clearDeletionMarker() }
    }
}
```

The lease stops what is running; the durable marker stops what would start. Only
`SyncLease` is held across a network call — `ReactionWriteGate` is not, so a listener
tapping Like never waits on one. Holding the lease across a request follows
`IdentityHandoff.finish`, which does the same.

**`signOutLocal()` is a network call.** `signOut(SignOutScope.LOCAL)` is local in
*scope* — it invalidates this device's session and nobody else's — but supabase-kt
issues an HTTP `POST /logout?scope=local` whenever a session exists, skipping it only
when there is none. So it runs **first, and outside the gate**: holding the gate across
it would make a Like wait on a round trip, up to the client's read timeout. A failure
there returns `Deferred` before anything local is touched — no purge, no forgotten
timestamps, no identity change — and a later start retries the whole routine.

**Everything after it is one gate section, and that section is the cutover.** Purge,
`LastSyncStore.forget(X)` and `forgetDeletedAccount()` are held together, with no
network inside. `SyncLease` does not serialise ordinary reaction writes, so without
this a tap landing after the purge and before the identity was cleared would commit a
`track_reaction` and an outbox row that survived the deletion, belonging to an account
that no longer existed. With one section there is no such instant: a tap either lands
before the gate is taken and the purge removes it, or it waits and lands afterwards, on
an install that is already a guest, where it is an ordinary new guest-side action.

**Room before identity, inside that section.** `forgetDeletedAccount()` writes `None`,
the one state from which a new anonymous uid may be minted, and reversing the two would
open a window in which a drain could mint an identity and upload the dead account's
pending events. The lease closes that window against sync; the gate closes it against
taps.

`deletionInFlight` is consulted by `ListenerSession.identity`, `ReactionSyncEngine`,
`ReactionPull` / `ReactionPullTrigger`, `ReactionSyncScheduler`, `IdentityHandoff` and
`ProfileRoute`.

### Recovery

Resolved by `IdentityReconciler` before any identity repair, taking the lease itself —
deletion resolution and handoff recovery never nest, because the lease is not
reentrant.

| Stage | Resolution |
|---|---|
| `CONFIRMED` | finish local cleanup. **Never ask the server again.** |
| `REQUESTED`, usable session for X | re-call `delete_my_account(R)` with the **same** R |
| `REQUESTED`, no session | `account_deletion_status(R, X)` on `anon`. `COMPLETED` → cleanup; `UNKNOWN` → stay sync-dead, retry later |

A successful sign-in as X proves the account exists and therefore that deletion did not
complete: clear the marker, restore normal operation, report. That is an *available*
resolution, never a required one — the primary path needs no session at all.
**Not implemented; deferred — see [Implementation status](#implementation-status).**

**Accepted limit.** An uninstall while `REQUESTED` takes the marker and the token with
it. If the deletion had not committed, the account survives with nothing pointing at
it. Nothing client-side can close this; the receipts table is the owner's audit trail.

## Post-apply verification

Read-only. Run after `0004` is applied.

```sql
-- 1. the table exists, is behind RLS, and has no policies
select relrowsecurity as rls_enabled, relforcerowsecurity as rls_forced
  from pg_class where oid = 'public.account_deletion_receipts'::regclass;

select count(*) as policies_should_be_zero
  from pg_policies
 where schemaname = 'public' and tablename = 'account_deletion_receipts';

-- 2. no client role can reach the table directly (expect zero rows)
select grantee, privilege_type
  from information_schema.role_table_grants
 where table_schema = 'public' and table_name = 'account_deletion_receipts'
   and grantee in ('anon', 'authenticated', 'PUBLIC');

-- 3. the primary key is the pair, in that order
select con.conname,
       pg_get_constraintdef(con.oid) as definition
  from pg_constraint con
 where con.conrelid = 'public.account_deletion_receipts'::regclass
   and con.contype = 'p';

-- 4. no foreign key on the receipts table at all (expect zero rows)
select con.conname
  from pg_constraint con
 where con.conrelid = 'public.account_deletion_receipts'::regclass
   and con.contype = 'f';

-- 5. both functions are SECURITY DEFINER with an empty search_path
select p.proname, p.prosecdef as security_definer, p.proconfig
  from pg_proc p join pg_namespace n on n.oid = p.pronamespace
 where n.nspname = 'public'
   and p.proname in ('delete_my_account', 'account_deletion_status');

-- 6. the EXECUTE grants are exactly:
--      delete_my_account         -> authenticated
--      account_deletion_status   -> anon, authenticated
select p.proname, pg_get_userbyid(a.grantee) as role
  from pg_proc p
  join pg_namespace n on n.oid = p.pronamespace
  cross join lateral aclexplode(p.proacl) a
 where n.nspname = 'public'
   and p.proname in ('delete_my_account', 'account_deletion_status')
   and a.privilege_type = 'EXECUTE'
 order by 1, 2;

-- 7. delete_my_account takes no uid parameter (expect exactly `p_request_id uuid`)
select p.proname, pg_get_function_identity_arguments(p.oid) as args
  from pg_proc p join pg_namespace n on n.oid = p.pronamespace
 where n.nspname = 'public' and p.proname = 'delete_my_account';

-- 8. the status route is harmless on a pair that does not exist
select public.account_deletion_status(
    '00000000-0000-0000-0000-000000000000'::uuid,
    '00000000-0000-0000-0000-000000000000'::uuid);   -- expect {"outcome":"UNKNOWN"}

-- 9. nothing from 0001-0003 moved. Compare against the preflight baseline.
select (select count(*) from auth.users)                          as auth_users,
       (select count(*) from public.reactions)                    as reactions,
       (select count(*) from public.reaction_events)              as reaction_events,
       (select count(*) from public.reaction_event_applications)  as applications,
       (select count(*) from public.account_deletion_receipts)    as receipts;
```

Query 8 is the only one that calls a new function, and it reads. **Do not validate
`delete_my_account` by deleting a production account.** End-to-end validation was done
once, under G-A8e, against a disposable fixture account created for it — which only
this migration made cleanable. See
[Live validation](#live-validation--recorded-result-2026-09-04).

## Implementation status

| | |
|---|---|
| **G-A8a** server: receipts table, `delete_my_account`, `account_deletion_status` | **CLOSED** — applied to production, verified |
| **G-A8b** client boundary and sync gates | **CLOSED** |
| **G-A8c** orchestrator, reconciler recovery, local cleanup | **CLOSED** |
| **G-A8d** the destructive row, two confirmations, progress and failure states | **CLOSED** |
| **G-A8e** live validation against a production fixture account | **CLOSED** — 2026-09-04, PASS |

`AccountDeletion.request(context)` is reached from the profile screen's `Удалить
аккаунт` row, behind the two confirmations G-A8d added.

`delete_my_account` has been executed against production exactly once, deliberately,
against the G-A8e fixture. No other account has ever been deleted by this app.

## Live validation — recorded result (2026-09-04)

One end-to-end run against production, on a clean debug install (`versionCode 202611`,
API 36 emulator) at `main` `ce2fafa`. **One** `delete_my_account` invocation.

The fixture was a disposable registered account created **through the product's own
`Создать аккаунт` screen** — not by dashboard invite and not by SQL — at
`zz-ga8e-…@example.com`, so the shipped registration path was the one exercised and no
mail could leave the project. Registration produced a live session immediately and the
install committed `REGISTERED(X)`. Before deletion it held **three LIKEs on three
distinct tracks**, drained to the cloud, with a read-only SQL gate confirming exactly
`3 / 3 / 3` rows in `reactions`, `reaction_events` and
`reaction_event_applications`, zero receipts for that uid, zero Storage buckets and
objects, and the three foreign keys into `auth.users` and `reaction_events` all
`ON DELETE CASCADE`.

What the destructive step produced, in order:

| | |
|---|---|
| UI | the row disabled with its inline spinner; both confirmations shown, the second naming the fixture address |
| server outcome | **`DELETED`** |
| local cleanup | completed — `cleared 0 pending event(s), 3 reaction(s)`, then `forgetDeletedAccount` |
| resulting screen | the **ordinary** guest profile, not either pending presentation; Back does not return to the account card |

Read-only SQL afterwards, scoped to the fixture uid:

| Check | Result |
|---|---|
| `auth.users` row, by uid **and** by address | gone |
| `public.reactions` for the uid | 0 |
| `public.reaction_events` for the uid | 0 |
| `public.reaction_event_applications` for the uid | 0 |
| accounts matching the fixture convention | 0 remaining |
| `account_deletion_receipts` for the uid | **exactly one**, durable |

Local state afterwards: identity `NONE`, no deletion marker, no handoff marker, zero
reaction rows, empty outbox, `LastSyncStore` forgotten.

### The recovery route, proven without a session

The receipt was then read back over the **`anon` transport a stranded device would
use** — `POST /rest/v1/rpc/account_deletion_status` with an `apikey` header and
**no `Authorization` header**, no access token, and no service-role key anywhere in
the run (none exists on the build machine, and the app ships only the publishable
key):

| Call | Answer |
|---|---|
| correct pair `(R, X)` | `COMPLETED` |
| wrong `request_id`, correct uid | `UNKNOWN` |
| correct `request_id`, wrong uid | `UNKNOWN` |

All three returned HTTP 200, so the two negatives are the function's own answers
rather than transport failures. That is the pair binding demonstrated in production:
neither half of the pair answers anything on its own.

The `(request_id, deleted_uid)` pair itself is kept with the run evidence and
deliberately not recorded in this repository.

## Deferred: resolving a deletion by signing in again

**Explicitly outside the G-A8a–e closure, and still open.**

The [Recovery](#recovery) section describes an *optional* resolution — a successful
sign-in as X proving the account still exists, which retracts an unresolved deletion.
**That path is not implemented**, it was not exercised by the live validation, and it
is deferred rather than dropped.

The reason is a collision with a different frozen contract: authentication from
`IdentityState.Registered` is not a defined transition in the G-A4 routing rules, so an
explicit sign-in while a deletion is unresolved is refused locally before any request
is made. Making it a defined transition changes the auth contract, not this one, and
would risk the generic router adopting an unrelated account.

Nothing depends on it. The primary resolution — the session-less status route — needs
no credentials at all, which is the whole reason the receipt exists.
