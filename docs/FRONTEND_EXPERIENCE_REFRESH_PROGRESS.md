# Vinz Account and Cloud Experience — roadmap progress

This is the single agreed development roadmap for the Android repository `nastechtest/Vinz`. Work remains account-first; do not broaden into unrelated music screens.

- Working branch: `feat/frontend-experience-refresh`
- Historical review: [PR #37](https://github.com/nastechtest/Vinz/pull/37) was merged on 2026-11-08. It cannot remain draft or include new branch commits. Per the user's one-PR/no-new-PR rule, no replacement PR is created; follow-up work remains on the named feature branch until review routing is explicitly changed.
- The feature branch is currently 8 commits ahead and 150 commits behind `main`; no merge or rebase was performed. The merged PR cannot be reopened as a draft, so follow-up evidence is posted in its conversation only.
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
- [x] Pre-auth username availability in the registration UI: debounced Android checks call a public Worker endpoint backed by an anon-only, boolean-returning SQL function; no email, user ID or private profile data is returned. The lower(username) unique index remains the final race-safe authority.
- [ ] Production rollout of signup availability: apply the new repository migration and deploy the Worker rate-limit binding only after explicit approval. The connected production Worker has not yet been changed.
- [ ] Google OAuth with PKCE and verified Android callback.
- [ ] Automatic session refresh/401 retry, expiry/revocation handling, account switching, identity linking/unlinking.
- [ ] Password reset completion screen and recovery callback/deep link.

### Phase 2 — Complete user profiles
- [x] Account overview and profile editor; username, display name, bio, public/discoverable controls.
- [x] Supabase profile row is owner-updated; public/private profile visibility fields are persisted.
- [ ] Full name private by default, public profile route and canonical profile URL.
- [ ] Avatar upload/change/remove UI and image validation. The repository now has verified-email + owner-scoped avatar object write policies; upload functionality is not yet implemented.
- [x] Username availability during registration and race-condition UI recovery (server unique index remains final authority).

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
- Added pre-auth username availability end to end: Android now debounces registration checks and blocks signup until the Worker reports availability; the Worker returns only `{ username, available }`, hashes the Cloudflare client IP before its 120/minute rate-limit key, and calls a new anon-only SQL function. No email, user ID or private profile data is returned; the unique lower(username) index remains the final authority.
- Added staging/production Wrangler rate-limit bindings. The migration and Worker config are repository-only; neither was applied/deployed. The connected `vinz` Worker currently has no `USERNAME_CHECK_LIMITER` binding, so signup availability remains fail-closed until rollout is approved.
- Fixed a LookStore state race: `isOn()` now compares the canonical `SettingsRepository.current` snapshot with the Look preview, rather than the separately collected `lookValues` flow.
- Consolidated duplicate Worker tests into the existing platform checks workflow, which now runs on all branch pushes and validates both Wrangler configs plus the signup RPC.

- Security follow-up: added a repository migration that also requires verified email for avatar object insert/update/delete while retaining owner-folder isolation. The platform workflow now asserts all three policies. This migration is not applied to the connected Supabase project.
- Added feature-branch Android and migration checks by extending existing workflows; no duplicate pipeline was created.
- CI run status and APK artifact are recorded after the feature-branch workflows complete.

## Latest validation — 2026-11-08

- Feature head `ffc6b6bee3cd6674bf3d10d5c4cad2a78321c83f` passed the platform backend checks (Supabase migration integrity, Cloudflare Worker tests and workflow hygiene).
- Android PR checks exposed a real Kotlin compile error in the OTP controller: the verification gateway returns the sealed `AuthResult` union, but the controller treated it as `SignedIn` without handling the other variants. Both the unit-test and Android Lint jobs failed at compilation; no APK artifact was produced.
- Fixed the controller to handle all three OTP outcomes explicitly. A regression test now asserts that a second `VerificationRequired` response keeps the account locked on the OTP screen and does not create a signed-in identity.
- The Android run for the OTP fix compiled successfully and executed 1,175 JVM tests: 2 failed, 9 skipped. All four OTP-controller tests passed, including the new regression. The two failures were existing integration assumptions exposed by the new strings: the locale test required every locale to duplicate base strings instead of allowing Android's normal `values/` fallback, and the gateway test seam contained two hardcoded English exception messages rejected by the i18n guard. Both are corrected in the next patch; a fresh Android run is required before claiming a green build.
- The avatar-policy platform run passed on commit `d586b75cf3e926e7425cf4a00f4d5cc33b7cce02`: migration ordering/integrity, the verified-email account policy check, the avatar write-policy check, Cloudflare Worker tests and workflow hygiene all passed. No production schema or Worker was changed.


## Latest implementation — 2026-11-09

- Feature head before this increment: `c59df02553d63b4299f7c887a3cb091c26c485b3`.
- Fixed the Android unit-test failure in `LookStoreTest.theEndOfTheSeasonBringsBackTheLookBefore` by correcting the production `LookStore.isOn` snapshot comparison; a regression assertion was added.
- Added pre-auth registration username availability across Android, Cloudflare Worker and Supabase migration. Signup and changed profile usernames remain blocked while checking, when taken, or when the service fails; database uniqueness remains final authority.
- Added Worker edge rate limiting (120 requests/minute per hashed Cloudflare client IP) and a boolean-only SQL RPC. No user ID, email, private profile or account details are returned by this endpoint.
- Consolidated duplicate Worker-only tests into the existing platform checks workflow. Android unit tests and platform checks are pending for this commit; do not call the increment green until both finish.
- Production rollout is blocked on approval to apply `20261109150000_vinz_public_username_availability.sql` and deploy the `USERNAME_CHECK_LIMITER` binding to the existing `vinz` Worker. No production schema, Worker, secret, deployment or release was changed.


## Latest implementation — 2026-11-09 session reliability

- Added absolute access-token expiry to the encrypted Vinz session record while remaining backward-compatible with existing encrypted records that do not contain an expiry.
- Authenticated Supabase requests now refresh proactively within a 60-second expiry window. A single 401 can trigger one refresh-and-retry; concurrent requests share the rotated session rather than consuming the same refresh token twice.
- Refresh-token rejection clears the invalid local session; transient server/network failures retain it for retry. A retried 401 clears only the same user/session version, avoiding accidental sign-out of a newer account.
- Added pure JVM regression tests for server expiry, relative expiry, refresh leeway and legacy sessions. Android CI is pending for this increment.
- The current feature head before this increment was `64e913d00b9819370554076b1c4ccd53852a0cc6`. No production Supabase settings, schema, Worker, OAuth configuration or credentials were changed.


- Follow-up hardening: sign-in and OTP session writes now share the refresh lock, preventing a slow refresh from overwriting a newly authenticated account. Session clearing compares both access and refresh token versions before removing state. Added a regression test for rotated-token version detection.
