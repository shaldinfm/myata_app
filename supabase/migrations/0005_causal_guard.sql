-- Radio Myata: a queued reaction never overwrites a newer server state.
--
-- Forward migration from the live 0004 schema. Additive: one column with a default,
-- one new overload of apply_reaction_event_batch. Nothing is dropped, no existing
-- function, policy or grant is altered. One transaction - a failure anywhere changes
-- nothing. Idempotent: safe to run twice.
--
-- NOT APPLIED. This file is source only; see the rollout section before any
-- production action.
--
-- ## The defect
--
-- 0003's apply_reaction_event_batch writes current state unconditionally for any
-- event it has not seen before. That is correct for "not seen before" and wrong for
-- "not *current*": a Like tapped on an offline phone, delivered weeks later, lands
-- on top of a Dislike another device - or the website - recorded in between. One
-- account is supposed to have one Collection, and the last request to arrive is not
-- the listener's last word.
--
-- ## The guard: the revision the device last saw
--
-- The client now sends `p_base_rev`: the server revision it last observed for this
-- track (Room's `track_reaction.remote_rev`), or null when it has seen none. The pull
-- never advances that value while local acts are pending for the track, and the
-- drain sends every pending act for a track as one batch - so a whole offline chain
-- (LIKE, UNLIKE, LIKE) carries one baseline, the state before the chain began.
--
-- State is written only when the row is exactly as the device last knew it:
--
--   no row                                       -> apply (nothing to protect)
--   row.rev = p_base_rev                         -> apply
--   row.rev = a revision this batch's own,       -> apply (the chain's earlier acts
--     already-applied events produced                    reached the cloud; the
--                                                        answer was lost)
--   anything else                                -> CONFLICT: the server moved on
--                                                   independently. Nothing is written
--                                                   to reactions.
--
-- A null baseline with a row present is a conflict too: the device is claiming the
-- track was untouched when it is not. Devices learn every row's revision from their
-- pull and from their own applied writes, so "null" and "a row exists" coincide only
-- when the server holds state this device never saw.
--
-- No wall-clock comparison anywhere. `updated_at` keeps its old meaning and is still
-- written, because installed 0003 clients guard their direct writes with it.
--
-- ## A conflict is resolved, not retried
--
-- The events are still history - they happened - so they stay in reaction_events,
-- where the insert at the top of the function already put them. Each is marked in
-- reaction_event_applications with `state_applied = false` and the revision it lost
-- to. That makes the outcome idempotent: a retry of the same batch finds every event
-- marked and answers ALREADY_APPLIED with the current row, exactly like any other
-- settled batch. The client settles its outbox rows and adopts the server's row.
--
-- `state_applied` exists so a superseded event is never mistaken for one whose
-- effect *is* the current row: only markers with `state_applied` count as "this
-- chain's own revision" in the guard above.
--
-- ## Rollout
--
--   1. Apply this migration to production (owner action). It is additive; installed
--      3.6.6 clients keep calling the unchanged 8-argument function and behave
--      exactly as before - unguarded, which is today's behaviour.
--   2. Only then ship a client that sends `p_base_rev`. A client that sends it to a
--      database without this migration gets PGRST202 (no such function) on every
--      batch and parks its outbox until the migration lands.
--   3. Later, once the 3.6.6 population has drained, the 8-argument overload and the
--      direct INSERT/UPDATE policies on reactions can be retired in a separate step.

begin;

-- ------------------------------------------------------------ the marker --

alter table public.reaction_event_applications
    add column if not exists state_applied boolean not null default true;

comment on column public.reaction_event_applications.state_applied is
    'True when this event''s effect was written to reactions at applied_rev. False '
    'when the event was recorded but superseded: the server state had moved on '
    'independently, and applied_rev is the revision it lost to.';

-- ---------------------------------------- apply_reaction_event_batch (9) --

create or replace function public.apply_reaction_event_batch(
    p_track_key  text,
    p_events     jsonb,
    p_reaction   text,
    p_liked_at   timestamptz,
    p_artist     text,
    p_title      text,
    p_stream     text,
    p_updated_at timestamptz,
    p_base_rev   bigint
) returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    -- Bounds, unchanged from 0003.
    max_events    constant int    := 256;
    max_bytes     constant bigint := 2097152;
    max_key_chars constant int    := 640;
    max_text      constant int    := 300;

    v_uid        uuid;
    v_count      int;
    v_distinct   int;
    v_bytes      bigint;
    v_inserted   uuid[];
    v_missing    int;
    v_mismatched int;
    v_ambiguous  int;
    v_marked     int;
    v_own_rev    bigint;
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

    if p_artist is null or pg_catalog.length(p_artist) not between 1 and max_text
       or p_title is null or pg_catalog.length(p_title) not between 1 and max_text then
        raise exception 'invalid current-state text' using errcode = '22023';
    end if;

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
    perform pg_catalog.pg_advisory_xact_lock(
        pg_catalog.hashtextextended(v_uid::text || '|' || p_track_key, 0));

    --------------------------------------------------------- first write here
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

    if v_mismatched > 0 then
        raise exception 'event identity conflict' using errcode = '22023';
    end if;

    if v_ambiguous > 0 then
        raise exception 'pre-cutover event cannot enter the atomic protocol'
            using errcode = '22023';
    end if;

    if v_missing > 0 then
        raise exception 'event row missing after insert' using errcode = 'XX000';
    end if;

    select * into v_row
      from public.reactions
     where listener_id = v_uid
       and track_key   = p_track_key;

    -------------------------------------------------------- already applied
    --
    -- Every represented event is marked - applied, or superseded by an earlier
    -- conflict. Nothing is written; the caller settles and adopts the current row.
    if v_marked = v_count then
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

    ------------------------------------------------------------ causal guard
    --
    -- The newest revision this chain's own earlier acts produced, if any reached
    -- the cloud in a call whose answer the device never received.
    select pg_catalog.max(a.applied_rev)
      into v_own_rev
      from pg_catalog.jsonb_array_elements(p_events) as e(value)
      join public.reaction_event_applications a
        on a.event_id = (e.value->>'event_id')::uuid
       and a.listener_id = v_uid
       and a.state_applied;

    if v_row.track_key is not null
       and v_row.rev is distinct from p_base_rev
       and v_row.rev is distinct from v_own_rev then

        -- Superseded: history kept, state untouched, every unmarked event marked so
        -- a retry answers ALREADY_APPLIED.
        insert into public.reaction_event_applications
            (event_id, listener_id, applied_rev, state_applied)
        select (e.value->>'event_id')::uuid, v_uid, v_row.rev, false
          from pg_catalog.jsonb_array_elements(p_events) as e(value)
            on conflict (event_id) do nothing;

        return pg_catalog.jsonb_build_object(
            'outcome', 'CONFLICT',
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
    end if;

    --------------------------------------------------------------- applied
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

    insert into public.reaction_event_applications (event_id, listener_id, applied_rev, state_applied)
    select (e.value->>'event_id')::uuid, v_uid, v_row.rev, true
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

revoke all on function public.apply_reaction_event_batch(
    text, jsonb, text, timestamptz, text, text, text, timestamptz, bigint) from public;
revoke all on function public.apply_reaction_event_batch(
    text, jsonb, text, timestamptz, text, text, text, timestamptz, bigint) from anon;
grant execute on function public.apply_reaction_event_batch(
    text, jsonb, text, timestamptz, text, text, text, timestamptz, bigint) to authenticated;

commit;
