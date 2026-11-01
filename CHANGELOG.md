# Changelog

## Unreleased

- One brand mark everywhere: the V with its cut-in music note, in the purple-to-cyan light, drawn as a single
  traced vector. It replaces the old triangle on all twelve app icons (each keeps its own ground), and is now the themed
  (monochrome) icon, the notification icon, the shortcut tiles' source of truth, and Vinz Remote's favicon and logo.
- A first-run entrance, on pure black, that is an intro rather than a splash: a point of light opens out of nothing,
  becomes a lit planet, and the camera pulls away from it through the sky it was sitting in and the orbits it leaves
  behind. "Vinz — powered by Nastech Research" arrives out of the last of that light with three lines about what this
  is. Then the sky is held into a ring at the bottom of the screen — the stars fall into it as the ring fills, and
  letting go drains it back — and the hold ends on a slash of the wordmark and a sting.
  - **It says hello.** A spoken welcome across the whole entrance (*"Welcome to Vinz."*, then each line of copy on its
    own beat, then *"Hold to begin."* as the ring lands), rendered and placed by `scripts/brand/make_intro_voice.py`
    so the voice and the captions arrive together.
  - **It can be skipped.** A Skip button from half a second in, with an action label for TalkBack: one tap fades the
    picture out and opens the app. Tapping the cinematic still lands on the ring rather than past it.
  - **It can be played again.** Settings → About → Play the entrance again runs it over the app, so it doubles as a
    preview of how Vinz opens.
  - **Nothing is black.** The first light at 0.15 s is drawn now; it was a declared constant the scene never read, which
    left the app looking dead for most of a second after launch.
  - **It holds still when it says it does.** The clock stops at the dock, so the sky no longer drifts and the near stars
    no longer grow behind a screen that is waiting for a hand.
  - 924 independently animated points, a gas giant with bands, rings and a terminator, three orbits, and a spark: all
    geometry, measured from the frame, so a phone, a 4K screen and an 8K panel draw the same picture.
    `IntroRenderTest` renders it at 2160 × 3840, 3840 × 2160 and **7680 × 4320**, and fails if a frame is black — the
    old check asked `getPixel() != 0`, which black paint is not.
  - Three sounds: a 16 s bed and a 2.4 s sting, synthesised (`scripts/brand/make_intro_audio.py`), and the spoken
    welcome above. Silent on a phone in silent mode or with media muted, and a player that cannot be made only ever
    means a quiet entrance; a failed player is released instead of being left holding a codec.
  - Held still for Reduce motion or animations off, with a tap standing in for the hold; the ring is a button node for
    TalkBack, and the picture is one decorative node, so the ways past the entrance are never a finger only and never
    behind five paragraphs of brand copy.
  - The dock now clears the navigation bar: with edge-to-edge drawing on, the ring used to sit under a three-button bar.
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
