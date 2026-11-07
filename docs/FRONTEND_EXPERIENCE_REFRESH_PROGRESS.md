# Vinz frontend experience refresh — progress

This is the single consolidated implementation and review track for the agreed Vinz app experience. Update this document with every meaningful implementation increment; distinguish implemented work from planned work and verified work.

- Repository: `nastechtest/Vinz`
- Working branch: `feat/frontend-experience-refresh`
- Review PR: [#37 — WIP: Vinz frontend experience and notification refresh](https://github.com/nastechtest/Vinz/pull/37)
- Base: `main`
- Cloud platform direction: Supabase + Cloudflare, using the existing project configuration and deployed resources as the source of truth.
- Account model: core music use remains available without signing in; a Vinz Account is required for cloud backup, profiles, and community/social features.
- Safety: never push to `main`, merge, deploy, release, or change production cloud resources without explicit user approval.

## Implemented on the branch

- [x] Recognition screen: AMOLED-black scoped color scheme and animated orb with ambient glow, expanding rings, sweep arc, and sound-level response.
- [x] Notification infrastructure: `Notifications.post` accepts an event-specific small icon and retains the existing default for other callers.
- [x] Event-specific notification resources and wiring: downloads, recognition, new releases, and music rewind.
- [x] This progress tracker.

## Consolidated agreed scope — implementation still required unless marked above

### 1. Full app visual and interaction refresh

- [ ] Refresh every existing app surface, not only selected screens: Home, Search, Library, Player, queue, Recognition, Sing, Lyrics, Podcasts, Artist and album pages, Release Radar, playlists, downloads, history, statistics, backup/restore, account/profile, Credentials, Settings, and all navigation/drawer/bottom-bar surfaces.
- [ ] Establish one coherent premium Vinz design system: typography, spacing, colors, surfaces, cards, controls, iconography, empty/loading/error states, transitions, and responsive layouts.
- [ ] Preserve existing features and data/business logic; no wholesale rewrite, feature removal, or regressions.
- [ ] Add accessible semantics, contrast, touch targets, keyboard/focus behavior where applicable, and reduced-motion support.

### 2. Credentials page — explicit full upgrade

- [ ] Redesign the Credentials page as a clear integration and secrets-management center.
- [ ] Organize credentials by provider/service and explain what each credential enables in Vinz.
- [ ] Support safe add, edit, validate/test, rotate/update, and remove flows where the existing provider/API supports them.
- [ ] Mask secrets by default; never echo full secret values after save, expose them in logs, analytics, screenshots, or UI text, or commit them to the repository.
- [ ] Show configured/missing/invalid/expired status, validation result, last checked state where available, and actionable setup guidance.
- [ ] Separate public client configuration from privileged server secrets. Never ship Supabase service-role/secret keys or Cloudflare API tokens in the Android app.
- [ ] Clearly identify which credentials belong in Android client configuration and which must remain in server-side secrets/Cloudflare or Supabase environments.
- [ ] Provide safe copy/reveal behavior only when justified, with explicit user action and automatic re-masking; avoid insecure local persistence.
- [ ] Keep provider-specific capabilities and graceful fallbacks visible when a key is absent or a service is unavailable.

### 3. All Settings — complete audit and refresh

- [ ] Audit and redesign every existing Settings category, subpage, toggle, selector, dialog, and advanced option; do not refresh only the Settings landing page.
- [ ] Cover appearance/theme, playback/audio, queue, downloads/storage, library, lyrics/translation/romanization, recognition, notifications, new releases/watched artists, podcasts, privacy, account/security, backup/restore, integrations/credentials, data usage, accessibility, and about/support wherever present in the current app.
- [ ] Ensure every setting is wired to its real persisted state and behavior, with correct defaults, validation, restart/apply semantics, and explanatory copy.
- [ ] Add grouped search/discoverability and clear descriptions for high-impact settings.
- [ ] Preserve user settings during upgrades and migrations; do not silently reset preferences.

### 4. Supabase + Cloudflare architecture and integration

- [ ] Inspect the actual Vinz repository configuration, existing Supabase project, Cloudflare account/resources, deployment manifests, and current service usage before changing architecture. Do not invent project IDs, endpoints, bindings, or resources.
- [ ] Make Supabase and Cloudflare work together as the intended Vinz cloud foundation; document each service's actual responsibility based on the existing deployment and approved architecture.
- [ ] Supabase scope to assess and implement where already intended: Auth, Postgres/database, Row Level Security, Storage, Realtime, and Edge Functions.
- [ ] Cloudflare scope to assess and implement where already intended: edge/API routing and Workers, secure server-side integrations/secrets, caching/performance, and object delivery/storage such as R2 only where the existing design uses it.
- [ ] Keep provider secrets and privileged credentials server-side. Android receives only the minimum public configuration and short-lived/user-scoped tokens required.
- [ ] Enforce ownership-aware RLS for user profiles, preferences, backups, playlists/community data, and other private records; audit storage policies and function authorization.
- [ ] Add reliable offline/error/retry states, sync status, conflict handling, and account/session lifecycle handling.
- [ ] Add observability and health diagnostics without leaking tokens, personal data, lyrics content, or private user data.
- [ ] No production schema mutation, deployment, token rotation, or infrastructure changes without explicit approval. Prefer reviewed migrations and staged/test validation.

### 5. Vinz Account, Credentials, profile, privacy and security

- [ ] Preserve the hybrid model: listening and core local music features work without sign-in; signing in unlocks cloud backup, profile, and community/social capabilities.
- [ ] Account overview and profile: email/Google sign-in and registration, session state, avatar, username editing, account status, and sign-out.
- [ ] Privacy and security controls: account/session management, consent and visibility choices, safe sign-out, account deletion/export pathways where supported, and clear data ownership.
- [ ] Make sign-in prompts contextual; do not block local playback or local library access behind account creation.
- [ ] Explain what syncs to cloud and what remains on-device.

### 6. Backup and restore

- [ ] Upgrade existing backup/restore rather than replacing it; preserve original backup/restore capabilities and compatibility.
- [ ] Add selective/smart backup behavior: automatically use the lightweight backup path only when the backup is below 50 MB; do not pretend omitted large media/files are backed up.
- [ ] Clearly show included/excluded content, size estimate, destination, progress, last successful backup, restore preview, and failure/retry state.
- [ ] Preserve metadata, playlists, preferences, library organization, and supported app data with versioning and safe restore validation.
- [ ] Integrate cloud backup with the Vinz Account and approved Supabase/Cloudflare storage architecture; retain local/offline options where already supported.
- [ ] Test backup compatibility, partial failures, interrupted transfers, and restore integrity.

### 7. Bantu-first Vinz and BantuLyrics

- [ ] Advance Bantu-first discovery, catalog metadata, artist/track presentation, language handling, and culturally relevant music workflows.
- [ ] Integrate BantuLyrics into the actual Vinz experience rather than treating it as a disconnected page.
- [ ] Preserve and improve lyrics display, translation, romanization, timing correction/synchronization, duet and backing-vocal handling, and existing lyrics workflows.
- [ ] Make language/provider availability transparent and support graceful fallbacks when lyrics or translations are unavailable.
- [ ] Validate language metadata and content attribution/rights before presenting provider data.

### 8. Music discovery, recognition, notifications and Release Radar

- [ ] Retain and improve the existing Release Radar; do not build a duplicate release-monitoring engine.
- [ ] Add user-controlled watched artists, per-artist notification preferences, global notification controls, and clear watch/unwatch management.
- [ ] Ensure new-release notifications respect user choices and do not notify for artists the user has not opted into under the chosen policy.
- [ ] Continue event-specific notification identities for downloads, recognition, updates, new releases, rewind, and other relevant event types.
- [ ] Improve music metadata/catalog search, artist discographies, new-release monitoring, music recognition, artwork, and provider fallback behavior based on verified provider terms and practical free tiers.
- [ ] Keep recognition visually AMOLED-black with a fluid, animated, sound-reactive orb and polished listening/synchronizing/result states.

### 9. Sing and instrumental/vocal workflows

- [ ] Upgrade the existing Sing experience toward high-quality vocal/instrumental separation without assuming a new model must be trained.
- [ ] Audit existing model/package availability and repair broken downloads or missing model assets.
- [ ] Where separation is unavailable or quality is insufficient, investigate legitimate fallback sources/workflows for obtaining instrumental and vocal-separated audio; respect source terms, copyright, and provider restrictions.
- [ ] Keep user informed about source, quality, availability, and whether processing is local or remote.

### 10. Podcasts and listening experience

- [ ] Improve podcast discovery, show/library organization, show pages, episode lists, artwork, downloads, playback progress, resume behavior, and queue integration.
- [ ] Add useful sorting/filtering and clear downloaded/in-progress/completed states.
- [ ] Preserve existing podcast playback and progress data.

### 11. Community, social and cloud features

- [ ] Account-backed profiles and community/friends features using the agreed Supabase + Cloudflare architecture.
- [ ] Privacy controls and user consent for profile visibility, social connections, and notifications.
- [ ] Wi-Fi/community sharing concepts only where supported by the agreed design and secure local-network behavior; do not expose private library data by default.

### 12. Quality, tests and delivery

- [ ] Add/extend unit, UI, integration, migration, security-policy, and regression tests for affected behavior.
- [ ] Verify Android build, lint, tests, and generated APK from the feature branch; publish a downloadable APK artifact only after GitHub confirms it exists.
- [ ] Keep one persistent draft PR (#37), update it with each meaningful code increment, and provide exact commit, compare, PR, CI, and artifact links.
- [ ] Keep the PR draft until the complete agreed scope is implemented and validated as far as the environment permits.
- [ ] Report genuine blockers and partial completion honestly. Never fabricate reviews, test results, build success, or artifact links.

## Verification at the previous checkpoint

- Branch head: `6d1c2f6f3765bac0ec058631bd52014ff24d0188`.
- PR #37 was open and draft; it was not merged.
- Prior CI run: [run 42386599801](https://github.com/nastechtest/Vinz/actions/runs/42386599801). At the last recorded inspection, CI scripts/workflows and Gradle wrapper validation passed; unit tests/debug APK build and Android Lint were still running.
- APK artifact was not yet confirmed at that checkpoint.
- Automatic Gradle dependency-submission runs are not Android build/test evidence.

## Review and change discipline

- Keep all work on `feat/frontend-experience-refresh`.
- Add commits to PR #37; do not open another PR.
- Inspect existing code, configuration, cloud resources, and documentation before implementation.
- Keep implementation increments coherent, tested, and reviewable; update this checklist and PR body as scope or status changes.
- Never merge, deploy, release, or mutate production cloud resources without explicit user approval.
