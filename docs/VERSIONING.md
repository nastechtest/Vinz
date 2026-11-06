# Vinz versioning

## One public version, three numbers

New APKs use `major.minor.patch`, for example `1.8.2`, then `1.8.3`. Build channel,
commit SHA and PR/build identifiers remain in BuildConfig, artifact names and build
notes; they are not extra components of Android's visible `versionName`.

`version.properties` contains a **minimum unreleased target**, not a file that must
be rewritten in every open branch after every release. `scripts/ci/version.sh`
resolves that floor against all stable tags. Main, PR and stable release workflows
use this same resolver and fetch full history/tags. Shallow histories fail safely.

Examples:
- File `1.8.2`, latest legacy tag `v1.8.1.2`: next patch and development builds use `1.8.2`.
- After `v1.8.2` exists, without editing that file: new main/PR builds and the next
  patch release use `1.8.3`.
- A file floor higher than the next patch is respected, not bumped twice.
- `minor` and `major` select the next minor/major from stable history, respecting
  the file's minimum. With no stable tag, the file is the initial patch target.

Legacy four-part tags remain untouched. They participate in ordering and code
allocation but new releases have exactly three parts. Preview tags never advance
stable versioning. Already-built APKs are not rewritten; the next build picks up
new tags automatically. Local, non-CI builds use the file's short minimum target.

## Android update counter

`versionCode` is independent of the public name. CI computes a code greater than
the file floor and every recorded stable code, including annotated legacy tags.
Stable releases therefore cannot reuse a published code. Multiple test builds
may share the upcoming release's code; their artifact label and commit distinguish
them. Official and debug builds have different signing keys and are not mutually
updatable. The Android maximum is checked before building.

## Rolling development publishing

Only `main-latest` is mutable. The publisher verifies the staged APK checksum,
uses the actual built commit SHA and names the repository explicitly. It updates
existing prerelease assets/notes rather than deleting a working release first.
A missing ref/release is different from an authorization/network failure; errors
are no longer swallowed. Stable `v*` tags/releases are never touched by this path.

The publisher checks out its helper at the exact built SHA. Permissions are read
only for building and write only for publishing. Unit/mocked publisher tests do
not create a live release. End-to-end rolling publication is verified after these
changes reach main; a green PR alone does not prove that live write path.
