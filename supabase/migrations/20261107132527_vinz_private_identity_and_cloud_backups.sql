-- Reconstructed from the live Vinz private identity and backup schema on 2026-11-07.

create table public.vinz_account_private (
  user_id uuid primary key references auth.users(id) on delete cascade,
  full_name text not null default '',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint vinz_account_private_full_name_length check (char_length(full_name) <= 120)
);

create trigger vinz_account_private_set_updated_at
before update on public.vinz_account_private
for each row execute function vinz_private.set_updated_at();

comment on table public.vinz_account_private is
  'Private account details. Only the owning authenticated user can read or update this row.';

create table public.vinz_backups (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  object_path text not null unique,
  byte_size bigint not null,
  sha256 text not null,
  format_version integer not null,
  app_version text not null default '',
  backup_scope text not null default 'full',
  created_at timestamptz not null default now(),
  constraint vinz_backups_byte_size_check check (byte_size > 0 and byte_size <= 52428800),
  constraint vinz_backups_sha256_check check (sha256 ~ '^[a-f0-9]{64}$'),
  constraint vinz_backups_format_version_check check (format_version > 0),
  constraint vinz_backups_backup_scope_check check (backup_scope = any (array['full','library','settings','history']))
);

create index vinz_backups_owner_created_idx
  on public.vinz_backups (user_id, created_at desc);

comment on table public.vinz_backups is
  'Backup metadata only. Backup objects are stored separately and are owned by one authenticated user.';

alter table public.vinz_account_private enable row level security;
alter table public.vinz_backups enable row level security;

create policy "Vinz private account read own"
  on public.vinz_account_private for select to authenticated
  using ((select auth.uid()) = user_id);

create policy "Vinz private account update own"
  on public.vinz_account_private for update to authenticated
  using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);

create policy "Vinz backups create own"
  on public.vinz_backups for insert to authenticated
  with check ((select auth.uid()) = user_id);

create policy "Vinz backups read own"
  on public.vinz_backups for select to authenticated
  using ((select auth.uid()) = user_id);

create policy "Vinz backups delete own"
  on public.vinz_backups for delete to authenticated
  using ((select auth.uid()) = user_id);

grant select on public.vinz_account_private to authenticated;
grant update (full_name) on public.vinz_account_private to authenticated;
grant select, insert, delete on public.vinz_backups to authenticated;
grant all on public.vinz_account_private to service_role;
grant all on public.vinz_backups to service_role;

create or replace function vinz_private.handle_new_auth_user()
returns trigger
language plpgsql
security definer
set search_path = ''
as $function$
declare
  generated_username text;
  chosen_name text;
  chosen_full_name text;
begin
  generated_username := 'listener_' || substr(replace(new.id::text, '-', ''), 1, 12);
  chosen_name := left(
    coalesce(
      nullif(btrim(new.raw_user_meta_data ->> 'full_name'), ''),
      nullif(btrim(new.raw_user_meta_data ->> 'name'), ''),
      'Listener'
    ),
    60
  );
  chosen_full_name := left(
    coalesce(
      nullif(btrim(new.raw_user_meta_data ->> 'full_name'), ''),
      nullif(btrim(new.raw_user_meta_data ->> 'name'), ''),
      ''
    ),
    120
  );

  insert into public.vinz_profiles (id, username, display_name)
  values (new.id, generated_username, chosen_name)
  on conflict (id) do nothing;

  insert into public.vinz_account_settings (user_id)
  values (new.id)
  on conflict (user_id) do nothing;

  insert into public.vinz_account_private (user_id, full_name)
  values (new.id, chosen_full_name)
  on conflict (user_id) do nothing;

  return new;
end;
$function$;

revoke all on function vinz_private.handle_new_auth_user() from public, anon, authenticated, service_role;
grant execute on function vinz_private.handle_new_auth_user() to postgres;

create trigger on_vinz_auth_user_created
after insert on auth.users
for each row execute function vinz_private.handle_new_auth_user();
