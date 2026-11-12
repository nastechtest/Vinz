# Vinz Account and Cloud Experience — roadmap progress

This is the single agreed development roadmap for the Android repository `nastechtest/Vinz`. Work remains account-first; do not broaden into unrelated music screens.

- Working branch: `feat/frontend-experience-refresh`
- Historical review: [PR #37](https://github.com/nastechtest/Vinz/pull/37) was merged on 2026-11-08. It cannot remain draft or include new branch commits. Per the user's one-PR/no-new-PR rule, no replacement PR is created; follow-up work remains on the named feature branch until review routing is explicitly changed.
- The feature branch is currently 38 commits ahead and 150 commits behind `main`; it is diverged, and no merge or rebase was performed. The merged PR cannot be reopened as a draft, so follow-up evidence is posted in its conversation only.
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
- [x] Native Google OAuth PKCE flow, encrypted one-use verifier/state, strict `vinz://auth/callback` validation and app callback handling; Supabase Google provider credentials/redirect allowlist still require hosted-console verification.
- [ ] Automatic session refresh/401 retry, expiry/revocation handling, account switching, identity linking/unlinking.
- [ ] Password reset completion screen and recovery callback/deep link.

### Phase 2 — Complete user profiles
- [x] Account overview and profile editor; username, display name, bio, public/discoverable controls.
- [x] Supabase profile row is owner-updated; public/private profile visibility fields are persisted.
- [ ] Full name private by default, public profile route and canonical profile URL.
- [x] Android avatar choose/upload/change/remove flow, bounded image decoding, center-square crop, metadata-stripping WebP re-encoding, 5 MiB Storage limit and owner-session-bound Storage API requests. The existing repository migration creates the public-read `vinz-avatars` bucket and owner-folder write policies; production rollout remains blocked because those migrations are not applied to the connected project.
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


## Live resource inspection — 2026-11-09

- The connected Supabase project `Vinz` is active. Live schema confirms `vinz_profiles`, `vinz_account_private`, `vinz_account_settings`, `vinz_backups` and `vinz_share_links` exist. The private `vinz-cloud-backups` bucket is capped at 50 MiB.
- Live migration history currently ends at `20261108082934_vinz_youtube_music_import`. Repository migrations `20261108140500_vinz_require_verified_email_for_account_policies.sql` and `20261109150000_vinz_public_username_availability.sql` are not applied to the connected project. Live policy inspection also confirms the verified-email RLS gate is not yet active. No production migration was applied.
- The current Cloudflare connection resolves to a different account than the previously recorded account ID. Read-only inventory found Workers `api`, `backup`, `home`, `share`, `staging` and `vinz`. The `api`, `backup` and `share` scripts are small path-gating wrappers that forward to `env.VINZ.fetch`; the `vinz` script contains the Supabase-backed API implementation, so it is not currently a Hello World-only Worker. No Worker, binding, secret or deployment was changed.
- Feature branch head at inspection: `4c6379d622f6c076501bc44999a113cfe6434855`; comparison with `main`: 18 commits ahead and 150 behind. PR #37 was already merged on 2026-11-07; follow-up remains on this feature branch and in the existing PR conversation. No replacement PR, merge or rebase was performed.
- Session-refresh Android validation for code head `4c6379d622f6c076501bc44999a113cfe6434855` is queued in run [42646360263](https://github.com/nastechtest/Vinz/actions/runs/42646360263). The prior green build at `64e913d00b9819370554076b1c4ccd53852a0cc6` is only a baseline and does not include session refresh.

## Android CI compile failure correction — 2026-11-09

- Inspected the actual feature-branch push run [42646360263](https://github.com/nastechtest/Vinz/actions/runs/42646360263), rather than relying on the earlier PR-run baseline. Both the JVM/build job and Android Lint failed at Kotlin compilation.
- Root cause: `VinzCloudAccount.executeRequest()` declared an `HttpResult` return type but ended with a `use { ... }` expression in a block-bodied function without an explicit `return`. Kotlin reported `VinzCloudAccount.kt:265:5 Missing return statement`.
- Fixed the implementation by explicitly returning the OkHttp response's `use` result. No auth behavior, credentials, production settings or backend resources changed.
- Code commit: `6c6227dce1c763a678d05ecec92e9dce9abf754e`.
- A new feature-branch push run is expected to validate compile, JVM tests, Lint, APK assembly and APK verification. Until that run completes successfully, this fix is not marked green and no new APK is claimed.
- Current next priorities remain session-refresh tests, Google OAuth/PKCE and recovery callbacks, full profile/settings, then cloud backup/restore, canonical share/deferred install links and cloud-coordinated Listen Together. Username availability and mandatory email OTP remain required.

## Google identity sign-in — 2026-11-10

- Added a native **Sign in with Google** action to the separate Vinz Account screen. It opens the Supabase-hosted provider authorization page in the user's browser; it does not use or change the YouTube Music cookie login.
- Added RFC 7636 S256 PKCE generation, a random per-attempt state value, a ten-minute callback window, exact `vinz://auth/callback` scheme/host/path validation, constant-time state comparison and one-use authorization-code exchange.
- The PKCE verifier/state/timestamp are encrypted with the existing Android Keystore-backed store. The app exchanges the code directly with Supabase Auth using the public publishable key; no Google client secret, service-role key or Cloudflare credential is included in Android.
- The callback rechecks Supabase's confirmed-email fields before persisting a session. The account controller refreshes its identity/profile after the external callback without putting access or refresh tokens in Compose state.
- Added pure JVM tests for the RFC 7636 challenge vector, verifier secrecy in the authorization URL, valid callback, state mismatch, expired callback, provider denial and strict callback host/path.
- This is repository code only. The connected Supabase project has not been modified. **Google sign-in remains blocked until** Google is enabled in Supabase Auth, its provider client ID/secret are configured server-side, and `vinz://auth/callback*` is added to Supabase's allowed redirect URLs. The Google provider's registered OAuth redirect must remain Supabase's `/auth/v1/callback`; no provider secret belongs in Vinz.
- Prior Android CI for the preceding Kotlin return fix passed all four jobs, including deterministic unit tests, APK assembly/verification and Lint, on code commit `6c6227dce1c763a678d05ecec92e9dce9abf754e` (run [42648340791](https://github.com/nastechtest/Vinz/actions/runs/42648340791)). The new OAuth increment requires its own fresh CI run before it is marked green.
- Remaining roadmap is unchanged: mandatory email OTP and race-safe username uniqueness; OAuth/recovery completion; full private/public profile and avatar; account settings; cloud backup/restore; canonical song sharing and deferred install; Durable Object Listen Together; community/discovery last.

## Persisted email OTP resend cooldown — 2026-11-10

- The six-digit verification flow now stores its resend-available timestamp inside the existing encrypted Android Keystore-backed pending-verification record. The email, pending username/display name and cooldown metadata remain encrypted together; passwords and typed OTPs are still never persisted.
- Signup and unconfirmed-password sign-in start a 60-second deadline only after the Supabase OTP/resend request succeeds. A successful resend advances the same deadline.
- Reopening the account screen or recreating its controller restores the remaining seconds instead of resetting the timer. The data client also rejects a resend while its persisted deadline is still active; Supabase remains authoritative for provider expiry, abuse/rate limits and failed-attempt limits.
- Added pure JVM tests for deadline boundaries/overflow and controller tests for restoring the cooldown and using the stored deadline after resend.
- No Supabase/Cloudflare production resource or provider-console setting was changed. The numeric email template, email confirmation setting, expiry and provider rate limits still require hosted Supabase Auth verification.
- Implementation commits: `c9bac1ba2e44c617195387f0163fe4621eb8df04`, `ee477f67137041015f5c62209860cc8575e550df`, `49c0b280c9480117a5b6cc88de83b4e4dbf811e2`, `58736e7454212a4cff0e830e27185912f8a5a956`, `3539ee68c2a3c4070c9ef859a1bd7e2f98118e21`, `eb0a31aeff160e6216736b6ef8e3a0e2f0cc721c`, `ee9931083c075f9bc18c5b120a57b17dfbd63a98`.
- Validation status: previous feature-head CI at `3dec1a7547217bf25a9ec0b81b20acf029366798` passed Lint, wrapper validation, JVM tests, debug APK assembly/signature verification and CI-script/workflow checks. Fresh CI for this cooldown increment must complete before the changes are marked green or an APK is linked.

## Live username-RPC security finding and repository hardening — 2026-11-10

- Re-inspected the connected Supabase project `iszghebmetrbkfhnnoqq` read-only. Live migration history currently ends at `20261108082934_vinz_youtube_music_import`; the repository's verified-email gates, avatar policies, public signup-availability migration and later hardening migrations are not applied.
- The live database currently has only the private `vinz-cloud-backups` storage bucket (50 MiB cap); `vinz-avatars` is not present in the connected project. No production schema or storage change was made.
- Supabase's live security advisor reported `public.vinz_username_available(text)` as `SECURITY DEFINER` and recommended revoking or changing the exposed function. Read-only catalog inspection confirmed `security_definer=true`.
- Added repository-only migration `20261110190000_vinz_username_rpc_security_hardening.sql`: both exposed public username RPCs become `SECURITY INVOKER` wrappers; the signup lookup's narrow `SECURITY DEFINER` implementation lives in the non-exposed `vinz_private` schema and returns only a boolean; exact execute grants are retained for anon signup checks and authenticated profile edits. The lower(username) unique index remains the atomic uniqueness authority.
- Extended the existing platform workflow (no duplicate workflow) to assert that only private helpers are SECURITY DEFINER and public wrappers are SECURITY INVOKER.
- Read-only Cloudflare inspection confirms the live `vinz` Worker has no `USERNAME_CHECK_LIMITER` binding and no Durable Object binding. The Google client secret is bound server-side as a secret; no secret values were read or exposed. No Worker setting or deployment was changed.
- Supabase's security advisor also reports leaked-password protection disabled. This is a hosted Auth setting and was not changed; it needs explicit production configuration approval.
- No migration was applied and no Cloudflare/Supabase production resource, secret, deployment or release was changed. The repository migration must be applied only after explicit user approval.
- CI status for this increment: the existing platform checks are triggered by the migration/workflow change; wait for the new run before marking the security assertion green.
- Implementation commits: `a3309eb3da24e47fe0100c7877da7245e298fbe6` (migration), `0bc125c070591d0350e93c849c12f272835065c3` (platform assertion).

### Validation addendum — 2026-11-10

- Platform backend checks passed on security migration/workflow commit `0bc125c070591d0350e93c849c12f272835065c3`: [run 42878090980](https://github.com/nastechtest/Vinz/actions/runs/42878090980).
- Android run [42877504616](https://github.com/nastechtest/Vinz/actions/runs/42877504616) is still in progress for OTP cooldown code/test commit `ee9931083c075f9bc18c5b120a57b17dfbd63a98`. CI scripts and Gradle wrapper checks passed; Android Lint and deterministic JVM tests are still running. APK assembly, verification and upload have not started, so this increment has no new verified APK yet.
- The current branch tip after this progress update is a documentation-only commit; no Android source changed after the Android CI test head. The branch remains `feat/frontend-experience-refresh`, 33 commits ahead and 150 behind `main` at the last comparison. No merge or rebase was performed.
- PR #37 remains closed and merged. Follow-up evidence was added as comments only; no new PR was created.


## Private full-name profile increment — 2026-11-11

- Re-inspected the feature branch, PR #37, Android/platform workflows, live Supabase schema/migration history, and connected Cloudflare account before changing code. PR #37 is closed and merged; no replacement PR was created.
- The latest completed Android run for the OTP/username/OAuth branch increment is [42877504616](https://github.com/nastechtest/Vinz/actions/runs/42877504616): all four jobs passed (deterministic JVM tests, debug APK assembly and APK verification/upload, Android Lint, CI script/workflow checks, and Gradle wrapper validation). The APK artifact is `Vinz-feature-620.apk` (artifact id `13792673067`); it predates this private-name increment.
- Added private full-name loading and saving against the existing owner-only `public.vinz_account_private.full_name` column. Public profile reads/writes remain in `vinz_profiles`; the private full name is never added to public profile output or overview UI.
- Added a separate private-details editor and save action, 120-character client/server validation, identity-change protection, gateway/controller tests and a Compose UI regression test.
- No schema change was needed: the existing migration creates the private row on auth-user creation, grants only owner-scoped SELECT/UPDATE, and the verified-email RLS migration gates access. Live Supabase reports the table with RLS enabled. No production migration or data mutation was performed.
- New code/test increment is committed on `feat/frontend-experience-refresh`. Android CI is pending for the corrected code commit; no platform workflow was triggered because this increment did not change Worker or migration files. The earlier platform run [42878090980](https://github.com/nastechtest/Vinz/actions/runs/42878090980) passed.
- Remaining account work: avatar upload/change/remove, public profile route/canonical URL, persisted notification/privacy settings, connected identity/session management and recovery callback completion. Cloud backup/restore, canonical share/deferred install, and Durable Object Listen Together remain in the agreed priority order.
- Code-review correction before validation: the first private-name patch had a literal `undefined` in its PATCH filter because the source-generation template expanded Kotlin interpolation too early. Corrected the request path to use the authenticated session's user ID in commit `e870fc219be951a677c3f3d6eae0e48fea72af0d`; no request was sent to production and no live data was changed.

- A source review caught and corrected an initial Kotlin string-interpolation mistake in the private-name PATCH filter before any production request was made; the corrected path is scoped to the authenticated session user in commit `e870fc219be951a677c3f3d6eae0e48fea72af0d`.

## Password recovery callback implementation — 2026-11-11

- Inspected current branch head, repository tree, account client/controller/UI, Android manifest, Supabase migrations, existing Android/platform workflows and recent CI before changing code. PR #37 remains merged/closed; no replacement PR was created.
- Implemented the app-owned `vinz://auth/reset-password` callback. The manifest registers the exact host/path, and `MainActivity` routes the callback into Vinz Account without logging or rendering callback token material.
- Recovery requests now include the explicit `redirect_to=vinz://auth/reset-password` target. The Supabase redirect allowlist and recovery email template still require hosted-project configuration; no production Auth settings were changed.
- Added a strict callback parser that accepts only the exact app route and `type=recovery`. It supports Supabase implicit-session fragments (access/refresh tokens) and custom `token_hash` recovery links, rejects partial/expired/wrong-type payloads, and does not log tokens.
- Implicit callback sessions are verified against Supabase `/auth/v1/user` before persistence. Token-hash callbacks are verified through `/auth/v1/verify`. Only a confirmed identity is stored, using the existing encrypted Android Keystore session store.
- A separate encrypted pending-recovery marker causes the account controller to show a new-password form after callback handling. The form requires an eight-character minimum and matching confirmation; password values remain transient in UI state and are cleared after submission/disposal. The update uses the authenticated Supabase `PUT /auth/v1/user` endpoint and clears the pending marker only after success.
- Added parser tests for fragment and token-hash callbacks, invalid routes/types, incomplete/expired payloads and the manifest route; added controller, validation and Compose UI tests for the recovery transition and password confirmation.
- Fixed the prior Android test failure in `CloudAccountScreenTest.privateFullNameIsClearlySeparateFromThePublicMusicProfile`: its hand-built state represented a loaded profile but omitted `originalUsername` and `UsernameAvailability.UNCHANGED`, so the profile-save button was correctly disabled. The fixture now represents the state produced by a successful profile load; no production save guard was weakened.
- CI evidence before this correction: run [43038559618](https://github.com/nastechtest/Vinz/actions/runs/43038559618) completed 1,198 JVM tests with 1 failure and 9 skipped; the only failure was that inconsistent test fixture. Lint, workflow validation and Gradle wrapper validation passed. No APK was produced by that failed run.
- Current Android validation run for code head `25366142e42904f1e77943945fc81a4298abff7e`: [43077254127](https://github.com/nastechtest/Vinz/actions/runs/43077254127), pending at the time of this update. Do not claim the recovery increment green or link an APK until that run completes successfully.
- Current feature branch comparison at code head: 65 commits ahead and 150 behind `main`; it remains diverged. No merge/rebase was performed.
- No production Supabase migration, Auth setting, Cloudflare resource, secret, deployment or release was changed.

### Next incomplete work

1. Confirm Android tests, Lint, debug APK assembly and APK signature/checksum verification on the current code head.
2. Complete provider-console verification for numeric email OTP, password recovery redirect allowlist/template, Google OAuth redirect allowlist and leaked-password protection.
3. Complete avatar upload/change/remove with safe image validation and storage policies, then finish account privacy, notification, connected identity and session/device settings.
4. Implement cloud backup/restore without changing existing local backup/restore.
5. Implement canonical song links, installed-app routing and deferred install.
6. Implement the Worker/Durable Object Listen Together control plane and its authorization, ordering, reconnect and drift tests.
7. Community/discovery remains last.

- Follow-up security hardening: recovery password updates now carry the expected user ID and re-check it against the active encrypted session before calling Supabase. A normal email/OTP/Google sign-in clears any stale recovery marker so a later account switch cannot reuse an earlier recovery authorization. The recovery form also requires matching confirmation and clears both transient password fields after submission.

- Final code commit for this recovery increment: `eabb0fa4f00735db03aace32a95408d7cb6facac`. Follow-up documentation commit: `c743ce136ce9d27890c49869ca993777bce9dc11`.
- Latest feature-branch Android workflow after the final code commit: [43077694746](https://github.com/nastechtest/Vinz/actions/runs/43077694746), still pending with no job results available at the latest inspection. The earlier pending run `43077254127` targets an older intermediate code head and is not the current validation target. No new APK is available or claimed.
- Latest branch head at this documentation update: `c743ce136ce9d27890c49869ca993777bce9dc11`; comparison with `main`: 75 commits ahead and 150 behind. The branch remains diverged; no rebase or merge was performed.

- Final account-switch hardening after the initial recovery implementation: the password update request is now pinned to the expected recovered user ID at the authenticated request boundary, not just checked before dispatch. A concurrent sign-in cannot cause a recovery action to update a different account. Final code commit: `9a9f31c9119dadd977b2a75c0e4662ebbcd04fcf`.
- The latest Android PR-check run for that final code commit is [43078050529](https://github.com/nastechtest/Vinz/actions/runs/43078050529), pending with no jobs/results exposed at the latest poll. The prior run for `eabb0fa` was cancelled when the final account-switch hardening was pushed. No new APK is available.

## Read-only Supabase avatar-storage inspection — 2026-11-11

- Rechecked the connected active project `Vinz` (`iszghebmetrbkfhnnoqq`) after the recovery increment. Live migration history still ends at `20261108082934_vinz_youtube_music_import`; the repository-only verified-email, public username and username RPC hardening migrations are not applied.
- Live `storage.buckets` inspection found `vinz-cloud-backups` (private, 50 MiB) but no `vinz-avatars` bucket. Live `storage.objects` policy inspection returned no avatar policies.
- Repository schema already has `vinz_profiles.avatar_path` and the verified-email/owner-folder avatar write policy migration, but no bucket-creation migration or Android upload/change/remove flow yet.
- No production bucket, policy, schema, Auth setting or migration was changed. The next profile increment must add a repository migration for a constrained avatar bucket, implement validated image processing/upload and profile-path updates, and test failure/replace/remove behavior. Applying the migration to the connected project remains blocked on explicit approval.


## Avatar upload implementation — 2026-11-12

- Inspected current branch head `021d4c59f8e74b1cee6aaeb86d580ae080cbee7f`, merged/closed PR #37, the existing Android PR-check workflow and the latest push run [43078050529](https://github.com/nastechtest/Vinz/actions/runs/43078050529).
- The latest Android run completed 1,208 JVM tests with one failure: `I18nGuardTest.noTextIsWrittenInTheCode` found a hardcoded recovery-session message in `CloudAccountController.kt`. The message now uses the controller's existing localized failure mapping rather than a literal English exception string.
- Added Android avatar selection using the system image picker; source images are bounded to 12 MiB, restricted to JPEG/PNG/WebP, decoded with dimension/pixel caps, center-cropped to 512×512 and re-encoded as metadata-stripped WebP. The resulting upload is capped at 5 MiB, matching the existing bucket policy.
- Added authenticated Supabase Storage upload/delete requests with the same proactive refresh and one-time 401 retry as account REST calls. Each upload uses a unique object path under the current user's UUID folder; profile `avatar_path` is updated only after upload succeeds. Replacing an avatar cleans up the previous owned object; removal clears the profile reference and deletes only a path inside the same user's folder. Account-switch checks prevent stale upload results from painting another user's profile.
- Added controller tests for upload, removal and account-switch races, plus Robolectric image tests for square WebP output, MIME mismatch, corrupt data and source-size limits.
- The existing repository migration `20261107200000_vinz_account_2_username_and_avatar_hardening.sql` already creates the public-read `vinz-avatars` bucket (5 MiB; JPEG/PNG/WebP) and owner-folder write policies; `20261108144000_vinz_require_verified_email_for_avatar_writes.sql` adds the verified-email gate. No new schema migration is required for this Android increment.
- Live Supabase read-only inspection on 2026-11-12 confirms the only Storage bucket is `vinz-cloud-backups`; there is no live `vinz-avatars` bucket or avatar object policy. Live migrations still end at `20261108082934_vinz_youtube_music_import`. Therefore avatar uploads are implemented in the branch but cannot work against the connected production project until the user explicitly approves applying the existing repository migrations. No migration, bucket or policy was applied.
- The same live Security Advisor still warns that `public.vinz_username_available(text)` is SECURITY DEFINER and that leaked-password protection is disabled. The branch has a repository-only hardening migration, but it is not applied; do not expose the live RPC or claim the production warning is fixed.
- Android validation for this increment must pass before claiming a verified build or APK. No production Supabase or Cloudflare resources, secrets, deployments, releases, main-branch refs or PR state were changed.
