-- Reconstructed from the live Vinz schema on 2026-11-07.
-- This baseline restores reproducible account/profile tables for clean environments.

create table public.vinz_profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  username text not null,
  display_name text not null default 'Listener',
  bio text not null default '',
  avatar_path text,
  is_public boolean not null default true,
  discoverable boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint vinz_profiles_username_format check (username ~ '^[a-z0-9_]{3,24}$'),
  constraint vinz_profiles_display_name_length check (char_length(display_name) >= 1 and char_length(display_name) <= 60),
  constraint vinz_profiles_bio_check check (char_length(bio) <= 160),
  constraint vinz_profiles_avatar_path_length check (avatar_path is null or char_length(avatar_path) <= 512)
);

create unique index vinz_profiles_username_lower_uidx
  on public.vinz_profiles (lower(username));

create index vinz_profiles_discoverable_idx
  on public.vinz_profiles (lower(username))
  where is_public = true and discoverable = true;

comment on table public.vinz_profiles is
  'Vinz cloud identity profile. Public fields only; private preferences belong in vinz_account_settings.';
comment on column public.vinz_profiles.discoverable is
  'When false, omit this public profile from community search and discovery results.';

create table public.vinz_account_settings (
  user_id uuid primary key references auth.users(id) on delete cascade,
  allow_friend_requests boolean not null default true,
  show_listening_activity boolean not null default false,
  notify_new_releases boolean not null default false,
  notify_friend_activity boolean not null default true,
  notify_podcast_updates boolean not null default false,
  updated_at timestamptz not null default now()
);

comment on table public.vinz_account_settings is
  'Private preferences for a Vinz identity. A user can read and update only their own row.';
