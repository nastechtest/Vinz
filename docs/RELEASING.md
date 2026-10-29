# Releasing Vinz

Three pipelines, one for each kind of build. Nothing publishes a **stable** release without a human
approving it.

| Build | Trigger | Identifier | Published as | Signed with |
| --- | --- | --- | --- | --- |
| Pull request | Opening or pushing to a PR | `pr-42-1.7.7.1-3` | Actions artifact `Vinz-PR-42-build-3.apk` | debug key |
| Main development | Every push to `main` | `v1.7.7.1-main.15` | Prerelease **Main — Latest** + Actions artifact | debug key |
| Preview | `workflow_dispatch`, channel `preview` | `v1.7.8-preview.3` | Prerelease `Vinz-1.7.8-release.apk` | release key |
| Stable | `workflow_dispatch`, channel `stable` | `v1.7.8` | GitHub Release `Vinz-1.7.8-release.apk` | release key |

## Branch strategy

| Branch | Purpose | How changes arrive |
| --- | --- | --- |
| `main` | Integration branch. Everything merged here has passed CI. | Pull request only |
| `stable` | Production branch. What a stable release is cut from. | Pull request only (normally `main` → `stable`) |
| `feature/*` | New work | Pull request into `main` |
| `fix/*` | Bug fixes | Pull request into `main` (or `stable` for a hotfix) |
| `release/*` | Optional release preparation (version bump, last-minute fixes) | Pull request into `stable` |

`main` and `stable` are protected: no direct pushes, no force-pushes, no deletion, one approving review,
and the CI checks below must be green. See [Repository settings](#repository-settings).

## 1. Everyday changes: pull request → main

```bash
git checkout -b feature/lyrics-cache
# ... work ...
git push -u origin feature/lyrics-cache
gh pr create --base main --fill
```

The **PR checks** workflow then runs:

| Check | What it proves |
| --- | --- |
| Validate Gradle wrapper | `gradle-wrapper.jar` matches the checksum for the declared Gradle version |
| Lint | Android Lint finds no errors |
| Unit tests and debug build | 91 deterministic JVM tests pass, the app compiles, and an installable APK is produced |

The 20 `*LiveTest` / `*ApiTest` classes call real YouTube Music and lyric endpoints, so they are excluded
from CI: they fail when a remote service is slow or changes its output, which says nothing about the pull
request. Run them locally with `./gradlew :app:testDebugUnitTest` when working on stream resolution.

### Testing the APK on a phone before merging

The **Unit tests and debug build** job uploads `Vinz-PR-42-build-3.apk`. Download it from the run's
summary (the job summary links straight to it) or with the CLI:

```bash
gh run download <run-id> -n Vinz-PR-42-build-3 -R nastechtest/Vinz
adb install -r Vinz-PR-42-build-3.apk
```

It is marked **TEST BUILD — NOT AN OFFICIAL RELEASE** in the summary, the artifact contents
(`BUILD-INFO.txt`) and in Settings → About, where it appears as `1.7.7.1-pr42.3`.

PR builds use the **debug key**, so Android will not install one over an official Vinz. Either uninstall
Vinz first, or build a side-by-side copy locally with `-PsideBySide` (installs as `com.vinz.music.dev`).

The build number counts previous runs of this workflow for that PR, so it increments per push
(`-1`, `-2`, `-3`). Fork pull requests run the same workflow: it needs no secrets and holds no write
permission, so a fork cannot reach the signing key.

## 2. Development builds from main

Merging a pull request to `main` starts **Main build**:

1. Unit tests and lint run again on the merged result.
2. `versionName` is stamped `1.7.7.1-main.<N>`, where **N is the number of commits on `main`**.

Why the commit count: it comes from git history, so it cannot reset, be duplicated by two runners, or
disagree between them, and the same commit always produces the same number. A counter stored in a file or
a workflow variable has none of those properties.

3. The APK is uploaded as `Vinz-main-1.7.7.1-build-15.apk` (retained `ARTIFACT_RETENTION_DAYS`, default
   14 days).
4. The rolling prerelease **Main — Latest** is recreated, pointing at this commit. Its tag `main-latest`
   is the only ref in this repository that is ever moved; it can never collide with a `v*` release tag.

To browse history, use the Actions artifacts: each one contains the exact commit SHA and a checksum.

## 3. Releases: pick a bump, pick a channel

A release is one dispatch. Nothing is typed by hand: no version, no commit SHA. The workflow works the
version out, stamps the build, tags it, signs it and publishes it.

Actions → **Release** → *Run workflow*:

| Input | Values | Meaning |
| --- | --- | --- |
| `bump` | `patch` / `minor` / `major` | How far to move the version |
| `channel` | `preview` / `stable` | Try it first, or publish it |
| `release_notes` | optional | Highlights and known issues for the top of the notes |

```bash
gh workflow run stable-release.yml -f bump=patch -f channel=preview
gh workflow run stable-release.yml -f bump=patch -f channel=stable
```

### What a bump means

The version comes from the higher of `version.properties` and the newest stable tag, then moves:

| Bump | From `1.7.7.1` | From `1.7.8` |
| --- | --- | --- |
| `patch` | `1.7.7.2` | `1.7.9` |
| `minor` | `1.8.0.0` | `1.8.0` |
| `major` | `2.0.0.0` | `2.0.0` |

`patch` moves the last number; `minor` and `major` move their position and reset everything after it. The
number of parts never changes. `versionCode` is always one more than the last released one, which is read
back from the release tag.

### `preview`

A signed prerelease, `v1.7.7.2-preview.<run number>`, marked *prerelease* and not *latest*. It is signed
with the same release key, so it installs straight over a stable Vinz — you are trying the exact build you
are about to publish. It creates no stable tag, so running it twice claims nothing twice.

### `stable`

Tags `v1.7.7.2` on the released commit, publishes it as the latest release, and records its `versionCode`
in the annotated tag.

### What happens, in order

1. **Work out the next version** — `contents: read` and no secrets — and refuse if that stable tag already
   exists.
2. Confirm the commit already passed CI (PR checks or Main build, plus CodeQL).
3. **The approval gate.** The `stable-production` environment holds the job: nothing below has run, no
   signing key has been read, no tag exists. If that environment has no required reviewers the run goes
   straight through, and logs a warning saying it published without a human.
4. Run the deterministic unit tests.
5. Import the keystore, failing in seconds if the alias or password is wrong.
6. Build `:app:assembleRelease`, stamped with `-PciVersionName` and `-PciVersionCode`.
7. **Reject a debug-signed APK.** AGP falls back to the debug key when `keystore.properties` is missing, so
   this is the check that stops one being published.
8. Verify package id, `versionName`, `versionCode` and the signer certificate.
9. Create and push the annotated tag (stable only).
10. Publish the GitHub Release with the APK and its SHA-256.
11. Delete the keystore and its password file from the runner.

Nothing is published if any step fails, and a tag is created only after the build verifies.

### Version numbers

`version.properties` is a floor, not the record. The APK is *stamped* rather than committed, so a released
version lives in its tag — which is why the tag carries the `versionCode`. `scripts/ci/version.sh` always
takes the higher of the file and the newest stable tag, so a file that lags behind can never hand out a
version twice.

## Versioning: four different things

| Thing | Example | Lives in | Changes when |
| --- | --- | --- | --- |
| `versionName` | `1.7.8` | `version.properties`, and the release tag | The workflow stamps the build; the file is only a floor |
| `versionCode` | `48` | The release tag's annotation | Computed per release; always one more than the last |
| Git tag | `v1.7.8` | Git refs | Created once, by the release workflow. Never moved |
| APK filename | `Vinz-1.7.8-release.apk` | The release asset | Derived; not authoritative |

Development builds only alter the *stamped* `versionName` (`1.7.7.1-main.15`) and the artifact name. They
never touch `version.properties`, never create a version tag, and never change `versionCode`.

Versions follow semantic versioning: `1.7.8` is a patch release, `1.8.0` adds features, `2.0.0` is a
breaking milestone. Vinz currently uses a four-part `versionName` (`1.7.7.1`); that is fine — the workflow
accepts `X.Y.Z` and `X.Y.Z.W`, and the tag simply mirrors whatever is in the file.

## One-time setup

### Signing secrets

Settings → Secrets and variables → Actions:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 release.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | keystore password |
| `ANDROID_KEY_ALIAS` | key alias |
| `ANDROID_KEY_PASSWORD` | key password |

Use the same key for every release: Android identifies an app by its signing certificate, and a new key
means users cannot update without uninstalling.

### Protected environment

Settings → Environments → **stable-production**:

- **Required reviewers**: at least one maintainer (and *Prevent self-review* if you want a second pair of
  eyes on your own releases);
- **Deployment branches**: `stable` only.

Or, in one command, from an admin machine:

```bash
./scripts/apply-github-settings.sh --repo nastechtest/Vinz --reviewer <your-username>
```

> The workflow checks for required reviewers before publishing. If the environment does not exist, GitHub
> would create it with no protection — so configure it first.

### Repository settings

The same script applies the rulesets:

- **main and stable**: pull request required, 1 approval, stale approvals dismissed, CODEOWNERS review,
  approval of the latest push, conversation resolution, required checks (`Validate Gradle wrapper`,
  `Lint`, `Unit tests and debug build`, `Analyze (java-kotlin)`), no force-push, no deletion;
- **tags `v*`**: cannot be updated or deleted — a released version stays released.

Settings that still need a human, in Settings → Actions → General: allow the `GITHUB_TOKEN` to *read*
workflow permissions by default (the workflows request their own write scopes per job), and keep *Allow
GitHub Actions to create and approve pull requests* off.

### The `stable` branch (optional)

Releases are tagged from `main`, so a `stable` branch is not required by the release workflow. Keep it if
you want a long-lived pointer at the last release; if you do, confirm the ruleset covers
`refs/heads/stable`.

### Optional variables

| Variable | Default | Purpose |
| --- | --- | --- |
| `ARTIFACT_RETENTION_DAYS` | `14` | How long development/PR APKs and reports are kept |

## Recovering

**A build failed on `main`.** Fix it in a pull request as usual; do not force-push `main`. If you only need
the APK rebuilt without a new commit, re-run **Main build** via `workflow_dispatch`.

**A pull request build failed.** Read the failing job. Lint failures that already existed before your
change are worth their own fix PR; if you must unblock a release quickly, add a lint baseline
(`android { lint { baseline = file("lint-baseline.xml") } }`, then `./gradlew :app:lintDebug` regenerates
it) rather than disabling lint.

**The wrong release.** Cancel the run, or simply do not approve it — an unapproved run publishes nothing
and creates no tag. A preview can be thrown away with `gh release delete <tag> --cleanup-tag`, since it
never claimed a stable version. A stable release cannot be undone: tags under `v*` are immutable.

**A stable release is bad.** You cannot take an APK back from users who installed it, and you must not
rewrite history. Do this instead:

```bash
# 1. Stop advertising it as the current release (the tag and the record stay).
gh release edit v1.7.8 --prerelease --latest=false

# 2. Revert the offending commits on stable through a pull request.
git checkout -b fix/revert-1.7.8 && git revert --no-edit <commit>
gh pr create --base stable --title "Revert 1.7.8" --fill

# 3. Ship a fix release with a higher versionCode (1.7.9 / 49) through the normal stable process.
```

Never delete or move an existing `v*` tag: the tag ruleset blocks it, and the record is what lets anyone
trace an installed APK back to a commit.

**Signing failed.** The job fails before tagging, so nothing is published. Check that all four secrets are
set and that `ANDROID_KEY_ALIAS` matches the keystore (`keytool -list -keystore release.jks`).

**Lost keystore.** There is no recovery: Android will not accept an update signed with a different key.
Users would have to uninstall and reinstall, losing app data. Back the keystore up somewhere that survives
losing a laptop.

## Worked example: PR → main → release

```bash
# A contributor fixes lyric timing.
git checkout -b feature/lyric-offset
git commit -am "Fix lyric offset drift after seek"
git push -u origin feature/lyric-offset
gh pr create --base main --fill
#  → PR checks: wrapper OK, lint OK, 91 tests pass, Vinz-PR-64-build-1.apk uploaded.
#  → The maintainer installs the artifact on a phone and confirms the fix.
#  → Approve and merge.

# main now has the commit; Main build publishes Vinz-main-1.7.7.1-build-12.apk
# and refreshes "Main — Latest".

# A few merged PRs later, cut a release. No version, no SHA.
gh workflow run stable-release.yml -f bump=patch -f channel=preview \
  -f release_notes="Fixes lyric offset drift after seeking."
#  → 1.7.7.2 worked out on its own, versionCode 48.
#  → Validate: green. Release: waiting for approval.
#  → Approve: v1.7.7.2-preview.7 published as a prerelease. Install it over your stable Vinz.

# Happy with it? Publish the same version, no approval-less guessing:
gh workflow run stable-release.yml -f bump=patch -f channel=stable \
  -f release_notes="Fixes lyric offset drift after seeking."
#  → Tag v1.7.7.2 created on the released commit, versionCode 48 recorded in it.
#  → Vinz-1.7.7.2-release.apk published as the latest release.
```

Note that the second run computes the same `1.7.7.2`: a preview claims no version, so publishing after one
picks up exactly where the preview left off.

## What this costs

Everything runs on standard `ubuntu-latest` runners with Gradle, dependency and SDK caching. A public
repository does not consume billable Actions minutes; the expensive part of each run is the Android SDK
install, which is why lint and the build share one job per workflow rather than fanning out.
