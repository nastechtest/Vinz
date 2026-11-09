-- Signup needs a pre-auth username check. This function exposes only a boolean and
-- never returns a user ID, email address, profile row or private account metadata.
-- The Cloudflare Worker adds a per-IP edge rate limit; the unique lower(username) index
-- remains the final atomic authority when a profile is inserted or updated.

create unique index if not exists vinz_profiles_username_lower_uidx
  on public.vinz_profiles using btree (lower(username));

create or replace function public.vinz_username_available_for_signup(p_username text)
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

revoke all on function public.vinz_username_available_for_signup(text) from public, anon, authenticated, service_role;
grant execute on function public.vinz_username_available_for_signup(text) to anon;

comment on function public.vinz_username_available_for_signup(text) is
  'Returns only whether a canonical Vinz username is available for signup; no account identifiers or email data are exposed.';
