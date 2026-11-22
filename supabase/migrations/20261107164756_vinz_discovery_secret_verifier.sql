create or replace function public.vinz_verify_discovery_secret(p_secret text)
returns boolean
language sql
security definer
set search_path = ''
as $function$
  select exists (
    select 1
    from vault.decrypted_secrets
    where name = 'vinz-discovery-cron'
      and decrypted_secret = p_secret
  );
$function$;

revoke all on function public.vinz_verify_discovery_secret(text) from public, anon, authenticated;
grant execute on function public.vinz_verify_discovery_secret(text) to service_role;
