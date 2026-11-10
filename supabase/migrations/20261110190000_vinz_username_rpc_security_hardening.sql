-- Public RPCs are invoker-only. Privileged lookups stay in the non-exposed private schema,
-- with narrowly granted execution on the exact boolean-only helper.
grant usage on schema vinz_private to anon, authenticated;

create or replace function vinz_private.vinz_username_available_for_signup(p_username text)
returns boolean
language sql
stable
security definer
set search_path = ''
as $function$
  select case
    when p_username is null
      or pg_catalog.lower(pg_catalog.btrim(p_username)) !~ '^[a-z0-9_]{3,24}$'
      or pg_catalog.lower(pg_catalog.btrim(p_username)) = any (array[
        'admin','administrator','api','account','accounts','auth','billing','community','discover',
        'help','login','logout','moderator','null','official','privacy','profile','root','search',
        'security','settings','signup','staff','status','support','system','terms','undefined','vinz',
        'webmaster','www'
      ]::text[])
    then false
    else not exists (
      select 1
      from public.vinz_profiles
      where pg_catalog.lower(username) = pg_catalog.lower(pg_catalog.btrim(p_username))
    )
  end;
$function$;

revoke all on function vinz_private.vinz_username_available_for_signup(text)
  from public, anon, authenticated, service_role;
grant execute on function vinz_private.vinz_username_available_for_signup(text) to anon;

create or replace function public.vinz_username_available_for_signup(p_username text)
returns boolean
language sql
stable
security invoker
set search_path = ''
as $function$
  select vinz_private.vinz_username_available_for_signup(p_username);
$function$;

revoke all on function public.vinz_username_available_for_signup(text)
  from public, anon, authenticated, service_role;
grant execute on function public.vinz_username_available_for_signup(text) to anon;

-- Replace the legacy SECURITY DEFINER public RPC with the already throttled private helper.
create or replace function public.vinz_username_available(p_username text)
returns boolean
language sql
volatile
security invoker
set search_path = ''
as $function$
  select vinz_private.vinz_username_available(p_username);
$function$;

revoke all on function public.vinz_username_available(text)
  from public, anon, service_role;
grant execute on function public.vinz_username_available(text) to authenticated;

comment on function public.vinz_username_available_for_signup(text) is
  'Invoker-only public wrapper for a private boolean-only signup availability check.';
comment on function public.vinz_username_available(text) is
  'Invoker-only authenticated wrapper for the rate-limited private username availability check.';
