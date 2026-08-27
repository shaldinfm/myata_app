-- Radio Myata: server-assigned revisions, liked_at, and the atomic apply RPC.
--
-- Forward migration from the live 0002 schema. Additive and non-destructive: no
-- table is dropped, no row is deleted, no existing column changes meaning. One
-- transaction - a failure anywhere changes nothing. Idempotent: safe to run twice.
--
-- ## What `rev` is, and what it is NOT
--
-- `rev` is **server-assigned change ordering**: a fresh value from a global
-- monotonic sequence on every insert and every update. It is the pull cursor, the
-- pull's page ordering, and the per-row watermark a client stores to avoid applying
-- a stale page.
--
-- It is **not** a compare-and-set token and must not become one. Push identity and
-- exactly-once are established by `event_id` membership in
-- `reaction_event_applications`, decided inside `apply_reaction_event_batch`. A
-- genuinely unapplied local pending action wins when it reaches that function; it
-- does not test a base revision first. An earlier draft of this design used CAS and
-- it was removed - reintroducing it would add a second, conflicting notion of push
-- identity on top of the application log.
--
-- What `rev` replaces is the *device wall clock* as the cross-device ordering
-- authority. A phone running ten minutes fast could otherwise beat a genuinely
-- later action on a correct clock, indefinitely. That is a correctness failure, not
-- a precision one.
--
-- ## What this deliberately does NOT do
--
--  * **`updated_at` is not touched.** Not its writer, not its meaning, not its
--    value. Installed pre-G-A7 clients guard their pushes with
--    `remote.updated_at <= their own device clock`, and on zero rows matched fall
--    through to an ignore-duplicates insert that does nothing when the row exists.
--    Overwriting the column with `now()` would make a device whose clock runs behind
--    real time fail that guard, write nothing, and - because the remote value keeps
--    advancing while the device does not - fail every later write, silently and
--    permanently. The new RPC therefore keeps writing the client's own `updated_at`
--    for as long as old clients exist.
--  * **No policy is removed.** Old clients write `reactions` directly and must keep
--    working through the rollout. RPC-only writes are a separate, later change.
--  * **No application-log backfill.** See that table's header.
--  * **No change to `reaction_events`' columns, policies, or append-only status.**
--    It gains one UNIQUE constraint, needed only as a foreign-key target.
--  * **No length bound on `stream`, anywhere.** Legacy Room rows copy
--    `favorites.stream` verbatim from older APKs and were never bounded locally, and
--    `public.reactions.stream` has never carried a CHECK. No production query could
--    prove a per-field cap safe for every installed device, so introducing one would
--    risk turning a valid listener's row into a permanent failure. Stream is covered
--    by the RPC's total input bound, which is a request-size boundary rather than a
--    semantic schema limit.
--
-- ## Client invariant recorded here for G-A7b (not implemented by this file)
--
-- After an RPC response, local settlement must itself be race-safe. Under
-- ReactionWriteGate and one Room transaction the client must: delete the settled
-- ATOMIC_RPC outbox rows; check whether any other pending row for that track
-- remains; adopt the returned state and rev only if none does; and never overwrite
-- the local state of a still-pending mutation. The gate is released immediately
-- afterwards and never held across the network. Without this, an RPC that returned
-- older remote state could overwrite a tap that landed while it was in flight.
--
-- Apply with:  supabase db push        (or paste into the SQL editor)

begin;

-- ------------------------------------------------------------- sequence --
--
-- Global rather than per-listener. Pull always filters by `listener_id` and orders
-- by `rev`, so gaps between one listener's values are irrelevant - the value is
-- only ever compared, never counted or interpreted.
--
-- The revokes are not decoration. Supabase ships default privileges that grant new
-- objects in `public` to `anon` and `authenticated`; without these, creating the
-- sequence would hand every client role USAGE/SELECT/UPDATE on it. Nothing outside
-- the trigger may touch this.

create sequence if not exists public.reactions_rev_seq as bigint;

revoke all on sequence public.reactions_rev_seq from public;
revoke all on sequence public.reactions_rev_seq from anon;
revoke all on sequence public.reactions_rev_seq from authenticated;

-- ------------------------------------------------------- reactions: columns --

alter table public.reactions
    add column if not exists liked_at timestamptz;

alter table public.reactions
    add column if not exists rev bigint;

comment on column public.reactions.liked_at is
    'When the track entered LIKED. NULL for every non-LIKED state - the trigger '
    'enforces that, so no client can violate it. This is what orders a restored '
    'Collection on a device that has never seen the track.';

comment on column public.reactions.rev is
    'Server-assigned change ordering: fresh on every insert and update. It is the '
    'pull cursor, the pull page ordering, and the client-side watermark that stops a '
    'stale page regressing local state. It is NOT a compare-and-set token - push '
    'exactly-once comes from reaction_event_applications. Never set by a client: it '
    'has deliberately no column default, see the trigger below.';

-- ------------------------------------------------------------- backfill --
--
-- Both statements are re-runnable: a second run matches nothing.
--
-- A currently-LIKED row's last transition *was* into LIKED, so its `updated_at` is
-- that moment. The one exception is a row restored by Undo, which carries its
-- original liked_at with a later updated_at - and that is precisely the defect this
-- column exists to stop repeating going forward. No currently-LIKED row is left
-- NULL, which is what the constraint below depends on.

update public.reactions
   set liked_at = updated_at
 where reaction = 'LIKED'
   and liked_at is null;

update public.reactions
   set liked_at = null
 where reaction in ('NEUTRAL', 'DISLIKED')
   and liked_at is not null;

-- Distinct ascending values for existing rows. Their relative order is arbitrary
-- and harmless: `rev` only ever answers "newer than what I last saw".
update public.reactions
   set rev = nextval('public.reactions_rev_seq'::regclass)
 where rev is null;

alter table public.reactions
    alter column rev set not null;

-- NOTE: `rev` deliberately has NO column default.
--
-- A default is evaluated as the *invoking* user when the tuple is constructed,
-- before any BEFORE trigger runs. A `nextval` default would therefore require every
-- `authenticated` client to hold USAGE on the sequence, and an old client's direct
-- INSERT would fail outright without it. The SECURITY DEFINER trigger below is the
-- only writer, and BEFORE ROW triggers run before NOT NULL is checked, so the
-- constraint is satisfied without granting anything to anyone.

-- ------------------------------------------------------------ constraint --

do $$
begin
    if not exists (
        select 1
          from pg_constraint con
          join pg_class rel on rel.oid = con.conrelid
          join pg_namespace nsp on nsp.oid = rel.relnamespace
         where nsp.nspname = 'public'
           and rel.relname = 'reactions'
           and con.conname = 'reactions_liked_at_matches_reaction'
    ) then
        alter table public.reactions
            add constraint reactions_liked_at_matches_reaction
            check ((reaction = 'LIKED') = (liked_at is not null));
    end if;
end
$$;

-- ----------------------------------------------------------------- index --
--
-- The pull's access path: `where listener_id = ? and rev > ? order by rev`. A
-- keyset scan in index order, no sort.

create index if not exists reactions_listener_rev_idx
    on public.reactions (listener_id, rev);

-- ------------------------------------------------------- rev/liked_at trigger --
--
-- SECURITY DEFINER, and that is the point rather than a shortcut. Old clients write
-- `reactions` directly as `authenticated`; if this ran as the invoker they would
-- each need USAGE on the sequence. Running as the owner keeps that capability out
-- of client hands entirely.
--
-- The shape is the minimal safe one for a definer function: no table access, no
-- dynamic SQL, no user-supplied identifier, `search_path` pinned empty, every
-- object fully qualified. PostgreSQL additionally refuses to invoke a function
-- returning `trigger` directly, so it has no callable surface at all.
--
-- `liked_at` derivation, and why one expression covers every case: on an UPDATE,
-- PostgreSQL builds NEW from the old row with only the statement's SET list applied,
-- and PostgREST only sets the columns present in the request body. So an old client
-- that never mentions `liked_at` arrives here with NEW.liked_at already equal to the
-- old value - preserved for LIKED -> LIKED, and NULL when the row is entering LIKED,
-- where the incoming client `updated_at` is the right derivation.
--
-- `updated_at` is not assigned. See the file header.

create or replace function public.reactions_assign_rev()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
    new.rev := nextval('public.reactions_rev_seq'::regclass);

    if new.reaction = 'LIKED' then
        new.liked_at := coalesce(new.liked_at, new.updated_at, pg_catalog.now());
    else
        new.liked_at := null;
    end if;

    return new;
end;
$$;

revoke all on function public.reactions_assign_rev() from public;
revoke all on function public.reactions_assign_rev() from anon;
revoke all on function public.reactions_assign_rev() from authenticated;

drop trigger if exists reactions_assign_rev_trg on public.reactions;
create trigger reactions_assign_rev_trg
    before insert or update on public.reactions
    for each row execute function public.reactions_assign_rev();

-- ------------------------------------------- reaction_events: FK target --
--
-- `event_id` is already the primary key, so this is logically redundant - but
-- PostgreSQL requires a UNIQUE or PRIMARY KEY on exactly the referenced column list,
-- and the applications table references the *pair* so that an application row naming
-- a different listener than its event is impossible to insert.
--
-- Nothing else about this table changes. No column, no policy, and still no UPDATE
-- or DELETE policy for any client role.

do $$
begin
    if not exists (
        select 1
          from pg_constraint con
          join pg_class rel on rel.oid = con.conrelid
          join pg_namespace nsp on nsp.oid = rel.relnamespace
         where nsp.nspname = 'public'
           and rel.relname = 'reaction_events'
           and con.conname = 'reaction_events_event_listener_key'
    ) then
        alter table public.reaction_events
            add constraint reaction_events_event_listener_key
            unique (event_id, listener_id);
    end if;
end
$$;

-- ------------------------------------------- reaction_event_applications --
--
-- Which events have already had their effect committed to current state, and at
-- which revision. Separate from `reaction_events` on purpose: that table is
-- historical fact, append-only, with no client UPDATE policy - a property worth
-- keeping literally true. Whether an event has been *applied* is delivery metadata
-- about it, not part of what happened.
--
-- ## It starts empty, and is never backfilled
--
-- The pre-G-A7 client delivered an event and reconciled current state as two
-- separate calls. Looking at an event written that way, nobody can tell whether its
-- state write landed: a crash between the two calls and a crash after both look
-- identical from here. Marking historical events applied would silently discard the
-- second case; marking them unapplied would license a stale replay of the first.
--
-- Neither is chosen. The ambiguity is never resolved because it is never asked. On
-- the device, outbox rows predating the new protocol are tagged LEGACY and drained
-- by the old path, which never reaches this table. And in the RPC, an event that
-- already exists without an application row is refused outright rather than adopted
-- into the atomic protocol - see classification case C below.

create table if not exists public.reaction_event_applications (
    event_id    uuid primary key,
    listener_id uuid not null,
    applied_rev bigint not null,
    applied_at  timestamptz not null default now(),

    constraint reaction_event_applications_matches_event
        foreign key (event_id, listener_id)
        references public.reaction_events (event_id, listener_id)
        on delete cascade
);

comment on table public.reaction_event_applications is
    'Server-owned. Written only by apply_reaction_event_batch. No client policy of '
    'any kind: the RPC returns everything a client needs, so there is nothing to '
    'read here directly.';

alter table public.reaction_event_applications enable row level security;

-- RLS is on with no policies at all, so no non-owner role can reach a row even if a
-- grant is added later by accident. The revokes say the same at the table level -
-- and are required, because Supabase's default privileges would otherwise grant
-- this new table to anon and authenticated automatically.
revoke all on public.reaction_event_applications from public;
revoke all on public.reaction_event_applications from anon;
revoke all on public.reaction_event_applications from authenticated;

-- ------------------------------------------- apply_reaction_event_batch --
--
-- One track, one state, one event set, one transaction.
--
-- ## Why a batch rather than one event
--
-- The current state of a track is the cumulative result of *every* local transition
-- applied to it. So a state application published on behalf of one event also
-- carries the effect of every other pending event for that track. Marking only the
-- one that happened to be sent would under-report what the revision represents, and
-- a sibling could later be treated as genuinely unapplied and replay a state that
-- had already reached the cloud.
--
-- Taking the whole pending set and marking all of it in the transaction that writes
-- the state is what makes the marker mean what it says.
--
-- ## Classification, after the ON CONFLICT boundary
--
-- Every supplied event_id resolves to exactly one of:
--
--   A  inserted by this transaction              -> genuinely new atomic event
--   B  pre-existing, payload matches, marked     -> legitimate atomic retry
--   C  pre-existing, payload matches, unmarked   -> pre-cutover event: REFUSE
--   D  pre-existing, foreign owner or different  -> identity conflict: REFUSE
--
-- C is the one that must never be waved through. An event in `reaction_events` with
-- no application row is exactly the legacy ambiguity above, and silently adopting it
-- into the atomic protocol would either resurrect a delivered action or claim an
-- undelivered one had landed. Refusing costs nothing: a legacy row is drained by the
-- legacy path and never arrives here in the first place.
--
-- The classification is evaluated **after** the insert resolves, against what is
-- actually committed - never against what was true before it.
--
-- ## Concurrency
--
-- A transaction-scoped advisory lock on (listener, track) serialises every call for
-- the same track. Two concurrent identical batches therefore run strictly in order:
-- the first commits APPLIED, the second sees its committed events and markers, and
-- returns ALREADY_APPLIED without a second revision. Nothing depends on UUID
-- randomness or on ON CONFLICT's wait semantics.
--
-- ## Identity
--
-- `auth.uid()` only. There is no listener_id parameter, so a caller has nothing to
-- forge. Every read and write below is scoped to it.
--
-- ## One track_key
--
-- Events carry no track_key of their own. That the events and the state describe the
-- same track is structural, not checked - there is no second value to disagree.

create or replace function public.apply_reaction_event_batch(
    p_track_key  text,
    p_events     jsonb,
    p_reaction   text,
    p_liked_at   timestamptz,
    p_artist     text,
    p_title      text,
    p_stream     text,
    p_updated_at timestamptz
) returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    -- Bounds. See the migration report for how each was derived.
    max_events    constant int    := 256;
    max_bytes     constant bigint := 2097152;   -- 2 MiB, total over all text input
    max_key_chars constant int    := 640;       -- 'legacy:' + 300 + sep + 300 = 608
    max_text      constant int    := 300;       -- matches reactions_text_within_bounds

    v_uid        uuid;
    v_count      int;
    v_distinct   int;
    v_bytes      bigint;
    v_inserted   uuid[];
    v_missing    int;
    v_mismatched int;
    v_ambiguous  int;
    v_marked     int;
    v_row        public.reactions%rowtype;
begin
    ---------------------------------------------------------------- identity
    v_uid := auth.uid();
    if v_uid is null then
        raise exception 'not authenticated' using errcode = '28000';
    end if;

    ------------------------------------------------- envelope shape and size
    if p_events is null or pg_catalog.jsonb_typeof(p_events) <> 'array' then
        raise exception 'p_events must be a json array' using errcode = '22023';
    end if;

    v_count := pg_catalog.jsonb_array_length(p_events);
    if v_count < 1 or v_count > max_events then
        raise exception 'event count out of range' using errcode = '22023';
    end if;

    -- The complete client-supplied text input, not just the events array. This is
    -- also the only bound on `stream`, deliberately: see the file header. The two
    -- timestamptz parameters are fixed-width and carry no client-controlled size.
    v_bytes := pg_catalog.octet_length(p_events::text)
             + pg_catalog.octet_length(coalesce(p_track_key, ''))
             + pg_catalog.octet_length(coalesce(p_reaction, ''))
             + pg_catalog.octet_length(coalesce(p_artist, ''))
             + pg_catalog.octet_length(coalesce(p_title, ''))
             + pg_catalog.octet_length(coalesce(p_stream, ''));

    if v_bytes > max_bytes then
        raise exception 'payload too large' using errcode = '22023';
    end if;

    -------------------------------------------------- current-state validity
    if p_track_key is null
       or pg_catalog.length(p_track_key) > max_key_chars
       or not (p_track_key ~ '^[0-9a-f]{64}$' or p_track_key ~ '^legacy:') then
        raise exception 'invalid track_key' using errcode = '22023';
    end if;

    if p_reaction is null
       or p_reaction not in ('NEUTRAL', 'LIKED', 'DISLIKED') then
        raise exception 'invalid reaction' using errcode = '22023';
    end if;

    if (p_reaction = 'LIKED') <> (p_liked_at is not null) then
        raise exception 'liked_at must be present iff reaction is LIKED'
            using errcode = '22023';
    end if;

    -- `stream` is deliberately NOT length-checked, here or in the event payloads.
    -- Legacy Room rows copy favorites.stream verbatim from older APKs and were never
    -- bounded locally, and public.reactions.stream has never carried a CHECK - so no
    -- production query can prove a per-field cap safe for every installed device.
    -- Introducing one would turn a valid listener's row into a permanent failure.
    if p_artist is null or pg_catalog.length(p_artist) not between 1 and max_text
       or p_title is null or pg_catalog.length(p_title) not between 1 and max_text then
        raise exception 'invalid current-state text' using errcode = '22023';
    end if;

    -- Old clients guard their pushes on this column, so it must keep arriving with
    -- the same meaning it has always had: the device's own clock at the tap.
    if p_updated_at is null then
        raise exception 'updated_at is required' using errcode = '22023';
    end if;

    ---------------------------------------------------------- event validity
    if exists (
        select 1
          from pg_catalog.jsonb_array_elements(p_events) as e(value)
         where e.value->>'event_id'    is null
            or e.value->>'event_type'  is null
            or e.value->>'artist'      is null
            or e.value->>'title'       is null
            or e.value->>'occurred_at' is null
            or e.value->>'event_type' not in ('LIKE', 'UNLIKE', 'DISLIKE', 'UNDISLIKE')
            or pg_catalog.length(e.value->>'artist') not between 1 and max_text
            or pg_catalog.length(e.value->>'title')  not between 1 and max_text
    ) then
        raise exception 'invalid event payload' using errcode = '22023';
    end if;

    -- Forces every uuid and timestamptz cast while nothing has been written and no
    -- lock is held. A malformed value aborts here, which is the required failure
    -- mode: no event inserted, no state written, no marker created.
    perform 1
       from pg_catalog.jsonb_array_elements(p_events) as e(value)
      where (e.value->>'event_id')::uuid is not null
        and (e.value->>'occurred_at')::timestamptz is not null;

    select pg_catalog.count(distinct (e.value->>'event_id'))
      into v_distinct
      from pg_catalog.jsonb_array_elements(p_events) as e(value);

    if v_distinct <> v_count then
        raise exception 'duplicate event_id in batch' using errcode = '22023';
    end if;

    -------------------------------------------------------------- serialise
    --
    -- Transaction-scoped, released on commit or abort. Every call touching this
    -- listener's copy of this track runs in order, so the classification below reads
    -- committed state rather than racing another writer. A hash collision between
    -- two unrelated (listener, track) pairs costs a little serialisation and is never
    -- incorrect.
    perform pg_catalog.pg_advisory_xact_lock(
        pg_catalog.hashtextextended(v_uid::text || '|' || p_track_key, 0));

    --------------------------------------------------------- first write here
    --
    -- RETURNING gives exactly the rows this transaction inserted. That set is what
    -- separates classification A from C below, and it cannot be inferred any other
    -- way once the conflict has resolved.
    with ins as (
        insert into public.reaction_events
            (event_id, listener_id, track_key, artist, title, event_type, stream, occurred_at)
        select (e.value->>'event_id')::uuid,
               v_uid,
               p_track_key,
               e.value->>'artist',
               e.value->>'title',
               e.value->>'event_type',
               e.value->>'stream',
               (e.value->>'occurred_at')::timestamptz
          from pg_catalog.jsonb_array_elements(p_events) as e(value)
            on conflict (event_id) do nothing
        returning event_id
    )
    select coalesce(pg_catalog.array_agg(event_id), '{}'::uuid[])
      into v_inserted
      from ins;

    ------------------------------------------- authoritative classification --
    --
    -- Evaluated against what is committed now, after the conflict resolved - never
    -- against the pre-insert snapshot. If another transaction won an event_id race
    -- with a different payload, this sees the winner's row and refuses.
    with supplied as (
        select (e.value->>'event_id')::uuid                  as event_id,
               e.value->>'artist'                            as artist,
               e.value->>'title'                             as title,
               e.value->>'event_type'                        as event_type,
               e.value->>'stream'                            as stream,
               (e.value->>'occurred_at')::timestamptz        as occurred_at
          from pg_catalog.jsonb_array_elements(p_events) as e(value)
    )
    select
        pg_catalog.count(*) filter (where re.event_id is null),
        pg_catalog.count(*) filter (
            where re.event_id is not null
              and (   re.listener_id is distinct from v_uid
                   or re.track_key   is distinct from p_track_key
                   or re.artist      is distinct from s.artist
                   or re.title       is distinct from s.title
                   or re.event_type  is distinct from s.event_type
                   or re.stream      is distinct from s.stream
                   or re.occurred_at is distinct from s.occurred_at)),
        pg_catalog.count(*) filter (
            where re.event_id is not null
              and not (s.event_id = any (v_inserted))
              and a.event_id is null),
        pg_catalog.count(*) filter (where a.event_id is not null)
      into v_missing, v_mismatched, v_ambiguous, v_marked
      from supplied s
      left join public.reaction_events re
             on re.event_id = s.event_id
      left join public.reaction_event_applications a
             on a.event_id = s.event_id
            and a.listener_id = v_uid;

    -- Case D. Foreign ownership and payload mismatch raise the *same* error
    -- deliberately: a caller must not be able to learn whether an id exists or who
    -- owns it. The current-state payload is excluded from the comparison - it is not
    -- event identity, and it legitimately differs between an original send and a
    -- retry.
    if v_mismatched > 0 then
        raise exception 'event identity conflict' using errcode = '22023';
    end if;

    -- Case C. A pre-existing event with no application row predates the atomic
    -- protocol, and whether its effect ever reached current state is undecidable.
    -- Refuse rather than guess in either direction.
    if v_ambiguous > 0 then
        raise exception 'pre-cutover event cannot enter the atomic protocol'
            using errcode = '22023';
    end if;

    -- Defensive: every supplied id was either inserted above or already present.
    if v_missing > 0 then
        raise exception 'event row missing after insert' using errcode = 'XX000';
    end if;

    -------------------------------------------------------- already applied
    --
    -- Every represented event is case B: its effect is already committed. Writing
    -- state again would resurrect it over whatever another device has since done,
    -- which is exactly what this function exists to prevent. Nothing is written; the
    -- caller settles its outbox rows and adopts what is actually there.
    if v_marked = v_count then
        select * into v_row
          from public.reactions
         where listener_id = v_uid
           and track_key   = p_track_key;

        return pg_catalog.jsonb_build_object(
            'outcome', 'ALREADY_APPLIED',
            'row', case when v_row.track_key is null then null else
                pg_catalog.jsonb_build_object(
                    'track_key',  v_row.track_key,
                    'reaction',   v_row.reaction,
                    'liked_at',   v_row.liked_at,
                    'artist',     v_row.artist,
                    'title',      v_row.title,
                    'stream',     v_row.stream,
                    'updated_at', v_row.updated_at,
                    'rev',        v_row.rev
                ) end
        );
    end if;

    --------------------------------------------------------------- applied
    --
    -- At least one represented event is case A, so the state is new. One write, one
    -- fresh rev from the trigger, then a marker for every represented event that
    -- lacks one - all in this transaction. There is no commit in which the state
    -- reflects an event that is left unmarked.
    insert into public.reactions
        (listener_id, track_key, artist, title, reaction, stream, updated_at, liked_at)
    values
        (v_uid, p_track_key, p_artist, p_title, p_reaction, p_stream, p_updated_at, p_liked_at)
        on conflict (listener_id, track_key) do update
        set artist     = excluded.artist,
            title      = excluded.title,
            reaction   = excluded.reaction,
            stream     = excluded.stream,
            updated_at = excluded.updated_at,
            liked_at   = excluded.liked_at
    returning * into v_row;

    -- After the checks above, an unmarked event is necessarily one inserted here, so
    -- this marks exactly case A. A case-B event keeps its original revision: that
    -- marker records when its effect first reached the cloud, not the latest write.
    insert into public.reaction_event_applications (event_id, listener_id, applied_rev)
    select (e.value->>'event_id')::uuid, v_uid, v_row.rev
      from pg_catalog.jsonb_array_elements(p_events) as e(value)
        on conflict (event_id) do nothing;

    return pg_catalog.jsonb_build_object(
        'outcome', 'APPLIED',
        'row', pg_catalog.jsonb_build_object(
            'track_key',  v_row.track_key,
            'reaction',   v_row.reaction,
            'liked_at',   v_row.liked_at,
            'artist',     v_row.artist,
            'title',      v_row.title,
            'stream',     v_row.stream,
            'updated_at', v_row.updated_at,
            'rev',        v_row.rev
        )
    );
end;
$$;

-- CREATE FUNCTION grants EXECUTE to PUBLIC by default, and Supabase's default
-- privileges add anon and authenticated on top. Both revokes are load-bearing.
revoke all on function public.apply_reaction_event_batch(
    text, jsonb, text, timestamptz, text, text, text, timestamptz) from public;
revoke all on function public.apply_reaction_event_batch(
    text, jsonb, text, timestamptz, text, text, text, timestamptz) from anon;
grant execute on function public.apply_reaction_event_batch(
    text, jsonb, text, timestamptz, text, text, text, timestamptz) to authenticated;

commit;
