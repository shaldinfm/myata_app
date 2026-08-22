-- Radio Myata: NEUTRAL becomes a value.
--
-- Forward migration from the live Model C schema (0001). Non-destructive: no table
-- is dropped, no row is deleted, no data is rewritten. It changes one CHECK
-- constraint and one view.
--
-- ## What changes, and why
--
-- 0001 said "NEUTRAL is absence": a withdrawn reaction deleted its `reactions` row.
-- That is a clean model right up to the moment a second device, or a second
-- identity, has to be reconciled with the first - because absence has no
-- `updated_at`. A deleted row cannot say *when* the listener changed their mind, so
-- the last-writer-wins guard that protects LIKED and DISLIKED had nothing to
-- compare against for the third state, and the only remaining tie-break was
-- delivery order. Delivery order is exactly the thing the sync design refuses to
-- depend on (docs/SUPABASE-SYNC.md).
--
-- So NEUTRAL becomes a stored value. A withdrawal now writes
-- `reaction = 'NEUTRAL'` with its own `updated_at`, and every transition - in both
-- directions - travels the same guarded upsert. The three current states are:
--
--     NEUTRAL   no opinion, or an opinion withdrawn
--     LIKED     explicit positive
--     DISLIKED  explicit negative
--
-- `reaction_events` is untouched. Its vocabulary is still exactly the four real
-- transitions - LIKE, UNLIKE, DISLIKE, UNDISLIKE - and nothing here manufactures a
-- fifth. State and history stay two different questions.
--
-- ## What this deliberately does not do
--
--  * **No DELETE policy is removed.** Normal reaction sync no longer deletes
--    anything, but account handoff and data removal still need it, and taking it
--    away would leave a listener unable to erase their own rows.
--  * **No tombstone expiry.** NEUTRAL rows are kept indefinitely for v1. They are
--    small, they are the thing that makes reconciliation total, and a sweeper is a
--    decision to take with data in hand rather than up front.
--  * **No change to RLS, to `reaction_events`, to the key-shape or text-length
--    constraints, or to any grant.**
--
-- Apply with:  supabase db push        (or paste into the SQL editor)
-- Idempotent: safe to run twice. Runs in one transaction - if any statement fails,
-- nothing changes.

begin;

-- ------------------------------------------------- reactions.reaction --
--
-- 0001 wrote this check inline, so Postgres named it. Rather than guess the
-- generated name, find the single-column check on `reaction` that does not yet
-- allow NEUTRAL and drop exactly that one. On a second run there is nothing to
-- find, which is what makes this re-runnable.
do $$
declare
    doomed record;
begin
    for doomed in
        select con.conname
        from pg_constraint con
        join pg_class rel on rel.oid = con.conrelid
        join pg_namespace nsp on nsp.oid = rel.relnamespace
        where nsp.nspname = 'public'
          and rel.relname = 'reactions'
          and con.contype = 'c'
          and con.conkey = array[
              (select att.attnum
                 from pg_attribute att
                where att.attrelid = rel.oid
                  and att.attname = 'reaction')
          ]
          and pg_get_constraintdef(con.oid) not like '%NEUTRAL%'
    loop
        execute format('alter table public.reactions drop constraint %I', doomed.conname);
    end loop;
end
$$;

-- Named this time, so the next migration does not have to go looking.
do $$
begin
    if not exists (
        select 1
        from pg_constraint con
        join pg_class rel on rel.oid = con.conrelid
        join pg_namespace nsp on nsp.oid = rel.relnamespace
        where nsp.nspname = 'public'
          and rel.relname = 'reactions'
          and con.conname = 'reactions_reaction_is_known'
    ) then
        alter table public.reactions
            add constraint reactions_reaction_is_known
            check (reaction in ('NEUTRAL', 'LIKED', 'DISLIKED'));
    end if;
end
$$;

comment on column public.reactions.reaction is
    'NEUTRAL | LIKED | DISLIKED. NEUTRAL is a stored value, not an absent row: a '
    'withdrawal has an updated_at, so it can win or lose a last-writer-wins '
    'comparison like any other state. Rows are no longer deleted by normal sync.';

-- ----------------------------------------------------- station analytics --
--
-- Same six columns, same meaning, one addition: a track appears only if somebody
-- currently holds an opinion about it.
--
-- Likes and dislikes count what they always counted. What changes is that NEUTRAL
-- rows now exist, and a track whose every current row is NEUTRAL would otherwise
-- surface as a 0/0 line - sync metadata wearing the costume of a music-programming
-- signal. The HAVING removes it. The moment one listener likes or dislikes it, the
-- track returns, with its full history of withdrawals intact underneath.
--
-- The NEUTRAL rows are still *inside* each surviving group, deliberately:
--
--  * `last_activity` counts a withdrawal as activity, because it is. A track whose
--    only recent event was somebody taking their Like back has not gone quiet.
--  * `mode()` sees every spelling this key was ever observed under, which is more
--    evidence for the same answer and changes no count.
create or replace view public.track_reaction_totals
with (security_invoker = true) as
select
    track_key,
    mode() within group (order by artist) as artist,
    mode() within group (order by title)  as title,
    count(*) filter (where reaction = 'LIKED')    as likes,
    count(*) filter (where reaction = 'DISLIKED') as dislikes,
    max(updated_at)                                as last_activity
from public.reactions
group by track_key
having count(*) filter (where reaction in ('LIKED', 'DISLIKED')) > 0;

comment on view public.track_reaction_totals is
    'Owner-only. Service role reads this; anon and authenticated are revoked '
    'deliberately. Excludes tracks whose current rows are all NEUTRAL - a 0/0 row '
    'is sync metadata, not programming analytics.';

-- create or replace preserves grants; re-stating them costs nothing and means this
-- file alone is enough to know who can reach the view.
revoke all on public.track_reaction_totals from anon, authenticated;
revoke all on public.reactions from anon;
revoke all on public.reaction_events from anon;

commit;

-- ---------------------------------------------------------------- checks --
--
-- Read-only. Run after the transaction commits; none of it changes anything.
--
--   -- 1. the constraint now admits three values, and only three
--   select conname, pg_get_constraintdef(oid)
--     from pg_constraint
--    where conrelid = 'public.reactions'::regclass and contype = 'c';
--
--   -- 2. nothing was lost
--   select reaction, count(*) from public.reactions group by reaction;
--
--   -- 3. the view still resolves, and 0/0 rows are gone
--   select count(*) as visible_tracks from public.track_reaction_totals;
--   select count(*) filter (where likes = 0 and dislikes = 0) as should_be_zero
--     from public.track_reaction_totals;
--
--   -- 4. reaction_events untouched
--   select conname, pg_get_constraintdef(oid)
--     from pg_constraint
--    where conrelid = 'public.reaction_events'::regclass and contype = 'c';
