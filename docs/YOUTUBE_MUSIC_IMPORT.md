# YouTube Music import for Vinz

YouTube Music is a **connected import service**, not a second Vinz sign-in method. The user signs in to Vinz first, opens **Vinz Account → Connected services → YouTube Music**, and selects **Connect YouTube Music**. Vinz sends the browser to Google's OAuth consent screen using the signed-in Vinz email as the optional login_hint; the user never enters a YouTube email or password into Vinz.

## Google OAuth setup

Use the same Google OAuth Web application client that is configured for Supabase Google sign-in, or a dedicated Web application client owned by Vinz.

Add this exact Authorized redirect URI in Google Cloud Console:

`https://api.vinzmusic.workers.dev/v1/integrations/youtube/callback`

Keep the existing Supabase Auth callback URI as well:

`https://iszghebmetrbkfhnnoqq.supabase.co/auth/v1/callback`

Enable **YouTube Data API v3** for the Google Cloud project and complete the OAuth consent-screen configuration. The requested scope is read-only:

`https://www.googleapis.com/auth/youtube.readonly`

The API uses PKCE, a random one-time state value held for ten minutes in the existing Cloudflare CATALOG_CACHE KV binding, and a fixed callback/return URL. OAuth tokens are AES-GCM encrypted before they are written to Supabase.

## Cloudflare Worker secrets

Set these secrets on the production API Worker named vinz (never put them in Git, browser code, or Android resources):

- `GOOGLE_CLIENT_ID` — OAuth Web client ID.
- `GOOGLE_CLIENT_SECRET` — matching OAuth client secret.
- `YOUTUBE_TOKEN_ENCRYPTION_KEY` — 32 cryptographically random bytes encoded as base64url.

Generate an encryption key locally with:

`node -e "console.log(require('node:crypto').randomBytes(32).toString('base64url'))"`

Then set each value using the Cloudflare Worker secrets workflow. Do not paste secret values into GitHub issues, pull requests, or chat. The same OAuth client ID and secret must be used for the consent request, token exchange, and refresh-token exchange.

## Database migration

Apply `supabase/migrations/20261108100000_vinz_youtube_music_import.sql` through the normal Supabase migration pipeline. It creates:

- `vinz_youtube_connections` — encrypted provider tokens and channel metadata.
- `vinz_youtube_imports` — imported playlist/video metadata.

Both tables have RLS enabled and owner-only policies. The public anon role receives no access. The Cloudflare API passes the signed-in Vinz user's JWT to PostgREST; it does not use a service-role key.

## Import behavior and API limits

The importer currently reads up to ten accessible playlists and up to 100 videos per playlist per run. It stores titles, channel/artist labels, playlist IDs, video IDs, publication dates and safe thumbnail URLs. It does not download or proxy audio.

The official YouTube Data API does not expose every YouTube Music feature, complete private listening history, or every private library surface. Vinz must describe the import as accessible playlist/video metadata, not promise a complete YouTube Music account clone. Private or unavailable videos are skipped.

## Verification

Automated tests cover OAuth PKCE/state creation, the no-email-entry login hint, fixed callback URL, expired-state rejection, metadata-only status responses, and import refusal when no connection exists. The production readiness workflow also verifies the live home/API/Supabase endpoints and reports missing files, wrong hosts, duplicate CORS origins, and changed URL/config lines.

**Release gates:** deploy the API Worker with all three secrets, apply the database migration, run the OAuth flow with a real Google test account, then verify connect → status → import → disconnect against production. This PR does not deploy the Worker, apply the migration, or configure Google Cloud credentials. The Android account Connections tab now calls the same authenticated API: it opens the Google consent URL in the external browser and refreshes the connection status when the user returns to Vinz. The web account page and Android account page both expose connect, import, refresh/status and disconnect controls.