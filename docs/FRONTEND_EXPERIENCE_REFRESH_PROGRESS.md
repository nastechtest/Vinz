# Vinz Account and Cloud Experience — roadmap progress

This is the single agreed development roadmap for the Android repository `nastechtest/Vinz`. Work remains account-first; do not broaden into unrelated music screens.

- Working branch: `feat/frontend-experience-refresh`
- Historical review: [PR #37](https://github.com/nastechtest/Vinz/pull/37) was merged on 2026-11-08. It cannot remain draft or include new branch commits. Per the user's one-PR/no-new-PR rule, no replacement PR is created; follow-up work remains on the named feature branch until review routing is explicitly changed.
- Backend: Supabase Auth/Postgres/RLS/Storage remains the account foundation. Cloudflare Workers and Durable Objects are reserved for link resolution and real-time room coordination.
- Local music, playback and library remain usable without signing in. Vinz Account remains separate from YouTube Music provider login.
- No production Supabase schema, Cloudflare resources, secrets, deployments or releases are changed by this branch work.

## Priority order and acceptance status

### Phase 1 — Vinz identity and authentication
- [x] Separate Vinz Account entry, email/password sign-up and sign-in, recovery request, encrypted Android Keystore session storage.
- [x] Username syntax validation and authenticated availability RPC for signed-in profile edits; the database has a case-insensitive unique index and server-side availability RPC.
- [x] Six-digit email OTP verification screen and Supabase `/verify` integration; no signup session is stored before successful code verification.
- [x] OTP resend flow with a 60-second UI cooldown; Supabase remains authoritative for expiry, request throttling and failed-attempt limits. Safe invalid/expired/rate-limit feedback is mapped without exposing response bodies.
- [x] Encrypted pending verification email/profile metadata survives process restarts and reopens the OTP screen; passwords and typed OTPs are never persisted.
- [ ] Verify hosted Supabase Auth configuration: Email confirmations must be enabled and the signup/email OTP template must render `{{ .Token }}` (not only `{{ .ConfirmationURL }}`) for numeric-code entry. Confirm configured OTP expiry and provider rate limits. This is a provider-console check; no production setting was changed.
- [ ] Pre-auth username availability while registering; current RPC is authenticated-only, so registration still relies on the database unique constraint at final save.
- [ ] Google OAuth with PKCE and verified Android callback.
- [ ] Automatic session refresh/401 retry, expiry/revocation handling, account switching, identity linking/unlinking.
- [ ] Password reset completion screen and recovery callback/deep link.

### Phase 2 — Complete user profiles
- [x] Account overview and profile editor; username, display name, bio, public/discoverable controls.
- [x] Supabase profile row is owner-updated; public/private profile visibility fields are persisted.
- [ ] Full name private by default, public profile route and canonical profile URL.
- [ ] Avatar upload/change/remove with image validation and verified owner-only storage policy.
- [ ] Username availability during registration and race-condition UI recovery (server unique index remains final authority).

### Phase 3 — Account settings and security
- [x] Existing account overview, security/reset request and connected-services sections.
- [ ] Persist notification preferences, privacy controls, connected identities, active sessions/devices and revoke controls.
- [ ] Password change/recovery callback, account export and deletion paths where supported.
- [x] Repository migration adds a database-side verified-email gate to account-owned RLS and private backup-object policies; it is not applied to the connected production project.

### Phase 4 — Cloud backup and restore
- [x] Existing private `vinz-cloud-backups` bucket and `vinz_backups` metadata foundation are present; local backup/restore must remain unchanged.
- [ ] Implement signed-in cloud backup/restore, below-50-MB lightweight rule, excluded-file disclosure, restore preview, checksum/version validation, progress/cancel/retry and conflict handling.
- [ ] Verify round-trip, corruption, offline and storage-policy failures.

### Phase 5 — Vinz direct song sharing and deferred install
- [x] Existing share-link metadata table is present; this is not yet the finished Android share experience.
- [ ] Canonical HTTPS resolver on Cloudflare Worker; verified Android App Links; exact song/album/artist/playlist routing.
- [ ] Installed-app open, web preview when absent, Play Store path and deferred destination restore after install.
- [ ] Open Graph metadata, share sheet/copy/QR, privacy-aware analytics, expiry/deleted/private/unavailable fallbacks.
- [ ] No copyrighted audio hosting or rebroadcast; resolve to lawful provider links and the listener's own entitlement.

### Phase 6 — Advanced Listen Together
- [ ] Cloudflare Worker authenticated signaling/API and Durable Object room coordinator with hibernating WebSockets.
- [ ] Server-authoritative queue/play/pause/seek/track state, sequence numbers, server clock, presence, host/co-host roles, approvals, invites, reactions/chat, moderation and host transfer.
- [ ] Drift correction, latency measurement, reconnect/resume, duplicate/out-of-order event handling, host disconnect recovery and network/lifecycle handling.
- [ ] Optional WebRTC DataChannel and TURN fallback; optional SFU only for voice, never copyrighted song audio.
- [ ] Every participant plays independently from their own lawful source/entitlement; add authorization, room-size, reconnect-storm, event-ordering and drift/load tests.

### Phase 7 — Community and discovery
- [ ] Expand discovery/social experiences only after identity/profile foundations are complete, with privacy, consent, blocking and moderation.

## CI and verification

- Existing Android PR checks are reused, not duplicated. `.github/workflows/pr-checks.yml` now also runs on pushes to `feat/frontend-experience-refresh` because PR #37 is already merged.
- Existing platform checks now run on the feature branch when Supabase migrations change and statically guard the verified-email RLS gate.
- Android JVM/UI tests, lint, debug APK verification and artifact upload must pass on the current feature head before the increment is called verified.
- Existing platform checks cover Cloudflare Worker tests and Supabase migration naming/order. Add isolated Supabase Auth/RLS contract tests as the backend increments land.
- Keep release/deploy workflows protected; no feature branch auto-deploy.
- Only link APKs when the workflow artifact exists and package/signature/checksum are verified.

## Current implementation increment

- Added Supabase email OTP verify/resend API handling, a numeric verification screen, resend cooldown, server-error mapping, encrypted pending verification metadata and controller/UI regression tests.
- Added a repository-only migration that gates account-owned RLS policies and private backup objects on the authoritative `auth.users.email_confirmed_at` timestamp. It has not been applied to the connected Supabase project.
- Added feature-branch Android and migration checks by extending existing workflows; no duplicate pipeline was created.
- CI run status and APK artifact are recorded after the feature-branch workflows complete.

## Latest validation — 2026-11-08

- Feature head `ffc6b6bee3cd6674bf3d10d5c4cad2a78321c83f` passed the platform backend checks (Supabase migration integrity, Cloudflare Worker tests and workflow hygiene).
- Android PR checks exposed a real Kotlin compile error in the OTP controller: the verification gateway returns the sealed `AuthResult` union, but the controller treated it as `SignedIn` without handling the other variants. Both the unit-test and Android Lint jobs failed at compilation; no APK artifact was produced.
- Fixed the controller to handle all three OTP outcomes explicitly. A regression test now asserts that a second `VerificationRequired` response keeps the account locked on the OTP screen and does not create a signed-in identity.
- Validation of this fix is pending the feature-branch workflow run triggered by the fix commit. No result is claimed until that run completes.
