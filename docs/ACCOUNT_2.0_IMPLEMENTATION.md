# Vinz Account 2.0 implementation

## Web routes
- `/`: Vinz email/password sign-up and sign-in, Google sign-in, email OTP verification/resend, and recovery entry.
- `/account`: account overview and navigation.
- `/account/profile`: profile editor, live username availability, avatar upload/remove, privacy and discoverability.
- `/account/security`: password update and session revocation.
- `/account/connections`: linked Google identity and YouTube integration status.
- `/auth/callback` and `/auth/reset-password`: allowlisted auth callback and recovery completion.
- `/u/[username]`: public music profile page.

## Android routes and experience
The existing native Vinz Account host now has Overview, Profile, Security and Connections sections. Profile editing uses the same server-backed username availability RPC, keeps privacy/discoverability controls, and handles final database conflicts. Security can send a password recovery email and revoke the current/all sessions through the existing auth client. Local music stays independent of cloud account sign-in.

Native Android Google OAuth callback/session exchange and Android avatar upload UI are not included yet. The Connections section explicitly explains that Google Vinz identity and the existing YouTube Music cookie sign-in are different systems.

## Server-authoritative usernames
- Web and Android debounce availability requests to the authenticated Supabase RPC `vinz_username_available`.
- The RPC returns only a boolean availability result and applies a per-user limit of 30 checks per minute.
- Migration `20261107200000_vinz_account_2_username_and_avatar_hardening.sql` canonicalizes usernames in a database trigger, rejects reserved names, and keeps the existing unique index as the final race-condition guard.
- Availability is advisory, not a reservation. The database constraint and trigger remain authoritative at save time. A concurrent claim must fail safely without overwriting the user's previous profile.

## Avatar storage
The migration provisions a public-read `vinz-avatars` bucket (5 MB; JPEG/PNG/WebP). Object writes, updates and deletes are restricted to the authenticated user's own first-level folder. The web profile editor uses this bucket; native Android avatar upload is not yet included.

## Google and YouTube
- Google sign-in is a Vinz identity provider on the web auth page. Linking Google from account settings is separate from YouTube import.
- YouTube is never presented as a Vinz sign-in method.
- YouTube import is disabled until the server-side provider-token exchange, secure token storage, and import worker exist. Do not request scopes that the app cannot safely use.
- YouTube Data API does not guarantee access to listening history or every private YouTube Music library feature.

## Deployment gates
- Configure Google OAuth client credentials in Supabase Auth and Google Cloud Console.
- Enable YouTube Data API v3 and configure its consent screen before implementing the server-side importer.
- Configure Supabase Auth Site URL as `https://home.vinzmusic.workers.dev` and allow `https://home.vinzmusic.workers.dev/auth/callback`. The Google OAuth provider redirect URI remains the Supabase Auth callback URL.
- Apply the migration through the normal Supabase migration pipeline before enabling avatar uploads or the new username restrictions.
- Account deletion remains disabled until a privileged server-side workflow can remove the Auth identity, profile rows and stored objects safely.
- No production secrets are included in the client, and this PR does not apply migrations or deploy production resources.
