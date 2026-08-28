-- Radio Myata: permanent account deletion, and the receipt that survives it.
--
-- Forward migration from the live 0003 schema. Additive: no table is dropped, no
-- column changes meaning, no existing policy or grant is altered. One transaction -
-- a failure anywhere changes nothing. Idempotent: safe to run twice.
--
-- ## What this is for
--
-- A registered listener must be able to remove their account permanently, and
-- "permanently" has to mean two things at once: the authentication identity is gone
-- *and* the app-owned rows keyed to it are gone. Neither half alone is acceptable.
-- An auth user with orphaned reaction rows is data nobody can reach or erase; app
-- rows deleted while the auth account lives is a listener told they were deleted and
-- lied to.
--
-- So both halves happen in **one transaction**, which is the single reason this is a
-- database function rather than an Edge Function. A server endpoint holding the
-- service-role key would have to call PostgREST for the rows and the GoTrue admin
-- API for the user - two systems, no shared transaction - and a crash between them
-- produces exactly the forbidden state. Here a failure at any step rolls back all of
-- it. Verified prerequisite for taking this route: the migration owner (`postgres`)
-- holds DELETE on `auth.users`, and every foreign key pointing at `auth.users` in
-- this project is ON DELETE CASCADE. See `supabase/preflight/00_account_deletion_readonly.sql`.
--
-- **No service-role secret exists anywhere in this design.** The Android app calls
-- these two functions with the publishable key and the listener's own session, and
-- there is nothing here it could call to reach another listener's data.
--
-- ## The lost-response problem, and the receipt
--
-- Deleting `auth.users` invalidates the caller's refresh credentials immediately;
-- the access token it is holding merely stops being renewable and expires on its own
-- schedule. That produces a sequence with no client-side answer:
--
--     1. the device commits its intent to delete;
--     2. this function commits successfully;
--     3. the response is lost;
--     4. the app does not run again until the access token has expired;
--     5. the account is gone, so nothing can authenticate;
--     6. there is no session with which to ask whether step 2 happened.
--
-- The device must not guess. Absence of a session is not evidence of deletion - a
-- token can be missing because storage was cleared, because a refresh failed, or
-- because the network is down.
--
-- So the device mints a high-entropy `request_id` and stores it durably **before**
-- calling this function, and the deleting transaction writes a receipt for the pair
-- `(request_id, deleted_uid)`. Afterwards an unauthenticated caller may ask
-- `account_deletion_status(request_id, deleted_uid)` and learn exactly one bit: does
-- that pair exist. Nothing else about the account is reachable by any route.
--
-- ## Why the receipt is keyed by the pair and not by the token alone
--
-- An earlier draft keyed receipts by `request_id` only. That let a second account
-- poison the answer: an attacker who learned some device's token R could call this
-- function from their own registered session, destroying their own account, and the
-- resulting receipt would make `status(R)` report COMPLETED to a device whose account
-- was still very much alive - which would then wipe its local Collection.
--
-- The pair closes it. `deleted_uid` is taken from `auth.uid()` inside the deleting
-- transaction and is never a parameter, so writing a row that certifies X requires a
-- session as X - and anybody holding one can simply delete the account outright. The
-- attacker's call writes `(R, their_uid)`, which is a different row and answers
-- nothing about `(R, X)`.
--
-- Neither half leaks on its own: a uid without its token is useless against 122 bits
-- of entropy, and a token without its uid is equally useless.
--
-- ## Multiple receipts per uid are normal, not a defect
--
-- Two devices can each start a deletion of the same account. One wins the advisory
-- lock and gets DELETED; the other finds the row already gone and gets
-- ALREADY_DELETED. **Both results are definitive, and both devices must be able to
-- prove theirs later**, so both write their own pair. An earlier draft capped
-- receipts at one per uid to bound abuse and thereby stranded the second device in
-- an unresolvable state forever. There is no cap here, deliberately - see the note
-- above the insert.
--
-- ## What this deliberately does NOT do
--
--  * **No soft delete, no grace period, no tombstone.** Deletion is immediate and
--    the row is gone. A "deleted" flag that keeps the account alive is a lie with a
--    schema.
--  * **No client acknowledgement route.** An RPC that removes a receipt is a
--    destructive capability handed to anybody holding the pair, and using it would
--    let them erase the only completion proof before the original device reads it -
--    stranding exactly the device this whole mechanism exists to rescue. Receipts
--    are permanent in v1. See "Retention" below.
--  * **No anonymous deletion.** Account deletion is a registered-account feature;
--    an anonymous caller is refused rather than quietly served.
--  * **No change to reaction sync semantics.** No policy, index, trigger, sequence
--    or function from 0001-0003 is touched. `apply_reaction_event_batch` is not read
--    and not modified.
--  * **No Storage handling.** See the invariant below.
--
-- ## Verified Storage invariant (production, checked 2026-08-29)
--
-- This project has **zero Storage buckets and zero Storage objects**, and therefore
-- no user-owned `storage.objects` rows. That is what makes the single-transaction
-- design available at all, and it is a condition on future work rather than a
-- coincidence:
--
--     `storage.objects` rows are metadata. Deleting them with SQL orphans the
--     physical files in the storage backend, so owned objects must be removed
--     through the Storage API - which cannot run inside this transaction. The first
--     user-owned Storage object in this project therefore forces the Edge Function
--     design and gives up the atomicity that is the reason for choosing this one.
--
-- The planned avatar picker does not introduce any: its design is frozen as sixteen
-- predefined avatars bundled as drawables with no photo upload, so a chosen avatar
-- is an index in `user_metadata` and is deleted with the auth row.
--
-- ## Retention
--
-- Receipts are permanent in v1: no expiry, no sweeper, no pruning job, and no
-- documented prune interval. Elapsed time is not evidence that no device is still
-- waiting to read a pair - a device offline for longer than any window we could pick
-- still needs its receipt - so no time-based rule is safe without independent proof
-- of resolution, which v1 deliberately does not collect.
--
-- A row is three fixed-width values; a million deletions is under 100 MB. If
-- retention ever becomes operationally significant it is an owner decision taken
-- then, under that same constraint. See docs/ACCOUNT-DELETION.md.
--
-- Apply with:  supabase db push        (or paste into the SQL editor)

begin;

-- ------------------------------------------- account_deletion_receipts --
--
-- Proof that a deletion performed under one opaque token completed, and nothing
-- else. Three columns, no email, no name, no counts, no request metadata.
--
-- The primary key is the pair, which is what makes a receipt certify one account's
-- deletion rather than one token's use. Multiple rows per `deleted_uid` are expected
-- and correct: one per device that received a definitive answer.
--
-- There is deliberately **no foreign key to `auth.users`**. The referenced row is
-- gone by the time this one is written - that is the whole point - so a foreign key
-- here is a constraint that could never hold.

create table if not exists public.account_deletion_receipts (
    request_id   uuid not null,
    deleted_uid  uuid not null,
    completed_at timestamptz not null default now(),

    primary key (request_id, deleted_uid)
);

comment on table public.account_deletion_receipts is
    'Server-owned. Written only by delete_my_account, read only by '
    'account_deletion_status. No client policy of any kind, and no route by which '
    'any client can delete a row: a completion proof a client can erase would strand '
    'the device waiting to read it.';

comment on column public.account_deletion_receipts.deleted_uid is
    'Always auth.uid() of the deleting session. Never a parameter - that is what '
    'stops one account writing a receipt that answers for another.';

comment on column public.account_deletion_receipts.completed_at is
    'Owner inspection only; never returned to a client. On an ALREADY_DELETED '
    'receipt this is when that caller learned the account was gone, not when it was '
    'deleted, and nothing depends on the difference.';

alter table public.account_deletion_receipts enable row level security;

-- RLS on with no policies at all, so no non-owner role can reach a row even if a
-- grant is added later by accident. The revokes say the same at the table level -
-- and are required, because Supabase's default privileges would otherwise grant this
-- new table to anon and authenticated automatically.
revoke all on public.account_deletion_receipts from public;
revoke all on public.account_deletion_receipts from anon;
revoke all on public.account_deletion_receipts from authenticated;

-- ------------------------------------------------------ delete_my_account --
--
-- One account, one transaction, one receipt.
--
-- ## Identity
--
-- `auth.uid()` only. There is no listener_id or uid parameter anywhere in the
-- signature, so a caller has nothing to forge and no way to name a victim. Every
-- read, every delete and the receipt all derive from it. This is the same rule
-- `apply_reaction_event_batch` follows, for the same reason.
--
-- ## Shape
--
-- SECURITY DEFINER because `auth.users` belongs to `supabase_auth_admin` and no
-- client role may be given DELETE on it. The shape is the minimal safe one for a
-- definer function: no dynamic SQL, no user-supplied identifier, `search_path`
-- pinned empty, every object fully qualified.
--
-- ## Concurrency
--
-- A transaction-scoped advisory lock on the uid serialises every call for one
-- account. Two devices deleting at once therefore run strictly in order: the first
-- commits and returns DELETED, the second blocks, then observes the absent
-- `auth.users` row rather than racing it, and returns ALREADY_DELETED. Nothing
-- depends on statement timing or on ON CONFLICT wait semantics.

create or replace function public.delete_my_account(p_request_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_uid          uuid;
    v_reactions    bigint;
    v_events       bigint;
    v_applications bigint;
    v_deleted      int;
begin
    ---------------------------------------------------------------- identity
    v_uid := auth.uid();
    if v_uid is null then
        raise exception 'not authenticated' using errcode = '28000';
    end if;

    -- Registered accounts only. An anonymous listener has no account screen, no way
    -- to reach this, and no credentials to recover one with - so serving them here
    -- would delete an identity the product never offered to delete. The claim is
    -- absent on some tokens rather than false, hence the coalesce.
    -- `coalesce` is deliberately unqualified: it is a parser construct rather than a
    -- pg_catalog function, and schema-qualifying it does not resolve. Same as 0003.
    if coalesce(auth.jwt() ->> 'is_anonymous', 'false') = 'true' then
        raise exception 'account deletion is for registered accounts'
            using errcode = '42501';
    end if;

    -- The token is what makes the outcome provable after the session is gone. A call
    -- without one could still delete correctly and would leave the caller unable to
    -- ever confirm it, so it is refused before anything is touched.
    if p_request_id is null then
        raise exception 'request_id is required' using errcode = '22023';
    end if;

    --------------------------------------------------------------- serialise
    perform pg_catalog.pg_advisory_xact_lock(
        pg_catalog.hashtextextended('account-deletion:' || v_uid::text, 0));

    ------------------------------------------------------- already deleted --
    --
    -- The other device won. This one still received a definitive answer and is still
    -- entitled to prove it, so it writes its own pair before returning.
    if not exists (select 1 from auth.users u where u.id = v_uid) then
        insert into public.account_deletion_receipts (request_id, deleted_uid)
        values (p_request_id, v_uid)
            on conflict (request_id, deleted_uid) do nothing;

        return pg_catalog.jsonb_build_object('outcome', 'ALREADY_DELETED');
    end if;

    ------------------------------------------------------------- the counts --
    --
    -- Read before the deletes, returned to the authenticated caller only. This is
    -- the listener's own data being described to the listener; none of it reaches
    -- the unauthenticated status route.
    select pg_catalog.count(*) into v_reactions
      from public.reactions r where r.listener_id = v_uid;

    select pg_catalog.count(*) into v_events
      from public.reaction_events e where e.listener_id = v_uid;

    select pg_catalog.count(*) into v_applications
      from public.reaction_event_applications a where a.listener_id = v_uid;

    ------------------------------------------------------------ the deletes --
    --
    -- Every one of these is redundant against the ON DELETE CASCADE chain that ends
    -- at `auth.users`, and every one of them is kept anyway. The function then states
    -- what it removes instead of depending on a cascade definition surviving a future
    -- migration, and the order is the foreign-key order so it reads as the graph it
    -- is walking:
    --
    --     reaction_event_applications -> reaction_events -> auth.users
    --     reactions                                      -> auth.users
    --
    -- RULE FOR FUTURE WORK: every new table keyed by a listener uid is added here,
    -- in this block, at the same time as it is created.
    delete from public.reaction_event_applications a where a.listener_id = v_uid;
    delete from public.reaction_events e where e.listener_id = v_uid;
    delete from public.reactions r where r.listener_id = v_uid;

    -- The half no client key can perform, and the reason this function exists.
    delete from auth.users u where u.id = v_uid;

    get diagnostics v_deleted = row_count;
    if v_deleted <> 1 then
        -- Unreachable: existence was checked above, under the lock that serialises
        -- every other caller for this uid. If it ever fires, the transaction rolls
        -- back and nothing has been deleted - which is the required failure mode.
        raise exception 'auth user vanished mid-transaction' using errcode = 'XX000';
    end if;

    ------------------------------------------------------------ the receipt --
    --
    -- Written last so the reading is obvious, though the transaction makes the order
    -- immaterial: any failure above rolls this back with everything else, so a
    -- receipt cannot exist for a deletion that did not happen.
    --
    -- No cap, and no per-uid uniqueness. A cap would bound abuse by a holder of a
    -- still-valid token for an account they have already destroyed - noise, since the
    -- pair binding means they cannot certify anybody else - but any ceiling can be
    -- exhausted deliberately, at which point a legitimate second device is denied its
    -- receipt and stranded permanently. Bounded normal growth beats an unrecoverable
    -- device.
    insert into public.account_deletion_receipts (request_id, deleted_uid)
    values (p_request_id, v_uid)
        on conflict (request_id, deleted_uid) do nothing;

    return pg_catalog.jsonb_build_object(
        'outcome',      'DELETED',
        'reactions',    v_reactions,
        'events',       v_events,
        'applications', v_applications
    );
end;
$$;

-- CREATE FUNCTION grants EXECUTE to PUBLIC by default, and Supabase's default
-- privileges add anon and authenticated on top. Both revokes are load-bearing:
-- deleting an account requires a session, so anon has no business here.
revoke all on function public.delete_my_account(uuid) from public;
revoke all on function public.delete_my_account(uuid) from anon;
grant execute on function public.delete_my_account(uuid) to authenticated;

-- --------------------------------------------------- account_deletion_status --
--
-- The one bit a device may learn after its session is gone: does this exact pair
-- have a receipt.
--
-- ## Why a function and not a policy on the table
--
-- Row-level security cannot force a client to supply a WHERE clause. Any SELECT
-- grant on `account_deletion_receipts`, however scoped, would permit dumping it. A
-- function taking exactly one pair and returning one word has no enumeration surface
-- and no way to ask a broader question.
--
-- ## What it returns, and everything it does not
--
-- COMPLETED or UNKNOWN. No uid echo, no `completed_at`, no counts, no email, no
-- indication of whether either half of the pair exists on its own. A missing pair
-- and a malformed one are the same answer, so the shape of a wrong guess tells a
-- caller nothing.
--
-- `p_deleted_uid` is a lookup key, never an authorisation claim: no branch anywhere
-- in this file derives a privilege from a caller-supplied uid. Guessing it is
-- useless without the 122-bit token beside it, which is why this needs no rate
-- limiting.
--
-- STABLE, and it writes nothing. There is deliberately no companion function that
-- removes a receipt - see the migration header.

create or replace function public.account_deletion_status(
    p_request_id  uuid,
    p_deleted_uid uuid
) returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
begin
    if p_request_id is null or p_deleted_uid is null then
        return pg_catalog.jsonb_build_object('outcome', 'UNKNOWN');
    end if;

    if exists (
        select 1
          from public.account_deletion_receipts r
         where r.request_id  = p_request_id
           and r.deleted_uid = p_deleted_uid
    ) then
        return pg_catalog.jsonb_build_object('outcome', 'COMPLETED');
    end if;

    return pg_catalog.jsonb_build_object('outcome', 'UNKNOWN');
end;
$$;

-- anon is granted deliberately, and it is the whole point: the device asking this
-- has no session left to ask with. See the lost-response sequence in the header.
revoke all on function public.account_deletion_status(uuid, uuid) from public;
grant execute on function public.account_deletion_status(uuid, uuid) to anon;
grant execute on function public.account_deletion_status(uuid, uuid) to authenticated;

commit;

-- ---------------------------------------------------------------- checks --
--
-- Read-only. Run after the transaction commits; none of it changes anything.
-- The full post-apply verification set is in docs/ACCOUNT-DELETION.md.
--
--   -- 1. the table exists, is behind RLS, and has no policies
--   select relrowsecurity from pg_class
--    where oid = 'public.account_deletion_receipts'::regclass;
--   select count(*) as should_be_zero from pg_policies
--    where schemaname = 'public' and tablename = 'account_deletion_receipts';
--
--   -- 2. no client role can reach the table directly
--   select grantee, privilege_type from information_schema.role_table_grants
--    where table_schema = 'public' and table_name = 'account_deletion_receipts';
--
--   -- 3. both functions are SECURITY DEFINER with an empty search_path
--   select p.proname, p.prosecdef, p.proconfig
--     from pg_proc p join pg_namespace n on n.oid = p.pronamespace
--    where n.nspname = 'public'
--      and p.proname in ('delete_my_account', 'account_deletion_status');
--
--   -- 4. the grants are exactly as intended
--   select p.proname, pg_catalog.pg_get_userbyid(a.grantee) as role
--     from pg_proc p
--     join pg_namespace n on n.oid = p.pronamespace
--     cross join lateral aclexplode(p.proacl) a
--    where n.nspname = 'public'
--      and p.proname in ('delete_my_account', 'account_deletion_status')
--      and a.privilege_type = 'EXECUTE'
--    order by 1, 2;
--
--   -- 5. nothing from 0001-0003 moved
--   select count(*) from public.reactions;
--   select count(*) from public.reaction_events;
--   select count(*) from public.reaction_event_applications;
--   select count(*) from auth.users;
