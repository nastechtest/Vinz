# Vinz Account 2.0 implementation

## Routes in this PR
- `/account`: account overview and navigation.
- `/account/profile`: profile editor, live username availability, avatar lifecycle, privacy and discoverability.
- `/account/security`: password update and session revocation.
- `/account/connections`: linked Google identity and separate YouTube read-only consent.
- `/u/[username]`: public profile page.

## Server-authoritative usernames
- The profile editor debounces availability requests to the existing authenticated Cloudflare API route.
- Cloudflare authenticates the Supabase access token and calls the Supabase RPC.
- Migration `20261107200000_vinz_account_2_username_and_avatar_hardening.sql` canonicalizes usernames in a database trigger, rejects reserved names, preserves the unique index as the final race-condition guard, and limits availability RPC calls to 30 per user/minute.
- Availability is advisory, not a reservation. The unique database index remains authoritative when saving and the UI handles a concurrent-claim conflict.

## Avatar storage
The migration provisions a public-read `vinz-avatars` bucket (5 MB; JPEG/PNG/WebP). Object writes, updates and deletes are restricted to the authenticated user's own first-level folder.

## Google and YouTube
- Google identity linking uses Supabase Auth `linkIdentity`; it is not presented as a separate YouTube sign-in.
- YouTube authorization is a distinct Google consent request for `youtube.readonly`. Import functionality must be enabled only after Google OAuth consent configuration and a secure server-side provider-token exchange/storage path are configured. The browser must not persist or expose refresh tokens.
- YouTube Data API does not guarantee access to listening history or every private YouTube Music library feature.

## Remaining provider/deployment gates
- Configure Google OAuth client credentials in Supabase Auth and Google Cloud Console.
- Enable YouTube Data API v3 and configure the consent screen.
- Configure Supabase Auth Site URL and redirect allowlist for the deployed Vercel URL and callback.
- Apply the migration through the normal Supabase migration pipeline before enabling avatar uploads or username changes.
- Android currently retains its existing account screen; a separate Android navigation increment is still required to expose all Account 2.0 pages natively.
- No production secrets are included in the client, and this PR does not apply migrations or deploy production resources.
