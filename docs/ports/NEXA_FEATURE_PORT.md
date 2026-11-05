# Selective NexaMusic features in Vinz

## First implementation slice

This branch implements only an optional next-track preview in the ordinary portrait player.
It is not the complete NexaMusic port and is not a release-ready claim for the later phases.
Enable it in Settings > Player > What is on the player > Show next track. It ships off.
Tapping the preview opens Vinz's existing editable queue, without skipping playback.

The target comes from Media3's current timeline, repeat mode and shuffle order, mirrored
by PlayerConnection as one flow. Repeat-one previews the current song; repeat-all wraps.
An empty/exhausted timeline or invalid current index has no preview. Song lookup uses the
QueueEntry timeline index, not a raw list offset. Queue moves, removal and autoplay refill
are observed through Vinz's existing player refresh path. The preview creates no player,
Canvas decoder, timer, queue implementation or network dependency. It uses a static
thumbnail with a local icon fallback and adds no motion.

The boolean is persisted, reversible, and registered in both Look registries so Looks,
reset and old Looks have a defined default. Existing player defaults stay unchanged.
The preview is hidden with lyrics, queue, statistics and obscuring panels. Wide/TV and
immersive layouts retain their existing layout and do not render this portrait-only card.

## Overlap decisions

- Keep Vinz's playback engine, editable queue, queued/upcoming/autoplay distinctions,
  local radio, crossfade/DJ processing and integrations. A preview is presentation only.
- Keep separate Canvas playback/cellular/download controls, artwork blending, backdrop
  fading and mini/full cover flight. Existing code already separates these concerns;
  no generic animated-cover switch or second decoder has been added.
- Preserve Vinz's lyrics model, providers, timing repair, duet/backing vocals, translation,
  romanization, export, presets and renderer choices. Typewriter/Bloom/Stage adapters
  and lyrics sharing are still unimplemented, not substituted with weaker renderers.
- Optional navigation, AI, internet rooms, providers/imports and integrations remain
  separate, unimplemented work packages needing security and compatibility review.

## Tests and release gates

New unit tests cover ordinary/exhausted/empty/invalid timelines, repeat-one, repeat-all,
shuffle flag forwarding, the default-off preference, persistence/disable and Look typing.
The shuffle forwarding test is not a device test of shuffled playback. CI results and
exact tested commits are recorded in the pull request, not assumed here.

Still required before release: on-device shuffle/reorder/Play Next/removal/autoplay tests,
crossfade transitions, queue exhaustion, TalkBack, 200% font scaling, compact screens,
offline thumbnails, reduced motion, and unchanged behavior with the setting off.
No physical-device or emulator validation is claimed.

## Provenance and licensing

Feature inspiration: NexaMusic, nkosanamahungela-dev/NexaMusic, audited revision
89d209fde3b32992b4af337ba3966a6283504415. Host baseline: nastechtest/Vinz,
f8c693d7c985621e5b91708949c5006d258a4413.

This slice is an independent implementation against Vinz's existing Media3 state.
No Nexa source file, asset, renderer or dependency has been copied into this slice.
The existing Vinz GPLv3 license and notices are retained. Future copied code/assets
require per-file attribution, compatible dependency licenses and GPL source obligations.
