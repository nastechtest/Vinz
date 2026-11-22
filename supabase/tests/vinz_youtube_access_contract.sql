-- Run after applying 20261108100000_vinz_youtube_music_import.sql.
-- Ensures provider credentials and imported metadata are private to the owning Vinz user.
do $$
begin
  if not exists (
    select 1 from pg_class c join pg_namespace n on n.oid=c.relnamespace
    where n.nspname='public' and c.relname='vinz_youtube_connections' and c.relrowsecurity
  ) then raise exception 'YouTube connection table must have RLS enabled'; end if;
  if not exists (
    select 1 from pg_class c join pg_namespace n on n.oid=c.relnamespace
    where n.nspname='public' and c.relname='vinz_youtube_imports' and c.relrowsecurity
  ) then raise exception 'YouTube import table must have RLS enabled'; end if;
  if has_table_privilege('anon','public.vinz_youtube_connections','SELECT') then
    raise exception 'anon must not read encrypted YouTube tokens';
  end if;
  if has_table_privilege('anon','public.vinz_youtube_imports','SELECT') then
    raise exception 'anon must not read imported YouTube metadata';
  end if;
  if not has_table_privilege('authenticated','public.vinz_youtube_connections','SELECT') then
    raise exception 'authenticated must read its own encrypted YouTube connection row';
  end if;
  if not has_table_privilege('authenticated','public.vinz_youtube_imports','INSERT') then
    raise exception 'authenticated must insert its own imported YouTube metadata';
  end if;
  if not exists (
    select 1 from pg_policies where schemaname='public' and tablename='vinz_youtube_connections'
      and policyname='Vinz YouTube connection owner access' and cmd='ALL'
      and roles @> array['authenticated']::name[]
  ) then raise exception 'YouTube connection owner policy is missing'; end if;
  if not exists (
    select 1 from pg_policies where schemaname='public' and tablename='vinz_youtube_imports'
      and policyname='Vinz YouTube imports owner access' and cmd='ALL'
      and roles @> array['authenticated']::name[]
  ) then raise exception 'YouTube import owner policy is missing'; end if;
end;
$$;
