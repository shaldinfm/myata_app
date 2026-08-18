-- Radio Myata: close the open write path into the track catalogue.
--
-- 0001 gave `tracks` an INSERT policy of `with check (true)` for any
-- authenticated listener. The comment above it said "add a track they are
-- reacting to", but the policy said no such thing, and the live project proved it:
-- an anonymous client inserted `junk-not-a-trackkey-...` with an artist of
-- `<script>whatever` and a 300-character title, having reacted to nothing. Anyone
-- with the publishable key - which ships in the APK - could fill the station's
-- catalogue with anything at all.
--
-- Existing rows were never rewritable (there is no UPDATE or DELETE policy, and
-- that is unchanged and verified), so this is about what can be *added*.
--
-- Applied on top of 0001, which is already live. Idempotent.

-- ------------------------------------------------ no direct client writes --
--
-- The catalogue is now unwritable through PostgREST. `tracks` keeps SELECT for
-- authenticated listeners and has no INSERT, UPDATE or DELETE policy of any kind,
-- so the only way a row can appear is the function below.
drop policy if exists "tracks can be added by signed-in listeners" on public.tracks;

-- --------------------------------------------------------- shape, enforced --
--
-- A track_key is a TrackKey v1 value: 64 lowercase hex characters, or the
-- `legacy:` namespace the Room migration uses for rows v1 cannot key
-- (docs/TRACKKEY-V1.md). Anything else is not a track identity.
--
-- NOT VALID deliberately: it constrains every future row without failing on what
-- is already there. The live catalogue currently holds test rows from the RLS
-- harness and from the audit that found this hole; deleting other people's data
-- from a migration is not this file's business. See the doc for a cleanup snippet.
alter table public.tracks drop constraint if exists tracks_key_is_trackkey_v1;
alter table public.tracks
    add constraint tracks_key_is_trackkey_v1
    check (track_key ~ '^[0-9a-f]{64}$' or track_key ~ '^legacy:')
    not valid;

alter table public.tracks drop constraint if exists tracks_text_within_bounds;
alter table public.tracks
    add constraint tracks_text_within_bounds
    check (
        length(artist) between 1 and 300
        and length(title) between 1 and 300
    )
    not valid;

-- ------------------------------------------------- the one way in --
--
-- How a legitimate row is created, and the only way: the client calls this while
-- recording its own reaction. It is SECURITY DEFINER, so it can write a table no
-- client role can, and it is narrow on purpose:
--
--   * it refuses an unauthenticated caller;
--   * it validates the key shape rather than trusting the caller;
--   * it trims and bounds the words;
--   * it is ON CONFLICT DO NOTHING, so it can *create* a catalogue entry and can
--     never modify one. Two listeners disagreeing about a track's spelling cannot
--     overwrite each other, and nobody can rewrite the station's words by calling
--     it with a key that already exists.
--
-- What it deliberately does not solve: the artist and title of a *new* key still
-- come from a client, because the station's metadata API has no track ids and the
-- device is the only source. What is bounded now is that a row can only appear
-- through this path, in the right shape, and can never be changed afterwards.
create or replace function public.register_track(
    p_track_key text,
    p_artist    text,
    p_title     text
)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
    if auth.uid() is null then
        raise exception 'register_track requires an authenticated listener'
            using errcode = '42501';
    end if;

    if p_track_key !~ '^[0-9a-f]{64}$' and p_track_key !~ '^legacy:' then
        raise exception 'track_key is not a TrackKey v1 value'
            using errcode = '22023';
    end if;

    if length(btrim(coalesce(p_artist, ''))) = 0
       or length(btrim(coalesce(p_title, ''))) = 0 then
        raise exception 'artist and title are required'
            using errcode = '22023';
    end if;

    insert into public.tracks (track_key, artist, title)
    values (p_track_key, left(btrim(p_artist), 300), left(btrim(p_title), 300))
    on conflict (track_key) do nothing;
end;
$$;

revoke all on function public.register_track(text, text, text) from public;
revoke all on function public.register_track(text, text, text) from anon;
grant execute on function public.register_track(text, text, text) to authenticated;

comment on function public.register_track(text, text, text) is
    'The only way a client can add a catalogue row. Validates TrackKey v1 shape, never updates an existing row.';
