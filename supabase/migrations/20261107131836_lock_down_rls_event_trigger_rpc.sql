-- Reconstructed from the live Vinz security configuration on 2026-11-07.

create schema vinz_private authorization postgres;
revoke all on schema vinz_private from public, anon, authenticated;

create or replace function vinz_private.set_updated_at()
returns trigger
language plpgsql
set search_path = ''
as $function$
begin
  new.updated_at := now();
  return new;
end;
$function$;

revoke all on function vinz_private.set_updated_at() from public, anon, authenticated, service_role;
grant execute on function vinz_private.set_updated_at() to postgres;

create trigger vinz_profiles_set_updated_at
before update on public.vinz_profiles
for each row execute function vinz_private.set_updated_at();

create trigger vinz_account_settings_set_updated_at
before update on public.vinz_account_settings
for each row execute function vinz_private.set_updated_at();

alter table public.vinz_profiles enable row level security;
alter table public.vinz_account_settings enable row level security;

create policy "Vinz profiles readable by owner or public"
  on public.vinz_profiles for select to authenticated
  using ((select auth.uid()) = id or is_public = true);

create policy "Vinz profiles update own profile"
  on public.vinz_profiles for update to authenticated
  using ((select auth.uid()) = id)
  with check ((select auth.uid()) = id);

create policy "Vinz account settings read own"
  on public.vinz_account_settings for select to authenticated
  using ((select auth.uid()) = user_id);

create policy "Vinz account settings update own"
  on public.vinz_account_settings for update to authenticated
  using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);

grant select on public.vinz_profiles to authenticated;
grant update (username, display_name, bio, avatar_path, is_public, discoverable) on public.vinz_profiles to authenticated;
grant select on public.vinz_account_settings to authenticated;
grant update (allow_friend_requests, show_listening_activity, notify_new_releases, notify_friend_activity, notify_podcast_updates) on public.vinz_account_settings to authenticated;
grant all on public.vinz_profiles to service_role;
grant all on public.vinz_account_settings to service_role;

-- Automatically enable RLS on future application tables created in public.
create or replace function public.rls_auto_enable()
returns event_trigger
language plpgsql
security definer
set search_path = 'pg_catalog'
as $function$
declare
  cmd record;
  tbl name;
begin
  for cmd in
    select *
    from pg_event_trigger_ddl_commands()
    where command_tag in ('CREATE TABLE', 'CREATE TABLE AS', 'SELECT INTO')
      and object_type in ('table', 'partitioned table')
  loop
    if cmd.schema_name is not null
       and cmd.schema_name in ('public')
       and cmd.schema_name not in ('pg_catalog', 'information_schema')
       and cmd.schema_name not like 'pg_toast%'
       and cmd.schema_name not like 'pg_temp%' then
      begin
        select c.relname into tbl
        from pg_catalog.pg_class c
        where c.oid = cmd.objid;
        execute format('alter table if exists %I.%I enable row level security', cmd.schema_name, tbl);
        raise log 'rls_auto_enable: enabled RLS on %', cmd.object_identity;
      exception when others then
        raise log 'rls_auto_enable: failed to enable RLS on %', cmd.object_identity;
      end;
    else
      raise log 'rls_auto_enable: skip % (either system schema or not in enforced list: %.)',
        cmd.object_identity, cmd.schema_name;
    end if;
  end loop;
end;
$function$;

revoke all on function public.rls_auto_enable() from public, anon, authenticated, service_role;
grant execute on function public.rls_auto_enable() to postgres;

create event trigger ensure_rls
on ddl_command_end
when tag in ('CREATE TABLE', 'CREATE TABLE AS', 'SELECT INTO')
execute function public.rls_auto_enable();
