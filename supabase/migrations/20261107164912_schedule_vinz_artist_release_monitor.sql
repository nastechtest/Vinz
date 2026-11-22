create extension if not exists pg_cron;
create extension if not exists pg_net with schema extensions;

select vault.create_secret(
  'https://iszghebmetrbkfhnnoqq.supabase.co',
  'vinz_project_url',
  'Supabase project URL used by scheduled Vinz discovery'
);

select vault.create_secret(
  'sb_publishable_9UXwKG0oVl1J9zIkJ-bZKg_W-fkotZe',
  'vinz_publishable_key',
  'Supabase publishable key used for the Edge Function gateway'
);

select cron.schedule(
  'vinz-artist-release-monitor',
  '*/15 * * * *',
  $job$
    select net.http_post(
      url := (select decrypted_secret from vault.decrypted_secrets where name = 'vinz_project_url')
             || '/functions/v1/vinz-discovery',
      headers := jsonb_build_object(
        'content-type', 'application/json',
        'apikey', (select decrypted_secret from vault.decrypted_secrets where name = 'vinz_publishable_key'),
        'x-vinz-cron-secret', (select decrypted_secret from vault.decrypted_secrets where name = 'vinz-discovery-cron')
      ),
      body := '{"kind":"refresh_artist_releases"}'::jsonb,
      timeout_milliseconds := 90000
    ) as request_id;
  $job$
);
