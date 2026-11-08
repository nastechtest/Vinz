# Vinz Account — implementation plan and progress

This PR's immediate scope is the Vinz account experience: authentication, complete user profiles, profile/account settings, and the cloud services needed to support them. Do not broaden this work into a general app redesign or unrelated music-feature projects.

- Repository: `nastechtest/Vinz`
- Branch: `feat/frontend-experience-refresh`
- Single review PR: [#37](https://github.com/nastechtest/Vinz/pull/37)
- Backend direction: Supabase is the account/auth/data foundation. Use Cloudflare only for the specific server-side/edge needs already agreed and present in the project. Inspect actual configuration/resources before implementing.
- Product rule: local music, playback and local library remain usable without signing in.
- Do not push to `main`, merge, deploy, or modify production cloud resources without explicit approval.

## Agreed implementation order

### Phase 1 — Vinz Identity and login
- [ ] Frontend-first account entry and welcome experience.
- [ ] Vinz email/password registration and login.
- [ ] Google sign-in.
- [ ] Email verification and resend flow.
- [ ] Forgot/reset password and recovery flow.
- [ ] Session persistence, expiry/refresh, sign-out and account switching.
- [ ] Loading, validation, network failure, duplicate account and authentication error states.
- [ ] Keep existing YouTube Music login independent; do not confuse it with a Vinz Account.

### Phase 2 — Complete user profile pages
- [ ] Account/profile overview page.
- [ ] Public profile page and private account details separated clearly.
- [ ] Username, display name, full name (private by default), bio, avatar/photo, and profile URL.
- [ ] Edit profile flow with validation, username availability/uniqueness, avatar upload/change/remove and save feedback.
- [ ] Profile preview and visibility controls.
- [ ] Correct empty/loading/offline/error states.
- [ ] Profile data backed by Supabase and protected by ownership-aware RLS and storage policies.

### Phase 3 — Profile settings and account management
- [ ] Profile and personal information settings.
- [ ] Privacy, discoverability and profile visibility.
- [ ] Notification preferences.
- [ ] Connected accounts and Google/email identity linking where supported.
- [ ] Active sessions/devices and sign-out controls.
- [ ] Password/security controls and account recovery.
- [ ] Account management, data export/deletion pathway where supported.
- [ ] Clear explanation of what is public, private, local and cloud-synced.

### Phase 4 — Cloud backup and restore tied to Vinz Account
- [ ] Upgrade the existing backup/restore flow, do not replace it.
- [ ] Cloud backup available to signed-in Vinz Accounts.
- [ ] Preserve the agreed lightweight backup rule: only use that path when the backup is below 50 MB.
- [ ] Clearly identify excluded large media/files; never claim they were backed up.
- [ ] Restore preview, integrity checks, progress, retry and compatibility.
- [ ] Keep local backup/restore options and existing behavior.

### Phase 5 — Community foundation (later, after account/profile)
- [ ] Build on the completed identity and profile foundation.
- [ ] User discovery and community entry points, privacy and consent.
- [ ] Wi-Fi/community sharing is later scope, not a reason to expand the current login/profile implementation prematurely.

## Supabase and Cloudflare responsibilities

- [ ] Inspect the repository's actual Supabase and Cloudflare configuration and connected project resources before deciding implementation details.
- [ ] Supabase: Auth, profile records in Postgres, RLS, avatar/profile Storage, and any required session/data functionality.
- [ ] Cloudflare: only the specific Worker/API/edge or server-side secret functions the current architecture actually requires; do not add products or infrastructure speculatively.
- [ ] Never place Supabase service-role/secret keys or Cloudflare API tokens in Android code, client configuration, logs or source control.
- [ ] Use publishable/client configuration only in the app; keep privileged secrets server-side.
- [ ] RLS must enforce per-user ownership; storage upload/read/update policies must match intended profile access.
- [ ] No production schema changes, secret rotation, resource creation or deployment without explicit approval.

## Existing branch work (not the main account deliverable)

- [x] Recognition AMOLED orb and event-specific notification icons are already in earlier commits on this branch. They are existing branch changes, not permission to broaden the current priority into unrelated features.

## Out of immediate scope

Do not start a general refresh of every music page, rebuild Sing, redesign Podcasts, expand music-provider research, implement BantuLyrics, or add broad social/community features before the account phases above are delivered. These can remain separate future workstreams unless the user explicitly reprioritizes them.

## Delivery and review discipline

- Implement frontend account pages first, then connect them to Supabase and only the necessary Cloudflare services.
- Inspect existing architecture and preserve current app behavior and user data.
- Make meaningful, reviewable code increments on `feat/frontend-experience-refresh` only.
- Update this document and PR #37 with exact completed work and verified test results.
- Keep one PR (#37), draft until the agreed account scope is implemented and validated. Never merge without explicit approval.
- Provide APK links only when an artifact is actually built, uploaded and verified.
- Report blockers honestly; do not claim planned features are implemented.
