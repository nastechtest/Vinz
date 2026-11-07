-- Vinz artist watches, podcast subscriptions and private notification delivery.
-- Additive migration: does not alter the existing profile or backup formats.

create table if not exists public.vinz_artist_watches (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  provider text not null check (provider in ('apple_itunes', 'musicbrainz')),
  provider_artist_id text not null,
  artist_name text not null,
  notify_new_releases boolean not null default true,
  last_checked_at timestamptz,
  last_seen_release_id text,
  last_seen_release_date date,
  created_at timestamptz not null default now(),
  unique (user_id, provider, provider_artist_id)
);

create index if not exists vinz_artist_watches_due_idx
  on public.vinz_artist_watches (notify_new_releases, last_checked_at)
  where notify_new_releases = true;

create table if not exists public.vinz_podcast_subscriptions (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  provider text not null check (provider in ('apple_itunes', 'podcast_index', 'rss')),
  provider_podcast_id text not null,
  feed_url text not null,
  title text not null,
  publisher text not null default '',
  notify_new_episodes boolean not null default true,
  last_checked_at timestamptz,
  last_seen_episode_guid text,
  created_at timestamptz not null default now(),
  unique (user_id, provider, provider_podcast_id)
);

create index if not exists vinz_podcast_subscriptions_due_idx
  on public.vinz_podcast_subscriptions (notify_new_episodes, last_checked_at)
  where notify_new_episodes = true;

create table if not exists public.vinz_notification_devices (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  platform text not null check (platform in ('android', 'ios', 'web')),
  push_token text not null,
  device_label text not null default '',
  last_seen_at timestamptz not null default now(),
  created_at timestamptz not null default now(),
  unique (user_id, push_token)
);

create index if not exists vinz_notification_devices_owner_idx
  on public.vinz_notification_devices (user_id);

create table if not exists public.vinz_notifications (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  kind text not null,
  title text not null,
  body text not null default '',
  deep_link text,
  payload jsonb not null default '{}'::jsonb,
  read_at timestamptz,
  created_at timestamptz not null default now()
);

create index if not exists vinz_notifications_inbox_idx
  on public.vinz_notifications (user_id, created_at desc);

alter table public.vinz_artist_watches enable row level security;
alter table public.vinz_podcast_subscriptions enable row level security;
alter table public.vinz_notification_devices enable row level security;
alter table public.vinz_notifications enable row level security;

drop policy if exists "Vinz artist watches own rows" on public.vinz_artist_watches;
create policy "Vinz artist watches own rows"
  on public.vinz_artist_watches for all to authenticated
  using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);

drop policy if exists "Vinz podcast subscriptions own rows" on public.vinz_podcast_subscriptions;
create policy "Vinz podcast subscriptions own rows"
  on public.vinz_podcast_subscriptions for all to authenticated
  using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);

drop policy if exists "Vinz notification devices own rows" on public.vinz_notification_devices;
create policy "Vinz notification devices own rows"
  on public.vinz_notification_devices for all to authenticated
  using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);

drop policy if exists "Vinz notifications read own rows" on public.vinz_notifications;
create policy "Vinz notifications read own rows"
  on public.vinz_notifications for select to authenticated
  using ((select auth.uid()) = user_id);

drop policy if exists "Vinz notifications update own rows" on public.vinz_notifications;
create policy "Vinz notifications update own rows"
  on public.vinz_notifications for update to authenticated
  using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);

drop policy if exists "Vinz notifications delete own rows" on public.vinz_notifications;
create policy "Vinz notifications delete own rows"
  on public.vinz_notifications for delete to authenticated
  using ((select auth.uid()) = user_id);
