# Vinz backend platform

This repository is currently a native Android application. The platform work below adds backend source and contracts without changing Android screens or replacing local-first music behavior.

## Connected services and responsibilities

| Service | Owns | Must not own |
|---|---|---|
| Supabase | Auth identities, public profile records, private account settings, backup metadata, PostgreSQL RLS | Public metadata caching, long-running discovery crawls |
| Cloudflare Workers | Public API edge, request validation, caching, rate limits, routing | Source-of-truth user records |
| Cloudflare R2 | Encrypted/opaque backup objects after opt-in; object storage only | Searchable account metadata |
| Cloudflare Queues / Cron | Durable background tasks and scheduled artist/podcast checks when enabled | Synchronous user-facing requests |
| PostHog | Aggregated feature usage, performance and error telemetry | Listening history, lyrics text, tokens, email addresses, or private music library |
| Vercel | Web control-plane or future web app deployments and preview checks | Android APK builds or duplicate queue workers |
| Linear | Roadmap, issue ownership, service limits, acceptance criteria and release tracking | Runtime execution |

A service being connected does not increase another provider's quota. The design reduces avoidable work by caching public data, queueing non-interactive work, and keeping user music data local.

## Existing verified infrastructure

- Supabase project `iszghebmetrbkfhnnoqq` is active in `eu-west-1`.
- Existing tables: `vinz_profiles`, `vinz_account_settings`, `vinz_account_private`, and `vinz_backups`.
- The `vinz_backups.byte_size` check enforces a maximum of 52,428,800 bytes (50 MiB); backup metadata includes SHA-256 and format version.
- Three Supabase migrations already exist; no Edge Functions are currently deployed.
- Cloudflare account already has a Worker named `vinz`, but it is the default Hello World script and has no service bindings.
- PostHog currently exposes one empty Default project. No Vinz events have been ingested.
- Vercel currently returns no project associated with the Vinz repository. The repository is Android-only, so Vercel is reserved for an actual web control plane if one is introduced.

## API contract (first slice)

### `GET /health`
Returns service health and whether the required Supabase runtime bindings are present. It does not expose configuration values.

### `GET /v1/me`
Requires a Supabase access token in `Authorization: Bearer <token>`. The Worker validates the token through Supabase Auth, then reads only the matching profile row through PostgREST. Row-level security remains enabled. Responses are always `no-store`; user-specific data is never placed in a shared edge cache.

Required Worker bindings:
- `SUPABASE_URL` — public project URL (non-secret configuration).
- `SUPABASE_PUBLISHABLE_KEY` — Supabase publishable/anon key (public client key; still store as a Worker binding).
- `ALLOWED_ORIGINS` — comma-separated exact browser origins if a web client is later introduced. Native Android calls need no browser CORS origin.

Never configure a Supabase service-role key in the mobile app or this Worker. Do not deploy authenticated routes until the public key binding is present and the RLS policies have been reviewed.

## Privacy and data boundaries

- Music library, listening history, recognition audio, lyrics content and personal playlists remain on-device by default.
- PostHog events should be coarse events such as `feature_used`, `api_request_failed`, `backup_completed`, and `discovery_refresh_completed`; use allowlisted properties only.
- Do not send raw search queries, track titles, artist names, lyrics, audio, access tokens, email addresses, or full user IDs to analytics.
- Private endpoints use `Cache-Control: no-store`. Public catalog metadata can use short-lived edge caching with explicit stale-while-revalidate and provider attribution.
- Every external provider call needs a timeout, bounded retries with jitter, a circuit breaker, and a cached/fallback result where licensing permits.
- Automatic cloud backup must preserve the app's current backup/restore format and only run when the complete backup is at or below 50 MiB. Larger backups must not be truncated; the user must retain the existing manual/export path.

## Implementation order

1. Preserve current Android behavior and verify existing database migrations/RLS.
2. Ship the Worker health/profile API behind explicit bindings and tests.
3. Add account flows (Google sign-in through Supabase Auth, email OTP, unique username reservation, profile/settings) using database constraints and RLS.
4. Add backup object upload/download with SHA-256 verification, immutable object keys, per-user ownership checks, and restore compatibility tests.
5. Add provider adapters and shared metadata caching for artist catalogs, artwork, lyrics/translation and podcasts.
6. Add queue-driven new-release monitoring and notification fan-out with user consent.
7. Add privacy-safe PostHog telemetry and feature flags; instrument failures and usage budgets.
8. Connect Vercel only to a real web app/control plane; keep Android build/release CI in GitHub Actions.

## Operational safeguards

- No production deployment or destructive database migration as part of scaffolding.
- No service-role key in client code, logs, analytics, or source control.
- No shared cache for authenticated responses.
- Add provider-level budgets and alert thresholds before enabling scheduled crawls.
- Keep fallback behavior explicit; never silently discard a backup or user data.
