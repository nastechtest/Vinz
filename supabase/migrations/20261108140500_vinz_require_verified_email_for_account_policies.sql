-- Require a confirmed email for Vinz account-owned data even if a client bypasses the Android UI.
-- Public share-link reads remain public by design; creating/deleting a link requires a verified owner.

grant usage on schema vinz_private to authenticated;

create or replace function vinz_private.is_email_verified()
returns boolean
language sql
stable
security definer
set search_path = ''
as $function$
  select exists (
    select 1
    from auth.users u
    where u.id = auth.uid()
      and u.email_confirmed_at is not null
  );
$function$;

revoke all on function vinz_private.is_email_verified() from public, anon, authenticated, service_role;
grant execute on function vinz_private.is_email_verified() to authenticated;

drop policy if exists "Vinz profiles readable by owner or public" on public.vinz_profiles;
create policy "Vinz profiles readable by owner or public"
  on public.vinz_profiles for select to authenticated
  using (vinz_private.is_email_verified() and ((select auth.uid()) = id or is_public = true));

drop policy if exists "Vinz profiles update own profile" on public.vinz_profiles;
create policy "Vinz profiles update own profile"
  on public.vinz_profiles for update to authenticated
  using (vinz_private.is_email_verified() and (select auth.uid()) = id)
  with check (vinz_private.is_email_verified() and (select auth.uid()) = id);

drop policy if exists "Vinz account settings read own" on public.vinz_account_settings;
create policy "Vinz account settings read own"
  on public.vinz_account_settings for select to authenticated
  using (vinz_private.is_email_verified() and (select auth.uid()) = user_id);

drop policy if exists "Vinz account settings update own" on public.vinz_account_settings;
create policy "Vinz account settings update own"
  on public.vinz_account_settings for update to authenticated
  using (vinz_private.is_email_verified() and (select auth.uid()) = user_id)
  with check (vinz_private.is_email_verified() and (select auth.uid()) = user_id);

drop policy if exists "Vinz private account read own" on public.vinz_account_private;
create policy "Vinz private account read own"
  on public.vinz_account_private for select to authenticated
  using (vinz_private.is_email_verified() and (select auth.uid()) = user_id);

drop policy if exists "Vinz private account update own" on public.vinz_account_private;
create policy "Vinz private account update own"
  on public.vinz_account_private for update to authenticated
  using (vinz_private.is_email_verified() and (select auth.uid()) = user_id)
  with check (vinz_private.is_email_verified() and (select auth.uid()) = user_id);

drop policy if exists "Vinz backups create own" on public.vinz_backups;
create policy "Vinz backups create own"
  on public.vinz_backups for insert to authenticated
  with check (vinz_private.is_email_verified() and (select auth.uid()) = user_id);

drop policy if exists "Vinz backups read own" on public.vinz_backups;
create policy "Vinz backups read own"
  on public.vinz_backups for select to authenticated
  using (vinz_private.is_email_verified() and (select auth.uid()) = user_id);

drop policy if exists "Vinz backups delete own" on public.vinz_backups;
create policy "Vinz backups delete own"
  on public.vinz_backups for delete to authenticated
  using (vinz_private.is_email_verified() and (select auth.uid()) = user_id);

drop policy if exists "Vinz backup objects upload own" on storage.objects;
create policy "Vinz backup objects upload own"
  on storage.objects for insert to authenticated
  with check (
    vinz_private.is_email_verified()
    and bucket_id = 'vinz-cloud-backups'
    and (storage.foldername(name))[1] = (select auth.uid())::text
  );

drop policy if exists "Vinz backup objects read own" on storage.objects;
create policy "Vinz backup objects read own"
  on storage.objects for select to authenticated
  using (
    vinz_private.is_email_verified()
    and bucket_id = 'vinz-cloud-backups'
    and (storage.foldername(name))[1] = (select auth.uid())::text
  );

drop policy if exists "Vinz backup objects delete own" on storage.objects;
create policy "Vinz backup objects delete own"
  on storage.objects for delete to authenticated
  using (
    vinz_private.is_email_verified()
    and bucket_id = 'vinz-cloud-backups'
    and (storage.foldername(name))[1] = (select auth.uid())::text
  );

drop policy if exists "Vinz artist watches own rows" on public.vinz_artist_watches;
create policy "Vinz artist watches own rows"
  on public.vinz_artist_watches for all to authenticated
  using (vinz_private.is_email_verified() and (select auth.uid()) = user_id)
  with check (vinz_private.is_email_verified() and (select auth.uid()) = user_id);

drop policy if exists "Vinz podcast subscriptions own rows" on public.vinz_podcast_subscriptions;
create policy "Vinz podcast subscriptions own rows"
  on public.vinz_podcast_subscriptions for all to authenticated
  using (vinz_private.is_email_verified() and (select auth.uid()) = user_id)
  with check (vinz_private.is_email_verified() and (select auth.uid()) = user_id);

drop policy if exists "Vinz notification devices own rows" on public.vinz_notification_devices;
create policy "Vinz notification devices own rows"
  on public.vinz_notification_devices for all to authenticated
  using (vinz_private.is_email_verified() and (select auth.uid()) = user_id)
  with check (vinz_private.is_email_verified() and (select auth.uid()) = user_id);

drop policy if exists "Vinz notifications read own rows" on public.vinz_notifications;
create policy "Vinz notifications read own rows"
  on public.vinz_notifications for select to authenticated
  using (vinz_private.is_email_verified() and (select auth.uid()) = user_id);

drop policy if exists "Vinz notifications update own rows" on public.vinz_notifications;
create policy "Vinz notifications update own rows"
  on public.vinz_notifications for update to authenticated
  using (vinz_private.is_email_verified() and (select auth.uid()) = user_id)
  with check (vinz_private.is_email_verified() and (select auth.uid()) = user_id);

drop policy if exists "Vinz notifications delete own rows" on public.vinz_notifications;
create policy "Vinz notifications delete own rows"
  on public.vinz_notifications for delete to authenticated
  using (vinz_private.is_email_verified() and (select auth.uid()) = user_id);

drop policy if exists "Vinz friend requests participant read" on public.vinz_friend_requests;
create policy "Vinz friend requests participant read"
  on public.vinz_friend_requests for select to authenticated
  using (vinz_private.is_email_verified() and ((select auth.uid()) = sender_id or (select auth.uid()) = recipient_id));

drop policy if exists "Vinz friend requests sender create" on public.vinz_friend_requests;
create policy "Vinz friend requests sender create"
  on public.vinz_friend_requests for insert to authenticated
  with check (vinz_private.is_email_verified() and (select auth.uid()) = sender_id and status = 'pending');

drop policy if exists "Vinz friend requests recipient respond" on public.vinz_friend_requests;
create policy "Vinz friend requests recipient respond"
  on public.vinz_friend_requests for update to authenticated
  using (vinz_private.is_email_verified() and (select auth.uid()) = recipient_id and status = 'pending')
  with check (vinz_private.is_email_verified() and (select auth.uid()) = recipient_id and status in ('accepted', 'declined'));

drop policy if exists "Vinz friend requests sender cancel" on public.vinz_friend_requests;
create policy "Vinz friend requests sender cancel"
  on public.vinz_friend_requests for delete to authenticated
  using (vinz_private.is_email_verified() and (select auth.uid()) = sender_id and status = 'pending');

drop policy if exists "Vinz share links owner create" on public.vinz_share_links;
create policy "Vinz share links owner create"
  on public.vinz_share_links for insert to authenticated
  with check (vinz_private.is_email_verified() and (select auth.uid()) = user_id);

drop policy if exists "Vinz share links owner delete" on public.vinz_share_links;
create policy "Vinz share links owner delete"
  on public.vinz_share_links for delete to authenticated
  using (vinz_private.is_email_verified() and (select auth.uid()) = user_id);

drop policy if exists "Vinz YouTube connection owner access" on public.vinz_youtube_connections;
create policy "Vinz YouTube connection owner access"
  on public.vinz_youtube_connections for all to authenticated
  using (vinz_private.is_email_verified() and (select auth.uid()) = user_id)
  with check (vinz_private.is_email_verified() and (select auth.uid()) = user_id);

drop policy if exists "Vinz YouTube imports owner access" on public.vinz_youtube_imports;
create policy "Vinz YouTube imports owner access"
  on public.vinz_youtube_imports for all to authenticated
  using (vinz_private.is_email_verified() and (select auth.uid()) = user_id)
  with check (vinz_private.is_email_verified() and (select auth.uid()) = user_id);
