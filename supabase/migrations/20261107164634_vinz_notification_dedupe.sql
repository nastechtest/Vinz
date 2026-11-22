alter table public.vinz_notifications
  add column if not exists dedupe_key text;

create unique index if not exists vinz_notifications_user_dedupe_idx
  on public.vinz_notifications (user_id, dedupe_key)
  where dedupe_key is not null;
