# Vinz backend platform

This repository is a native Android application. The backend work is isolated under `platform/cloudflare`, `supabase/functions`, `supabase/migrations`, and `docs`. No Android screens or local music-library behavior have been changed.

## Service ownership

| Service | Responsibility |
|---|---|
| Supabase | Auth, profile/settings data, RLS, backup metadata, notification records, scheduled discovery Edge Function |
| Cloudflare Workers (API) | Authenticated edge API, validation, provider routing and privacy-safe error telemetry |
| Cloudflare Workers (web) | Vinz web frontend built with vinext; production hostname `home.vinzmusic.workers.dev` |
| Cloudflare R2 | Opaque user backup objects |
| Cloudflare KV | Shared cache for public catalog metadata/artwork |
| Cloudflare Queues | Reserved for future bursty discovery/notification fan-out; queues exist but have no consumer yet |
| PostHog | Allowlisted backend operational error events; never listening history or raw music content |
| Linear | Workstreams, blockers, acceptance criteria and release tracking |
| Vercel | Reserved for an actual web control-plane app. The current repo is Android-only and no Vinz Vercel project is linked. Android builds stay in GitHub Actions. |

Using several providers does not increase any provider's quota. The purpose is to keep each service on the workload it handles efficiently and to make fallbacks explicit.

## Verified live infrastructure (2026-11-07)

### Supabase
- Project `iszghebmetrbkfhnnoqq`, region `eu-west-1`.
- The original three migration versions were reconstructed as equivalent baseline migrations from the live schema and added to the repository.
- Additive migrations are applied for artist watches, podcast subscriptions, private notification devices/inbox, notification deduplication, a Vault-backed scheduler verifier, a privacy-safe username availability RPC, and the release-monitor schedule.
- `pg_cron`, `pg_net`, and Supabase Vault are enabled.
- Edge Function `vinz-discovery` is active (version 5).
- Cron job `vinz-artist-release-monitor` is active every 15 minutes.
- A manual scheduled-function invocation returned HTTP 200 with zero failures. It found zero due artist watches and podcast subscriptions during the test, so no test notifications were fabricated.
- Existing auth-user trigger creates the profile, settings, and private account rows. Username uniqueness is enforced by a case-insensitive unique index.

### Cloudflare
- Existing production Worker `vinz` remains the original Hello World script; it has not been replaced.
- A separate `staging` Worker is deployed with the backend API source, Supabase public bindings, the `vinz-backups` R2 binding, the `VINZ_CATALOG_CACHE` KV binding, PostHog secret binding, and observability enabled.
- R2 bucket `vinz-backups` exists in EEUR.
- KV namespace `VINZ_CATALOG_CACHE` exists in EU.
- Queues `vinz-discovery-jobs` and `vinz-discovery-jobs-dlq` exist in EU, but no queue consumer is configured; the initial release-monitor schedule runs through Supabase Cron + Vault instead.

### GitHub and PostHog
- Draft PR #38 contains the backend source, migrations, Edge Function, documentation and CI tests.
- The dedicated Worker test workflow passed on the checked commit. Existing Android regression/PR checks are separate and must finish before merge.
- PostHog is wired into staging for a fixed, allowlisted `vinz_api_error` event. It receives no user IDs, emails, tokens, queries, artist/track names, lyrics, audio or request bodies.

### Web hosting
- The web frontend is hosted on Cloudflare Workers at `https://home.vinzmusic.workers.dev`.
- The API is hosted separately at `https://api.vinzmusic.workers.dev`.
- Vercel is not part of Vinz's hosting, CI, preview, or release path.

## Cloudflare API contract

All `/v1/*` routes require a Supabase access token in `Authorization: Bearer <token>`. The Worker validates it through Supabase Auth and passes the same user token to PostgREST so RLS remains the authorization boundary.

### Account and community
- `GET /v1/me` — caller's profile.
- `PATCH /v1/me` — update allowlisted profile fields; unique username collisions return 409.
- `GET /v1/usernames/:username/availability` — boolean availability result without revealing the account that owns a name.
- `GET/PATCH /v1/me/settings` — caller's privacy and notification preferences.
- `GET /v1/profiles/:username` — discoverable public profile fields only.
- `GET /v1/community/search?q=...` — search only public, discoverable profiles.
- `GET/POST /v1/friends/requests` — list incoming/outgoing requests and send a request by username.
- `PATCH /v1/friends/requests/:id` — accept or decline an incoming request.
- `DELETE /v1/friends/requests/:id` — cancel an outgoing pending request.
- `POST /v1/shares` — create a public music share record for allowlisted provider URLs; returns a share path and `vinz://` deep link.
- `GET /s/:id` — public share metadata lookup subject to expiry and RLS.
- `DELETE /v1/shares/:id` — remove a share created by the caller.
- `GET/POST/DELETE /v1/watches/artists` — manage the caller's artist watchlist.
- `GET/POST/DELETE /v1/podcasts/subscriptions` — manage podcast subscriptions.
- `POST/DELETE /v1/devices` — register/remove the caller's push token.
- `GET /v1/notifications` and `PATCH /v1/notifications/:id` — private inbox and read status.

### Catalog
- `GET /v1/catalog/search?q=...&type=song|album|artist|podcast` — Apple iTunes Search API with normalized metadata and KV caching.
- `GET /v1/catalog/artists/:itunesArtistId/releases` — Apple artist album catalogue/discography with KV caching.
- `GET /v1/lyrics/search?q=...` or structured `track_name`/`artist_name`/`album_name` — LRCLIB lyrics search.
- `GET /v1/lyrics/get?track_name=...&artist_name=...&album_name=...&duration=...` — best-match lyrics lookup. Lyrics responses are proxied only, never stored in Supabase, KV or PostHog; 429 responses preserve `Retry-After`.
- Search results omit preview-audio URLs. Apple artwork/store links remain attributed and must be used under Apple's Search API promotional-use terms.

### Backups
- `GET /v1/backups` — list the caller's backup metadata.
- `PUT /v1/backups` — upload a binary backup with headers `x-vinz-format-version`, `x-vinz-app-version`, `x-vinz-sha256`, and `x-vinz-backup-scope`.
- `GET /v1/backups/:id` — download after ownership, size and SHA-256 verification.
- `DELETE /v1/backups/:id` — delete metadata and then its R2 object.

The API enforces the live database's 52,428,800-byte (50 MiB) maximum and supports the existing scopes `full`, `library`, `settings`, and `history`. It never truncates an oversized backup. The Android client must preserve its current export/restore format and only schedule automatic backup when the complete archive is within the limit.

## Discovery and notifications

The `vinz-discovery` Edge Function:
- Checks up to 8 due Apple artist watches per run, with a 3.1-second gap between Apple requests.
- Checks up to 5 due podcast RSS subscriptions per run.
- Uses the user's `notify_new_releases` and `notify_podcast_updates` preferences.
- Establishes a baseline on the first successful check rather than notifying users about an artist's full back catalogue.
- Uses per-user dedupe keys so retries do not create duplicate inbox items.
- Parses RSS/Atom feeds with a 2 MiB response cap, HTTPS-only URLs, bounded redirects and request timeouts.
- Stores in-app notifications. Push delivery is not yet enabled; it needs Firebase Cloud Messaging credentials and an agreed delivery policy.

The scheduler's shared token is stored in Supabase Vault and checked by a service-role-only RPC. The Edge Function is not exposed as an unauthenticated job runner.

The discovery, notification and share tables have explicit grants in addition to RLS; anonymous share readers can select only share metadata columns and cannot read the owner ID or write rows. Friend requests are participant-only, with recipients able to update status and senders able to cancel pending requests.\n\nThe username-availability RPC is intentionally `SECURITY DEFINER` because it must test usernames across private profiles without exposing those rows. It has an empty `search_path`, fixed read-only SQL, returns only a boolean, and is executable only by authenticated/service roles. Supabase flags this as a review warning; add edge rate limiting before public rollout.

## Worker bindings

The staging Worker has:
- `SUPABASE_URL` — project URL.
- `SUPABASE_PUBLISHABLE_KEY` — public client key; no service-role key.
- `BACKUPS_BUCKET` — R2 bucket binding.
- `CATALOG_CACHE` — KV namespace binding.
- `POSTHOG_PROJECT_KEY` — secret binding.
- `POSTHOG_HOST` — PostHog ingestion host.
- `ALLOWED_ORIGINS` — exact browser origins if a browser client is added; native Android calls do not need CORS.

Never place the Supabase service-role key in the Android app or public API Worker. The Edge Function uses Supabase's server-side runtime secret only for its scheduled administrative query.

## Privacy, quotas and provider rules

- Music library, listening history, recognition audio, lyrics text and personal playlists remain local by default.
- Private endpoints are `no-store`; public metadata cache entries are keyed by normalized provider query and have explicit TTLs.
- PostHog only receives operational error type, route, service and status code. Never send raw queries, track/artist names, lyrics, audio, tokens, emails or full user IDs.
- Apple iTunes is the initial no-key provider for metadata, artwork, podcast discovery and artist discographies.
- MusicBrainz remains a planned enrichment adapter. Its public API is limited to one request per second and must not be polled for metadata changes; use controlled queued refreshes.
- Podcast Index requires its registered API authentication headers. RSS is the initial episode-update path.
- LRCLIB is the first no-key lyrics lookup provider. Its docs require a descriptive User-Agent, sequential requests with 200–500 ms spacing for batches, and honoring `Retry-After` on 429. Its availability does not grant redistribution or translation rights. Use a licensed translation provider or user-provided/authorized translations; do not mirror copyrighted lyrics without permission.
- Recognition should use the official ShazamKit Android SDK as the primary device-side path; the app needs its Android AAR integration and ShazamKit media identifier/private key setup. AcoustID + Chromaprint is a non-commercial-only alternative unless a commercial agreement is obtained; it requires an application API key and must stay below three requests per second.
- Every external provider needs timeouts, bounded retries, usage budgets and graceful fallback.

## Remaining integration gates

- Google sign-in: configure a Google OAuth Web client ID/secret in Supabase Auth Provider settings and add the Android package/signing SHA-256 fingerprints to the Google OAuth configuration.
- Email OTP: configure a transactional SMTP provider and sender domain in Supabase Auth. Gmail mailbox access is not a transactional mail service.
- Push notifications: configure Firebase Cloud Messaging service credentials/project settings before enabling push delivery. Device registration and in-app notification storage already exist.
- Lyrics/translation and recognition: select providers with explicit rights/terms, documented rate limits and real credentials if required.
- Android integration: the other frontend agent must connect these documented API contracts without changing local-first behavior or the existing backup format.
- Vercel: only add a project when the web control plane exists.

## Safety gates

- No production Worker deployment, production app release, merge or destructive schema operation is included in this draft PR.
- The production `vinz` Worker is untouched; the new API is on `staging`.
- The database changes applied so far are additive. The reconstructed baseline migrations are intended to reproduce the live schema in a clean project and should be reviewed before using them for a fresh environment.
- Never cache authenticated responses or silently discard user data.
