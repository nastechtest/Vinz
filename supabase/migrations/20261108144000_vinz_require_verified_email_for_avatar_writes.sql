-- Avatar objects are account-owned data: an unverified identity may not upload,
-- replace or delete an avatar even if it bypasses the Android OTP screen.
-- Public reads remain unchanged; the bucket's existing owner-folder isolation is preserved.

drop policy if exists "Vinz avatar owner insert" on storage.objects;
create policy "Vinz avatar owner insert"
  on storage.objects for insert to authenticated
  with check (
    vinz_private.is_email_verified()
    and bucket_id = 'vinz-avatars'
    and (storage.foldername(name))[1] = (select auth.uid())::text
  );

drop policy if exists "Vinz avatar owner update" on storage.objects;
create policy "Vinz avatar owner update"
  on storage.objects for update to authenticated
  using (
    vinz_private.is_email_verified()
    and bucket_id = 'vinz-avatars'
    and (storage.foldername(name))[1] = (select auth.uid())::text
  )
  with check (
    vinz_private.is_email_verified()
    and bucket_id = 'vinz-avatars'
    and (storage.foldername(name))[1] = (select auth.uid())::text
  );

drop policy if exists "Vinz avatar owner delete" on storage.objects;
create policy "Vinz avatar owner delete"
  on storage.objects for delete to authenticated
  using (
    vinz_private.is_email_verified()
    and bucket_id = 'vinz-avatars'
    and (storage.foldername(name))[1] = (select auth.uid())::text
  );
