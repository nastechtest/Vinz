# Selective NexaMusic-to-Vinz batch plan

## Corrected design direction

The user clarified that the different next-song card is a distinctive Vinz interpretation, not necessarily a placement defect. Retain the current card direction in the plan. Do not move it below the seekbar, remove it, or reproduce Nexa pixel-for-pixel without explicit approval. Similar functionality can have Vinz-native presentation. Visual differences do not establish independent authorship or remove GPL obligations for any reused/adapted code.

Planning only for now. No implementation or merge until the first batch is approved. The prior automatic continuation was stopped.

## Delivery agreement

Target roughly ten meaningful source/test-file changes per batch. Do not pad the count, treat translations as features, or omit required resources to meet a quota. Supply a verified exact-commit test APK after checks, then wait for user testing before starting the next batch. Fix feedback before progressing. Each phase can span multiple batches.

Two parallel read-only audits reviewed player integration and lyrics overlap. PR30 baseline 78a1f2e0304ce94fb3b18744651adc5b35c48e53 passed automated checks, but there is no claimed device validation.

## Proposed sequence

1. Retain and polish the distinct next-song card: responsive spacing, artwork fallback, accessible queue opening and large fonts. Verify visibility across layouts and overlays; preserve the configurable deck, default-off setting, and Media3 natural repeat/shuffle order. Do not mutate or replace the editable queue. No needless changes to working settings or playback code. Source anchors: ui/player/NowPlaying.kt, ui/player/NextTrackPreview.kt, playback/NextTrackPreview.kt, playback/PlayerConnection.kt; tests include NextTrackPreviewTest.kt and NextTrackPreviewSettingsTest.kt.
2. Missing lyrics visuals: Typewriter already exists, so do not duplicate it. Compare Bloom and Stage against Nexa source and existing Vinz styles before defining distinct additions. One style per bounded batch. Reuse Vinz timing, duet/backing vocal, translation, romanization, offset and reduced-motion models. Source anchors: data/prefs/Settings.kt, ui/player/LyricStyles.kt and PlayerLyrics.kt, ui/screens/LyricsYourWay.kt and LookPreview.kt, and TV styles/screens.
3. Lyrics selection/sharing: verify existing functionality first; add local text sharing and a tested formatter where missing. Preserve attribution/source expectations. No hidden metadata, provider secrets, remote uploads or automatic artwork export. Rich image sharing is a separate scoped decision.
4. Missing navigation/artwork interactions: preserve Vinz navigation, existing Canvas/download/cellular controls, cover blending/flight and backdrops. No duplicate artwork switches or decoders. Separate navigation and artwork batches.
5. Optional AI and internet rooms: separate security-reviewed batches. AI must be opt-in with protected credentials and clear data handling. Internet rooms must extend rather than replace local/direct Together. Do not invent a backend or claim remote behavior without tested services.
6. Imports/providers/integrations: one compatible integration at a time, retaining stronger Vinz implementations and testing migrations, offline/failure behavior and recovery. Validate authorization, dependencies and licensing before committing to implementation.

## GPL and provenance gate

Reading how a project works or learning a general idea is not itself a GPL violation. GPL-covered reuse/modification is permitted when its conditions are met. Before copying/adapting anything, inspect the actual file and dependency licenses, record origin/version and preserve applicable notices. Independently written code is not made a derivative merely by seeing source, but independence cannot be proved from screenshots or changed styling. Vinz itself also has existing license obligations.

For distributed GPLv3-covered modified works: retain required copyright/license notices, include the license and prominent dated modification notices, license the covered work as required, and provide the corresponding source for the distributed APK through a GPL-compliant method, including necessary build material. Review assets, trademarks and dependencies separately. This checklist is not a completed legal compliance audit.

Official references: https://www.gnu.org/licenses/gpl-3.0.en.html sections 2, 4, 5 and 6; https://copyright.gov/help/faq/faq-protect.html on ideas versus expression.

## Gate for every implementation batch

- Independent review for host integration, design intent, defaults and licensing.
- Unit tests, Android lint, debug assembly and applicable baseline-profile compilation on the exact final commit.
- New Look keys need typed registration and labels. New English strings require all 31 translations in the same change; never bypass those tests.
- Deliver exact-commit APK, concise changes and a focused checklist. Distinguish automated checks from actual phone testing.
- Wait for user feedback. No automatic merge or next batch.

Use parallel reviewers for independent UI/source comparison, tests and license/privacy checks. A single integration owner handles shared NowPlaying/settings/resources changes to avoid conflicts.
