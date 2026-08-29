-- PREFLIGHT - READ ONLY. Changes nothing. Safe to run any number of times.
--
-- Run this BEFORE applying `supabase/migrations/0004_account_deletion.sql`, and
-- read the output. The migration deletes rows from `auth.users` inside a database
-- function, which is only available if this project's schema actually permits it.
-- This tells you whether it does.
--
-- Run it **as the role that will apply the migration**: query 1 asks about
-- `current_user`, and a SECURITY DEFINER function executes as its owner, which is
-- whoever created it.
--
-- ## What decides the design
--
--   1  `can_delete_auth_users = false`   -> the single-transaction design is dead.
--                                          Fall back to an Edge Function holding the
--                                          service-role key, and accept that
--                                          deleting rows and deleting the auth user
--                                          are then two systems with no shared
--                                          transaction.
--   3  any `no action` or `restrict`     -> the same, unless that table is one the
--                                          function may legitimately clear first.
--   5  any bucket or owned object        -> STOP. `storage.objects` rows are
--                                          metadata; deleting them with SQL orphans
--                                          the physical files, so owned objects need
--                                          the Storage API, which cannot run inside
--                                          this transaction. See the Storage
--                                          invariant in the migration header.
--   6  anything non-null                 -> an object of that name already exists.
--                                          Do not apply until it is understood.
--
-- ## Recorded baseline - production, 2026-08-29, PASS
--
--     current_user            postgres           auth.users owner  supabase_auth_admin
--     can_use_auth_schema     true               bypasses_rls      true
--     can_read_auth_users     true
--     can_delete_auth_users   true
--     FKs -> auth.users       all CASCADE
--     buckets 0   objects 0   objects_with_an_owner 0
--     receipts table / delete_my_account / account_deletion_status   all absent
--     auth.users 69   reactions 6   reaction_events 13   applications 13
--
-- Re-run and compare before any later re-apply. A row that has moved is a question,
-- not a formality.

-- ------------------------------------------------------------------ 1 --
-- Who am I, and may I delete an auth user.

select current_user,
       (select rolsuper      from pg_roles where rolname = current_user) as is_superuser,
       (select rolbypassrls  from pg_roles where rolname = current_user) as bypasses_rls,
       pg_get_userbyid((select relowner from pg_class where oid = 'auth.users'::regclass))
           as auth_users_owner,
       has_schema_privilege(current_user, 'auth', 'USAGE')       as can_use_auth_schema,
       -- SELECT matters as much as DELETE: the function checks whether the row
       -- exists before deleting it, which is what separates DELETED from
       -- ALREADY_DELETED on the two-device path.
       has_table_privilege(current_user, 'auth.users', 'SELECT') as can_read_auth_users,
       has_table_privilege(current_user, 'auth.users', 'DELETE') as can_delete_auth_users;

-- ------------------------------------------------------------------ 2 --
-- Row-level security on auth.users. `postgres` bypasses RLS, so this is recorded
-- for the reader rather than as a gate.

select relrowsecurity as rls_enabled, relforcerowsecurity as rls_forced
  from pg_class where oid = 'auth.users'::regclass;

-- ------------------------------------------------------------------ 3 --
-- THE DECIDING QUERY: every foreign key pointing at auth.users, and what it does on
-- delete. Anything reported `no action` or `restrict` will abort the deletion.

select con.conrelid::regclass as referencing_table,
       con.conname,
       case con.confdeltype when 'c' then 'cascade'  when 'a' then 'no action'
                            when 'r' then 'restrict' when 'n' then 'set null'
                            when 'd' then 'set default' end as on_delete
  from pg_constraint con
 where con.confrelid = 'auth.users'::regclass and con.contype = 'f'
 order by 3, 1;

-- ------------------------------------------------------------------ 4 --
-- Non-internal triggers on auth.users. Anything here runs inside the deleting
-- transaction and can fail it.

select tgname, tgenabled, pg_get_triggerdef(oid) as definition
  from pg_trigger where tgrelid = 'auth.users'::regclass and not tgisinternal;

-- ------------------------------------------------------------------ 5a --
-- Storage discovery. Run this BEFORE 5b/5c/5d and skip those if either regclass
-- comes back null.

select to_regclass('storage.buckets') as buckets_table,
       to_regclass('storage.objects') as objects_table;

select column_name, data_type, is_nullable
  from information_schema.columns
 where table_schema = 'storage' and table_name = 'objects'
 order by ordinal_position;

-- ------------------------------------------------------------------ 5b --
-- Bucket count. Only if 5a reported a non-null buckets_table.

select count(*) as buckets from storage.buckets;

-- ------------------------------------------------------------------ 5c --
-- Total objects, and how many carry an owner under EITHER column name. Only if 5a
-- reported a non-null objects_table.
--
-- `to_jsonb(o) ->> 'owner_id'` yields NULL for an absent key rather than raising, so
-- this one statement is valid on every Supabase schema version - the older `owner`
-- column, the newer `owner_id`, or both. Naming a column directly would let the
-- preflight fail before it told us anything.

select count(*) as objects,
       count(*) filter (
           where coalesce(to_jsonb(o) ->> 'owner_id',
                          to_jsonb(o) ->> 'owner') is not null
       ) as objects_with_an_owner
  from storage.objects o;

-- ------------------------------------------------------------------ 5d --
-- Objects owned by ONE specific uid, same column-agnostic access. For the
-- per-account check at live validation; substitute the uid under test.

select count(*) as objects_owned_by_uid
  from storage.objects o
 where coalesce(to_jsonb(o) ->> 'owner_id',
                to_jsonb(o) ->> 'owner') = '00000000-0000-0000-0000-000000000000';

-- ------------------------------------------------------------------ 6 --
-- Nothing the migration is about to create already exists.

select to_regclass('public.account_deletion_receipts') as receipts_table;

select p.proname, pg_get_function_identity_arguments(p.oid) as args
  from pg_proc p join pg_namespace n on n.oid = p.pronamespace
 where n.nspname = 'public'
   and p.proname in ('delete_my_account', 'account_deletion_status');

-- ------------------------------------------------------------------ 7 --
-- The reaction cascades are what migrations 0001 and 0003 say they are. The
-- migration's explicit deletes do not depend on these, but a surprise here means
-- the schema is not the one this design was written against.

select con.conrelid::regclass as tbl,
       con.conname,
       con.confrelid::regclass as refs,
       case con.confdeltype when 'c' then 'cascade' else con.confdeltype::text end as on_delete
  from pg_constraint con
 where con.contype = 'f'
   and con.conrelid in ('public.reactions'::regclass,
                        'public.reaction_events'::regclass,
                        'public.reaction_event_applications'::regclass);

-- ------------------------------------------------------------------ 8 --
-- Scale sanity, so the migration report has real numbers beside it.

select (select count(*) from auth.users)                         as auth_users,
       (select count(*) from public.reactions)                   as reactions,
       (select count(*) from public.reaction_events)             as reaction_events,
       (select count(*) from public.reaction_event_applications) as applications;
