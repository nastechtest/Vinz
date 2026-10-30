# Changelog

## Unreleased

- One brand mark everywhere: the V with its cut-in music note, in the purple-to-cyan light, drawn as a single
  traced vector. It replaces the old triangle on all twelve app icons (each keeps its own ground), and is now the themed
  (monochrome) icon, the notification icon, the shortcut tiles' source of truth, and Vinz Remote's favicon and logo.
- A first-run splash: the mark arriving out of a starfield, the name under it, two lines ("Your music. Your universe.",
  "Every song is a star."), and a space-like sound. It plays once per install, leaves on a tap, and holds still (no
  drift, no overshoot) when animations are switched off or Reduce motion is on.
- The tour no longer starts underneath the splash: it waits, so its first step is never missed.
- The launch window is pure black to match the splash.
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
