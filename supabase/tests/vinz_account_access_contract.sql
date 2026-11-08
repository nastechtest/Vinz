-- Run with: supabase test db (or psql against a disposable database after migrations).
-- These assertions deliberately protect the distinction between Postgres grants and RLS.
do $$
begin
  if not exists (
    select 1 from pg_class c
    join pg_namespace n on n.oid = c.relnamespace
    where n.nspname = 'public' and c.relname = 'vinz_profiles' and c.relrowsecurity
  ) then
    raise exception 'vinz_profiles must have RLS enabled';
  end if;

  if has_column_privilege('anon', 'public.vinz_profiles', 'id', 'SELECT') then
    raise exception 'anon must not read private Vinz profile rows';
  end if;
  if not has_column_privilege('authenticated', 'public.vinz_profiles', 'id', 'SELECT') then
    raise exception 'authenticated must have profile SELECT privilege; RLS limits rows';
  end if;
  if not exists (
    select 1 from pg_policies
    where schemaname = 'public'
      and tablename = 'vinz_profiles'
      and policyname = 'Vinz profiles readable by owner or public'
      and cmd = 'SELECT'
      and roles @> array['authenticated']::name[]
  ) then
    raise exception 'authenticated profile owner/public SELECT policy is missing';
  end if;
  if not exists (
    select 1 from pg_policies
    where schemaname = 'public'
      and tablename = 'vinz_profiles'
      and policyname = 'Vinz profiles update own profile'
      and cmd = 'UPDATE'
      and roles @> array['authenticated']::name[]
  ) then
    raise exception 'authenticated profile owner UPDATE policy is missing';
  end if;

  if not has_column_privilege('anon', 'public.vinz_share_links', 'external_url', 'SELECT') then
    raise exception 'anon must read approved public share-link columns';
  end if;
  if has_column_privilege('anon', 'public.vinz_share_links', 'user_id', 'SELECT') then
    raise exception 'anon must not read share-link owner IDs';
  end if;
  -- This RPC intentionally uses SECURITY DEFINER to check global username uniqueness
  -- without granting authenticated users read access to other users' private profiles.
  -- Keep it executable only by authenticated users and pin its search_path to empty.
  if has_function_privilege('anon', 'public.vinz_username_available(text)', 'EXECUTE') then
    raise exception 'anon must not execute username availability RPC';
  end if;
  if not has_function_privilege('authenticated', 'public.vinz_username_available(text)', 'EXECUTE') then
    raise exception 'authenticated must execute username availability RPC';
  end if;
  if not exists (
    select 1 from pg_proc p
    join pg_namespace n on n.oid = p.pronamespace
    where n.nspname = 'public'
      and p.proname = 'vinz_username_available'
      and p.prosecdef
      and coalesce(array_to_string(p.proconfig, ','), '') like '%search_path=""%'
  ) then
    raise exception 'username availability SECURITY DEFINER must use an empty search_path';
  end if;

  if not exists (
    select 1 from pg_policies
    where schemaname = 'public'
      and tablename = 'vinz_share_links'
      and policyname = 'Vinz share links public read valid'
      and cmd = 'SELECT'
      and roles @> array['anon']::name[]
  ) then
    raise exception 'public share-link expiry policy is missing';
  end if;
end;
$$;
