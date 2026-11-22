create table if not exists public.vinz_friend_requests (
  id uuid primary key default gen_random_uuid(),
  sender_id uuid not null references auth.users(id) on delete cascade,
  recipient_id uuid not null references auth.users(id) on delete cascade,
  status text not null default 'pending' check (status in ('pending','accepted','declined')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint vinz_friend_requests_not_self check (sender_id <> recipient_id),
  unique (sender_id, recipient_id)
);

create index if not exists vinz_friend_requests_recipient_status_idx
  on public.vinz_friend_requests (recipient_id, status, created_at desc);
create index if not exists vinz_friend_requests_sender_status_idx
  on public.vinz_friend_requests (sender_id, status, created_at desc);

alter table public.vinz_friend_requests enable row level security;

create policy "Vinz friend requests participant read"
  on public.vinz_friend_requests for select to authenticated
  using ((select auth.uid()) = sender_id or (select auth.uid()) = recipient_id);

create policy "Vinz friend requests sender create"
  on public.vinz_friend_requests for insert to authenticated
  with check ((select auth.uid()) = sender_id and status = 'pending');

create policy "Vinz friend requests recipient respond"
  on public.vinz_friend_requests for update to authenticated
  using ((select auth.uid()) = recipient_id and status = 'pending')
  with check ((select auth.uid()) = recipient_id and status in ('accepted','declined'));

create policy "Vinz friend requests sender cancel"
  on public.vinz_friend_requests for delete to authenticated
  using ((select auth.uid()) = sender_id and status = 'pending');

revoke all on table public.vinz_friend_requests from public, anon, authenticated;
grant select, insert, delete on public.vinz_friend_requests to authenticated;
grant update (status) on public.vinz_friend_requests to authenticated;
grant all on public.vinz_friend_requests to service_role;
