create or replace function public.vinz_username_available(p_username text)
returns boolean
language sql
stable
security definer
set search_path = ''
as $function$
  select case
    when p_username is null or p_username !~ '^[a-z0-9_]{3,24}$' then false
    else not exists (
      select 1
      from public.vinz_profiles
      where lower(username) = lower(p_username)
    )
  end;
$function$;

revoke all on function public.vinz_username_available(text) from public, anon;
grant execute on function public.vinz_username_available(text) to authenticated, service_role;
