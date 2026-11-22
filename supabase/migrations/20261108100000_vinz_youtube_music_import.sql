-- Secure YouTube Music import connection and imported playlist metadata.
-- OAuth access/refresh tokens are AES-GCM encrypted by the Cloudflare API Worker.
create table if not exists public.vinz_youtube_connections (
  user_id uuid primary key references auth.users(id) on delete cascade,
  channel_id text,
  channel_title text not null default '',
  encrypted_access_token text not null,
  encrypted_refresh_token text,
  access_token_expires_at timestamptz not null,
  scopes text[] not null default '{}',
  connected_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint vinz_youtube_channel_id_length check (channel_id is null or char_length(channel_id) <= 128),
  constraint vinz_youtube_channel_title_length check (char_length(channel_title) <= 200)
);
alter table public.vinz_youtube_connections enable row level security;
drop policy if exists "Vinz YouTube connection owner access" on public.vinz_youtube_connections;
create policy "Vinz YouTube connection owner access"
  on public.vinz_youtube_connections for all to authenticated
  using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);
revoke all on public.vinz_youtube_connections from public, anon;
grant select, insert, update, delete on public.vinz_youtube_connections to authenticated;
comment on table public.vinz_youtube_connections is
  'Per-user YouTube OAuth connection. Provider tokens are encrypted at rest by the Cloudflare API Worker; RLS restricts rows to the owner.';

create table if not exists public.vinz_youtube_imports (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  playlist_id text not null,
  playlist_title text not null default '',
  video_id text not null,
  title text not null,
  artist text not null default '',
  thumbnail_url text,
  published_at timestamptz,
  imported_at timestamptz not null default now(),
  constraint vinz_youtube_import_playlist_id_length check (char_length(playlist_id) between 1 and 128),
  constraint vinz_youtube_import_video_id_length check (char_length(video_id) between 1 and 128),
  constraint vinz_youtube_import_title_length check (char_length(title) between 1 and 200),
  constraint vinz_youtube_import_artist_length check (char_length(artist) <= 200),
  constraint vinz_youtube_import_thumbnail_https check (thumbnail_url is null or thumbnail_url like 'https://i.ytimg.com/%'),
  unique (user_id, playlist_id, video_id)
);
create index if not exists vinz_youtube_imports_user_recent_idx
  on public.vinz_youtube_imports (user_id, imported_at desc);
alter table public.vinz_youtube_imports enable row level security;
drop policy if exists "Vinz YouTube imports owner access" on public.vinz_youtube_imports;
create policy "Vinz YouTube imports owner access"
  on public.vinz_youtube_imports for all to authenticated
  using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);
revoke all on public.vinz_youtube_imports from public, anon;
grant select, insert, update, delete on public.vinz_youtube_imports to authenticated;
comment on table public.vinz_youtube_imports is
  'Imported YouTube playlist/video metadata owned by a Vinz account. It does not copy or proxy audio.';
