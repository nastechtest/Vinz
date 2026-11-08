-- Account 2.0: canonical username enforcement, server-side availability throttling,
-- and a public-avatar bucket with owner-only writes.
create table if not exists vinz_private.username_availability_checks (
  user_id uuid not null references auth.users(id) on delete cascade,
  window_start timestamptz not null,
  attempts integer not null default 0 check (attempts >= 0),
  primary key (user_id, window_start)
);
revoke all on vinz_private.username_availability_checks from public, anon, authenticated, service_role;

create or replace function vinz_private.enforce_vinz_username()
returns trigger language plpgsql security definer set search_path = '' as $function$
declare
  normalized text;
  reserved constant text[] := array['admin','administrator','api','account','accounts','auth','billing','community','discover','help','login','logout','moderator','null','official','privacy','profile','root','search','security','settings','signup','staff','status','support','system','terms','undefined','vinz','webmaster','www'];
begin
  normalized := pg_catalog.lower(pg_catalog.btrim(new.username));
  if normalized !~ '^[a-z0-9_]{3,24}$' then
    raise exception using errcode = '23514', constraint = 'vinz_profiles_username_format', message = 'invalid_username';
  end if;
  if normalized = any (reserved) then
    raise exception using errcode = '23514', constraint = 'vinz_profiles_username_reserved', message = 'reserved_username';
  end if;
  new.username := normalized;
  return new;
end;
$function$;
revoke all on function vinz_private.enforce_vinz_username() from public, anon, authenticated, service_role;
grant execute on function vinz_private.enforce_vinz_username() to postgres;
drop trigger if exists vinz_profiles_enforce_username on public.vinz_profiles;
create trigger vinz_profiles_enforce_username before insert or update of username on public.vinz_profiles for each row execute function vinz_private.enforce_vinz_username();

create or replace function public.vinz_username_available(p_username text)
returns boolean language plpgsql volatile security definer set search_path = '' as $function$
declare
  normalized text;
  attempts_now integer;
  reserved constant text[] := array['admin','administrator','api','account','accounts','auth','billing','community','discover','help','login','logout','moderator','null','official','privacy','profile','root','search','security','settings','signup','staff','status','support','system','terms','undefined','vinz','webmaster','www'];
begin
  if auth.uid() is null then return false; end if;
  normalized := pg_catalog.lower(pg_catalog.btrim(coalesce(p_username, '')));
  if normalized !~ '^[a-z0-9_]{3,24}$' or normalized = any (reserved) then return false; end if;
  insert into vinz_private.username_availability_checks(user_id, window_start, attempts)
  values (auth.uid(), pg_catalog.date_trunc('minute', pg_catalog.clock_timestamp()), 1)
  on conflict (user_id, window_start) do update
    set attempts = vinz_private.username_availability_checks.attempts + 1
  returning attempts into attempts_now;
  if attempts_now > 30 then return false; end if;
  return not exists (select 1 from public.vinz_profiles p where pg_catalog.lower(p.username) = normalized);
end;
$function$;
revoke all on function public.vinz_username_available(text) from public, anon;
grant execute on function public.vinz_username_available(text) to authenticated;

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('vinz-avatars', 'vinz-avatars', true, 5242880, array['image/jpeg','image/png','image/webp'])
on conflict (id) do update set public=excluded.public, file_size_limit=excluded.file_size_limit, allowed_mime_types=excluded.allowed_mime_types;

do $policies$
begin
  if not exists (select 1 from pg_policies where schemaname='storage' and tablename='objects' and policyname='Vinz avatar public read') then
    create policy "Vinz avatar public read" on storage.objects for select to public using (bucket_id='vinz-avatars');
  end if;
  if not exists (select 1 from pg_policies where schemaname='storage' and tablename='objects' and policyname='Vinz avatar owner insert') then
    create policy "Vinz avatar owner insert" on storage.objects for insert to authenticated with check (bucket_id='vinz-avatars' and (storage.foldername(name))[1]=(select auth.uid())::text);
  end if;
  if not exists (select 1 from pg_policies where schemaname='storage' and tablename='objects' and policyname='Vinz avatar owner update') then
    create policy "Vinz avatar owner update" on storage.objects for update to authenticated using (bucket_id='vinz-avatars' and (storage.foldername(name))[1]=(select auth.uid())::text) with check (bucket_id='vinz-avatars' and (storage.foldername(name))[1]=(select auth.uid())::text);
  end if;
  if not exists (select 1 from pg_policies where schemaname='storage' and tablename='objects' and policyname='Vinz avatar owner delete') then
    create policy "Vinz avatar owner delete" on storage.objects for delete to authenticated using (bucket_id='vinz-avatars' and (storage.foldername(name))[1]=(select auth.uid())::text);
  end if;
end;
$policies$;
