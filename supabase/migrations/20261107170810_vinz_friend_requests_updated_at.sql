create trigger vinz_friend_requests_set_updated_at
before update on public.vinz_friend_requests
for each row execute function vinz_private.set_updated_at();
