-- Public profiles are intentionally readable without signing in, but only their public projection.
-- Private full names and account preferences remain in separate owner-only tables.
revoke all on table public.vinz_profiles from anon;

grant select (username, display_name, bio, avatar_path, is_public)
  on table public.vinz_profiles to anon;

drop policy if exists "Vinz public profiles read public" on public.vinz_profiles;
create policy "Vinz public profiles read public"
  on public.vinz_profiles
  for select
  to anon
  using (is_public = true);

comment on policy "Vinz public profiles read public" on public.vinz_profiles is
  'Anonymous reads can see only rows explicitly marked public; column grants exclude account identifiers and private fields.';
