# Changelog

## Unreleased

- Align the Android application ID, Kotlin namespace, source paths, generated names, and internal identifiers with Vinz.
- Update the file associations, remote-control header, settings/database names, and shared-format markers to use Vinz identifiers.
- Replace the tag-triggered release with a manually approved stable release: validation of the commit,
  version and CI results, a protected environment, release-signature verification, and an immutable tag.
- Add pull request, main development, and stable release workflows, with per-build APK artifacts and a
  rolling "Main — Latest" prerelease.
- Move `versionName` and `versionCode` into `version.properties` so CI and releases share one source of truth.
- Retain original copyright and third-party license notices.
