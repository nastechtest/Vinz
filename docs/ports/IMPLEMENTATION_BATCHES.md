# Selective NexaMusic-to-Vinz implementation batches

## Delivery agreement

Work in roughly ten meaningful source/test-file changes per batch, then stop for a test APK and user review before the next batch. Ten is a working size, not a quota: do not pad changes, count translation files as new features, or omit required companion resources to meet it. Each phase below may need multiple batches. No automatic merge or release.

Two focused read-only audits informed this plan: player placement/order and lyrics overlap. Current reviewed baseline is PR #30 head 78a1f2e0304ce94fb3b18744651adc5b35c48e53. Automated checks passed at that head, but the user screenshot rejects the preview layout; green checks do not imply visual acceptance.

## 1. Correct the next-song preview first

Visual target: the supplied reference with a compact next-song row BELOW the seek bar, not the current large rounded card above it. Preserve Vinz artwork, controls and appearance; this is not a whole-player redesign.

- Remove the pre-deck preview insertion in ui/player/NowPlaying.kt.
- Insert a compact preview immediately after DeckPart.SCRUBBER inside the existing configurable deck loop. It follows the seekbar if the user rearranges controls, not a fixed screen coordinate.
- Restyle ui/player/NextTrackPreview.kt to a light inline row, small static thumbnail, bounded metadata and accessible queue-opening target. No fixed-height text clipping.
- Preserve default-off settings and Media3 natural playback order, including repeat and shuffle. Do not replace or mutate the queue.
- Hide it with a hidden seekbar, minimal/small layouts, queue/lyrics/stats overlays and unsupported wide/immersive layouts. Verify visibility against actual host modes before implementing.
- Add focused layout/visibility and Compose tests, plus screenshot coverage for ordinary/compact/large-font states; keep the existing selector and settings regressions. Add a pure layout helper only if it improves testability.

Known source paths below app/src/main/java/com/vinz/music/: ui/player/NowPlaying.kt, ui/player/NextTrackPreview.kt, ui/screens/PlayerYourWay.kt. Existing tests: app/src/test/java/com/vinz/music/ui/player/NextTrackPreviewTest.kt and app/src/test/java/com/vinz/music/NextTrackPreviewSettingsTest.kt. New layout/UI tests must be explicitly identified as new files. Do not modify Settings.kt or PlayerConnection.kt merely to inflate file count.

Acceptance: compare user screenshots in portrait; next row is below seekbar, no overflow, tap opens current editable queue, defaults remain unchanged. Check shuffle/repeat, queue reorder/removal/Play Next/autoplay, offline art, TalkBack and large fonts. State which checks are automated versus performed by the user.

## 2. Missing lyrics visuals, retaining Vinz infrastructure

Typewriter already exists in LyricsMotion and LyricsStyle: do not port it again. Compare Bloom and Stage against Vinz existing styles and Nexa source, then add only distinct, compatible behavior, one bounded style batch at a time. Preserve timing, duet/backing vocals, translation, romanization, offsets, presets and reduced-motion fallback.

Known integration paths: data/prefs/Settings.kt; ui/player/LyricStyles.kt and PlayerLyrics.kt; ui/screens/LyricsYourWay.kt and LookPreview.kt; ui/tv/TvLyricsStyles.kt and TvLyricsScreen.kt. Existing tests include LyricsLookTest.kt, LyricsPartsTest.kt, LyricsLookPartsTest.kt and TvLyricsStyleTest.kt. Stage needs a distinct source-grounded visual specification before implementation; do not silently map it to an existing style and call it ported.

## 3. Lyrics sharing

Verify existing sharing/export entry points first. Add selection and a local text-sharing flow through the existing lyrics UI, with a separately tested formatter. Respect source/attribution expectations; no hidden metadata, provider credentials, remote uploads or automatic copyrighted-artwork export. Add richer share images only in a later bounded batch if requested and appropriate.

## 4. Navigation and artwork-control gaps

Compare actual source before selecting missing navigation interactions. Keep Vinz navigation and existing Canvas/cellular/download, cover blending, cover flight and backdrop controls. Clarify existing controls only where needed; do not add duplicate animated-art switches or decoders. Batch independent navigation work separately from artwork behavior.

## 5. Optional AI and internet rooms

Separate batches and source/security reviews. AI remains opt-in with protected credentials and explicit network/data handling. Internet rooms extend rather than replace local/direct Together behavior. Do not hardcode secrets, invent a backend, or claim remote functionality without a tested service.

## 6. Imports, providers and integrations

One compatible integration at a time. Verify source, maintenance, licensing, authorization, dependency compatibility, migrations, failure/offline behavior and recovery before adding it. Retain stronger Vinz implementations. Keep GPLv3 notices and source obligations. Split this phase into approximately ten-file batches rather than one oversized PR.

## Gate after every batch

1. Reviewer checks screenshot/spec fidelity, playback foundations and default-off parity.
2. Run deterministic unit tests, Android lint, debug assembly and baseline-profile module compilation on the exact final source commit.
3. New Look keys need typed registration and a human label; new English strings need all 31 translation sets in the same change. Never bypass these tests.
4. Supply the exact-commit test APK, a short change list and a focused phone checklist. Never claim device tests that were not run.
5. Wait for user testing and feedback. Fix that batch before beginning the next.

## Parallel work

Use focused reviewers for independent source comparison, test design and license/privacy checks in parallel. A single integration owner controls shared settings, NowPlaying and resource changes to avoid conflicting edits. Do not delegate one trivial lookup. Do not continue the old scheduled all-in-one coding pass while the user is reviewing this plan.
