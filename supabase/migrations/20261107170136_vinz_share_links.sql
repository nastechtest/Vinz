create table if not exists public.vinz_share_links (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  provider text not null check (provider in ('youtube_music','apple_music','spotify','soundcloud','youtube','vinz')),
  external_url text not null,
  title text not null,
  artist text not null default '',
  artwork_url text,
  created_at timestamptz not null default now(),
  expires_at timestamptz,
  constraint vinz_share_links_title_length check (char_length(title) between 1 and 200),
  constraint vinz_share_links_artist_length check (char_length(artist) <= 200),
  constraint vinz_share_links_url_length check (char_length(external_url) <= 2048)
);

create index if not exists vinz_share_links_owner_created_idx
  on public.vinz_share_links (user_id, created_at desc);

alter table public.vinz_share_links enable row level security;

create policy "Vinz share links public read valid"
  on public.vinz_share_links for select to anon, authenticated
  using (expires_at is null or expires_at > now());

create policy "Vinz share links owner create"
  on public.vinz_share_links for insert to authenticated
  with check ((select auth.uid()) = user_id);

create policy "Vinz share links owner delete"
  on public.vinz_share_links for delete to authenticated
  using ((select auth.uid()) = user_id);

grant select on public.vinz_share_links to anon, authenticated;
grant insert, delete on public.vinz_share_links to authenticated;
grant all on public.vinz_share_links to service_role;
