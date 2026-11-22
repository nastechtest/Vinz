-- Tighten table and column grants after checking Supabase default privileges.
revoke all on table public.vinz_artist_watches from public, anon, authenticated;
revoke all on table public.vinz_podcast_subscriptions from public, anon, authenticated;
revoke all on table public.vinz_notification_devices from public, anon, authenticated;
revoke all on table public.vinz_notifications from public, anon, authenticated;
revoke all on table public.vinz_share_links from public, anon, authenticated;

grant select, insert, update, delete on public.vinz_artist_watches to authenticated;
grant select, insert, update, delete on public.vinz_podcast_subscriptions to authenticated;
grant select, insert, update, delete on public.vinz_notification_devices to authenticated;

grant select, delete on public.vinz_notifications to authenticated;
grant update (read_at) on public.vinz_notifications to authenticated;

grant select (id, provider, external_url, title, artist, artwork_url, created_at, expires_at)
  on public.vinz_share_links to anon, authenticated;
grant insert, delete on public.vinz_share_links to authenticated;

grant all on public.vinz_artist_watches to service_role;
grant all on public.vinz_podcast_subscriptions to service_role;
grant all on public.vinz_notification_devices to service_role;
grant all on public.vinz_notifications to service_role;
grant all on public.vinz_share_links to service_role;
