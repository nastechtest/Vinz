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
