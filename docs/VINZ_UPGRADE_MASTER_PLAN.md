# Vinz upgrade programme

Status: execution baseline and staged engineering plan
Base: `main` at `478bb5da9ab54d516cb2aa007b307c881e111dd6` (2026-11-07)
Working branch: `codex/vinz-performance-and-ecosystem-upgrade`

## Non-negotiable rules

1. Upgrade the existing Vinz application; do not replace it with a new app or parallel implementation.
2. Preserve `applicationId = com.vinz.music`, Room data, DataStore preferences, backup compatibility, media-session behavior, deep links, signing identity, and current versioning rules.
3. Reuse the existing player, lyrics, library, downloads, Looks, Blend, Handoff, Connect, TV, analytics, and recommendation systems. Add capabilities through their existing repositories and interfaces.
4. Keep local music, local playback, and the local library useful without a Vinz cloud account or network access.
5. Keep privileged Supabase, Cloudflare, and provider credentials out of the Android application.
6. Do not merge draft PRs, deploy production services, rotate signing keys, or change release identity as part of an automated upgrade.
7. Every behavior change must have tests and a migration/rollback story where persistent data or network contracts are affected.
8. Do not claim a build, benchmark, or device test passed unless the corresponding workflow/device result is available.

## Repository baseline observed

- Android application: Kotlin, Jetpack Compose, Media3/ExoPlayer.
- Gradle modules currently included: `:app` and `:baselineprofile`.
- Android namespace and application ID: `com.vinz.music`.
- Current minimum Android API: 26; target API: 36; compile SDK: 37.
- The application already has a MediaLibraryService-based playback service, Room, DataStore, baseline profiles, instrumentation tests, translation generation, and extensive device integrations.
- The source tree contains hundreds of Kotlin files; the codebase is already a feature-rich application, not a starter player.
- The playback path includes queue persistence, crossfade/gapless behavior, audio processors, EQ, spatial audio, normalisation, silence-edge handling, Sing processing, media-session commands, and Android Auto integration.
- Existing product areas include local library, YouTube Music/InnerTube, Spotify playlist matching, Last.fm, lyrics, downloads, song recognition, recommendations, Replay/history, Blend/Listen Together, Looks, Chromecast/Cast, UPnP, TV, remote control, and device handoff.

Large files are a maintainability signal to investigate, not proof of a defect. In particular, `PlaybackService.kt`, `Settings.kt`, `MainActivity.kt`, and `AppContainer.kt` must only be split after their responsibilities, call sites, lifecycle ownership, and test seams are mapped. Keep the Android launcher activity and manifest aliases compatible.

## Existing in-flight work — do not duplicate

### PR #37 — frontend/account work

Branch: `feat/frontend-experience-refresh`

The draft already contains Supabase email/password account foundations, encrypted Android Keystore-backed session storage, profile fetch/update, account navigation, notification changes, and translation-resource build changes. Its own description lists unfinished OAuth/callback handling, refresh/expiry/revocation, full profile settings, storage policies, RLS validation, and cloud backup integration.

Before extending account work:
- review its complete diff and tests;
- verify auth trigger, RLS, storage, and error behavior against the actual Supabase project;
- preserve separation between Vinz Account and the YouTube Music cookie session;
- avoid duplicating account screens, session stores, profile repositories, or notification infrastructure.

### PR #38 — backend/platform work

Branch: `codex/vinz-platform-foundation`

The draft already contains Cloudflare Worker/API code, Supabase migrations/functions, discovery/notification foundations, and a Next.js web portal. Its deployment state and production claims must be verified independently; repository code is not proof of a deployed service.

Before extending platform work:
- inspect migrations in order and test clean install plus upgrade paths;
- verify RLS and grants using least privilege;
- verify idempotency, deduplication, rate limits, and secret handling;
- test Worker contracts and Supabase functions;
- do not deploy or mutate production resources without approval.

The PR #37 and #38 changed-file lists currently have no path overlap. That does not prove there are no runtime or schema dependencies between them.

## Upgrade portfolio: 20 capabilities

The following is a product backlog, not a statement that these features are absent. Each item must begin with a source audit and reuse check.

| # | Capability | Existing Vinz foundation to extend | Upgrade boundary |
|---:|---|---|---|
| 1 | AI music copilot | Search, library, MixMaker, ForYou | New opt-in assistant interface; provider-neutral; no credentials in APK |
| 2 | Natural-language music search | Existing search and InnerTube | Intent-to-existing-search adapter; preserve normal search |
| 3 | Intelligent playlist creation | MixMaker, playlists, taste data | Add explainable constraints and preview before saving |
| 4 | Collaborative playlists | Blend, Listen Together, Vinz cloud | Reuse identity and sharing; define ownership/conflict rules |
| 5 | Cloud accounts and profiles | PR #37, existing local account | Complete the existing account path; never require sign-in for local playback |
| 6 | Cloud backup and restore | Existing Backup + PR #38 | Versioned, encrypted, resumable backup; local backup remains |
| 7 | Friends and music sharing | Blend, QR/deep links, cloud platform | Privacy controls, block/report, expiring share tokens |
| 8 | Push notifications | Existing Notifications + platform work | User preferences, dedupe, quiet hours, opt-out |
| 9 | Personalised discovery | ForYou, ReleaseRadar, Taste | Better ranking, cold-start behavior, transparent controls |
| 10 | Cross-device synchronisation | Handoff, Connect, Room, cloud account | Conflict resolution and explicit sync scope |
| 11 | Adaptive headphone audio | HeadphoneEq, AudioEffects | Device/profile matching, safe limits, lifecycle tests |
| 12 | Intelligent DJ transitions | DjMode, DjSequencer, crossfade | Transition planning and measurable audio continuity |
| 13 | Karaoke improvements | Lyrics, Sing | Timing accuracy, track alignment, accessible controls |
| 14 | Music metadata management | MetaRepository, library | Safe edits, tag format support, atomic file updates |
| 15 | Smart download predictions | Downloads, offline repository | User-controlled policies, storage budget, metered-network rules |
| 16 | Offline lyrics intelligence | LyricsRepository, cache | Cache invalidation, source attribution, offline fallback |
| 17 | Podcast experience | Episode progress and Media3 | Playback speed, chapter navigation, resume and queue correctness |
| 18 | Accessibility | Existing Compose UI and translations | TalkBack, keyboard/TV focus, dynamic type, contrast and captions |
| 19 | TV experience | Existing TV screens and remote control | TV-first navigation, pairing reliability, focus and playback parity |
| 20 | Cross-device listening analytics | ReplayStats, play events, cloud platform | Privacy-first opt-in sync, deletion/export, deduplicated events |

## Engineering workstreams and acceptance criteria

### A. Playback reliability and performance

- Establish repeatable cold-start, warm-start, play-request-to-audible, seek, queue-transition, memory, CPU, and battery baselines.
- Profile on at least one lower-memory device and one current device.
- Preserve MediaLibraryService/MediaSession ownership and foreground-service requirements.
- Verify audio focus, interruptions, Bluetooth route changes, process death, service recreation, and notification/controller behavior.
- Avoid moving audio work onto the main thread; avoid introducing extra audio engines or competing player instances.
- Add regression tests for every corrected issue and compare before/after measurements.

### B. Library, database, and caching

- Treat Room as the local source of truth for persisted library state.
- Review query plans and indexes before adding indexes.
- Keep Room schema exports and migration tests in sync.
- Make cache bounds, expiry, cancellation, and offline behavior explicit.
- Avoid repeated artwork decoding, repeated metadata requests, unbounded flows, and full-library work on screen entry.
- Test large libraries, duplicate tracks, removed files, permission revocation, and interrupted scans.

### C. UI, navigation, and Looks

- Preserve existing routes, deep links, icon aliases, saved Looks, imported Looks, and user customisations.
- Profile Compose recomposition and scrolling before optimising.
- Reuse existing components and design tokens; do not add a second theme or design system.
- Verify RTL, pseudo-locales, large text, TalkBack, keyboard, and Android TV focus.

### D. Connectivity and providers

- Keep provider-specific behavior behind existing adapters.
- Add bounded timeouts, cancellation, retries with backoff, clear error mapping, and health telemetry without leaking tokens.
- Test offline, captive portal, TLS failure, provider throttling, expired sessions, and partial responses.
- Keep remote-control pairing authenticated and revocable.

### E. Cloud and identity

- Complete and validate PR #37/#38 before adding more cloud-dependent product features.
- Enforce authorization on the server; Android client validation is not a security boundary.
- Never put service-role keys, Worker secrets, or signing secrets in app resources, BuildConfig, or logs.
- Test RLS with anonymous, authenticated owner, authenticated non-owner, and service roles.
- Add export/deletion semantics before storing additional personal listening data.

## Delivery sequence

1. **Baseline and triage:** obtain a clean checkout, run the documented unit tests, lint, Android instrumentation tests, and CI; record failures before edits.
2. **Correctness fixes:** fix reproducible defects with narrow patches and regression tests.
3. **Performance:** measure, profile, optimise, and publish before/after evidence.
4. **Existing platform PRs:** reconcile #37 and #38, verify dependencies, and keep their production deployment gated.
5. **Feature increments:** implement the portfolio in small PR-sized slices, beginning with the highest-value user-facing capabilities.
6. **Release readiness:** verify upgrade installs, backup restore, signing, versioning, APK/AAB artifacts, and device matrix.

## Required evidence for each implementation PR

- Problem statement and source-level root cause.
- Existing implementation reused and duplicate avoided.
- Files and public interfaces changed.
- Unit/instrumentation tests added or updated.
- Build, lint, and test workflow links with actual outcomes.
- Before/after performance numbers for performance claims.
- Database/API migration and rollback plan where applicable.
- Security/privacy impact.
- Explicit statement of anything not tested.

## Current execution limitation

This branch is being prepared through the connected GitHub repository interface. A local Android SDK/Gradle/device environment is not available in this execution session, so no local clone, Android build, emulator run, APK, or device benchmark can honestly be reported from this session. GitHub Actions results must be checked after pushing code; failing or missing checks must remain visible rather than being represented as passed.
