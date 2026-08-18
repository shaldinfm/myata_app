-- PREFLIGHT - READ ONLY. Changes nothing. Safe to run any number of times.
--
-- Run this BEFORE 01_rebaseline_model_c.sql and read the output. The re-baseline
-- drops the reaction-foundation tables, and that is only acceptable while they
-- hold nothing but test data. This tells you whether that is still true.
--
-- What to look for:
--
--   * `reactions` / `reaction_events` - if either holds rows that came from a real
--     listener rather than from tools/supabase/rls-check.sh, STOP. Re-baselining
--     would delete them. Take the forward-migration route instead.
--   * `tracks` - expected to hold the harness rows and the junk row from the audit
--     that found the open INSERT policy. All disposable.
--
-- The `looks_like_test_data` column is a hint, not a verdict: it counts rows whose
-- track_key is not a TrackKey v1 value, which is what the harness and the audit
-- produced. A real listener's reaction always has a 64-hex key, so a non-zero
-- count in the *other* direction (real-looking keys) is the thing to think about.

select
    'reactions' as table_name,
    count(*)    as rows,
    count(*) filter (
        where track_key !~ '^[0-9a-f]{64}$' and track_key !~ '^legacy:'
    ) as looks_like_test_data,
    count(distinct listener_id) as distinct_listeners,
    min(updated_at) as earliest,
    max(updated_at) as latest
from public.reactions

union all

select
    'reaction_events',
    count(*),
    count(*) filter (
        where track_key !~ '^[0-9a-f]{64}$' and track_key !~ '^legacy:'
    ),
    count(distinct listener_id),
    min(occurred_at),
    max(occurred_at)
from public.reaction_events

union all

select
    'tracks',
    count(*),
    count(*) filter (
        where track_key !~ '^[0-9a-f]{64}$' and track_key !~ '^legacy:'
    ),
    null,
    min(first_seen_at),
    max(first_seen_at)
from public.tracks;

-- The objects the re-baseline will drop, so you can see exactly what exists now.
select
    c.relkind as kind,   -- r = table, v = view
    c.relname as name
from pg_class c
join pg_namespace n on n.oid = c.relnamespace
where n.nspname = 'public'
  and c.relname in ('tracks', 'reactions', 'reaction_events', 'track_reaction_totals')
order by c.relname;

-- Any function the earlier design would have added. Expected: no rows, because
-- 0002 was never applied.
select p.proname as function_name
from pg_proc p
join pg_namespace n on n.oid = p.pronamespace
where n.nspname = 'public'
  and p.proname = 'register_track';
