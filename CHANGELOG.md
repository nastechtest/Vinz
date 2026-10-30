# Changelog

## Unreleased

- One brand mark everywhere: the V with its cut-in music note, in the purple-to-cyan light, drawn as a single
  traced vector. It replaces the old triangle on all twelve app icons (each keeps its own ground), and is now the themed
  (monochrome) icon, the notification icon, the shortcut tiles' source of truth, and Vinz Remote's favicon and logo.
- A first-run entrance, on pure black, that is an intro rather than a splash: the camera pulls away from a lit planet,
  through the sky it was sitting in and the orbits it leaves behind, and "Vinz — powered by Nastech Research" arrives
  out of the last of that light with three lines about what this is. Then the sky is held into a ring at the bottom of
  the screen — the stars fall into it as the ring fills, and letting go drains it back — and the hold ends on a slash of
  the wordmark and a sting. Once per install, and tapping the cinematic lands on the hold rather than past it.
  - 924 independently animated points, a gas giant with bands, rings and a terminator, three orbits, and a spark: all
    geometry, measured from the frame, so a 1080p phone and a 4K screen draw the same picture. `IntroRenderTest` renders
    it at 2160 × 3840 and 3840 × 2160 so that claim is checked by the build.
  - Two sounds, generated rather than sampled (`scripts/brand/make_intro_audio.py`): a 13.5 s bed and a 1.6 s sting.
    Silent on a phone in silent mode, and a player that cannot be made only ever means a quiet entrance.
  - Held still for Reduce motion or animations off, with a tap standing in for the hold; the ring is a button node for
    TalkBack, so the one way past the entrance is never a finger only.
- The tour no longer starts underneath the entrance: it waits, so its first step is never missed.
- The launch window is pure black to match the entrance.
- Release in one dispatch: choose `patch`, `minor` or `major` and `preview` or `stable`, and the workflow works
  out the version, bumps the stamped build, tags it and publishes it. No version strings or commit SHAs to type,
  and no release pull request that only edits `version.properties`.
- `preview` publishes the signed build as a prerelease without claiming a stable version, so a release can be
  tried on a phone before it is published.
- A release tag now records the `versionCode` it was published with, so consecutive releases always increase it
  even though the APK is stamped rather than committed.
- Align the Android application ID, Kotlin namespace, source paths, generated names, and internal identifiers with Vinz.
- Update the file associations, remote-control header, settings/database names, and shared-format markers to use Vinz identifiers.
- Replace the tag-triggered release with a manually approved stable release: validation of the commit,
  version and CI results, a protected environment, release-signature verification, and an immutable tag.
- Add pull request, main development, and stable release workflows, with per-build APK artifacts and a
  rolling "Main — Latest" prerelease.
- Move `versionName` and `versionCode` into `version.properties` so CI and releases share one source of truth.
- Retain original copyright and third-party license notices.
