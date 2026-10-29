# Releasing Vinz

Three pipelines, one for each kind of build. Nothing publishes a **stable** release without a human
approving it.

| Build | Trigger | Identifier | Published as | Signed with |
| --- | --- | --- | --- | --- |
| Pull request | Opening or pushing to a PR | `pr-42-1.7.7.1-3` | Actions artifact `Vinz-PR-42-build-3.apk` | debug key |
| Main development | Every push to `main` | `v1.7.7.1-main.15` | Prerelease **Main — Latest** + Actions artifact | debug key |
| Stable | `workflow_dispatch` + approval | `v1.7.8` | GitHub Release `Vinz-1.7.8-release.apk` | release key |

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

## 3. Stable release (approval required)

A stable release is never triggered by a push, a merge, or a tag. It is started by hand, validated, and
then published only after a reviewer approves the `stable-production` environment.

### Step 1 — prepare the version, in a pull request

Edit `version.properties` at the repository root:

```properties
versionName=1.7.8
versionCode=48
```

`versionCode` **must** be greater than the versionCode of the previous stable release, otherwise Android
refuses to install the update. The workflow enforces this by reading the previous release tag out of git.

Open the PR against `main`, let CI pass, merge it, then promote it:

```bash
gh pr create --base stable --head main --title "Release 1.7.8" --body "Release candidate for 1.7.8"
```

### Step 2 — start the workflow

Actions → **Stable release** → *Run workflow*:

| Input | Value |
| --- | --- |
| `version` | `1.7.8` (no `v` prefix; must equal `versionName`) |
| `commit_sha` | Full 40-character SHA of the merge commit on `stable` |
| `release_notes` | Optional highlights and known issues; goes at the top of the notes |

```bash
gh workflow run stable-release.yml -f version=1.7.8 -f commit_sha=<sha> -f release_notes="..."
```

### Step 3 — what happens before approval

The **Validate the release candidate** job runs with `contents: read` and **no access to signing
secrets**, so a bad request cannot touch the release key. It fails unless all of this holds:

- the SHA is well formed, exists, and is an ancestor of `stable`;
- `version.properties` at that commit says exactly the requested version;
- no `v<version>` tag exists, locally or on the remote;
- `versionCode` is greater than the previous stable tag's;
- a successful **PR checks** or **Main build** run *and* a successful **CodeQL** run exist for that commit.

### Step 4 — approve

The **Sign, tag and publish** job waits on the `stable-production` environment. A required reviewer
approves in the run page (or via `gh api`). Until then, no step of that job has executed.

After approval it:

1. confirms the environment really has required reviewers (so a missing configuration cannot silently
   turn into an unapproved release);
2. re-checks that the version is still unreleased (approval can take hours);
3. runs the deterministic unit tests again;
4. imports the keystore, fails fast if the alias or password is wrong;
5. builds `:app:assembleRelease` and **rejects a debug-signed APK** — the build falls back to the debug
   key when `keystore.properties` is missing, so this is the check that prevents shipping one;
6. verifies package id, `versionName`, `versionCode` and the signer certificate;
7. creates the annotated tag `v1.7.8` on the approved commit and pushes it;
8. publishes a full (non-prerelease) GitHub Release with the APK, its SHA-256, and notes listing the
   version, changes since the previous release, commit, checksum and timestamp;
9. deletes the keystore and its password file from the runner, and confirms the release's assets.

If any step fails, nothing is published. A tag is created only after the build and verification succeed.

## Versioning: four different things

| Thing | Example | Lives in | Changes when |
| --- | --- | --- | --- |
| `versionName` | `1.7.8` | `version.properties` | A release PR bumps it |
| `versionCode` | `48` | `version.properties` | Same release PR; must strictly increase |
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

### Create the stable branch

```bash
git checkout main && git pull
git push origin main:stable
```

Then confirm the ruleset covers `refs/heads/stable` (the script does this).

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

**The wrong release candidate.** Cancel the workflow run, or simply do not approve it — an unapproved run
publishes nothing and creates no tag. Start again with the correct `commit_sha`.

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

## Worked example: PR → main → stable

```bash
# A contributor fixes lyric timing.
git checkout -b feature/lyric-offset
git commit -am "Fix lyric offset drift after seek"
git push -u origin feature/lyric-offset
gh pr create --base main --fill
#  → PR checks: wrapper OK, lint OK, 91 tests pass, Vinz-PR-64-build-1.apk uploaded.
#  → The maintainer installs the artifact on a phone, confirms the fix, re-runs the workflow
#    from a second push: Vinz-PR-64-build-2.apk, identifier pr-64-1.7.7.1-2.
#  → Approve and merge.

# main now has the commit; Main build publishes v1.7.7.1-main.12 as Vinz-main-1.7.7.1-build-12.apk
# and refreshes "Main — Latest".

# A few merged PRs later, cut a release.
git checkout main && git pull
git checkout -b release/1.7.8
#   version.properties: versionName=1.7.8, versionCode=48
git commit -am "Release 1.7.8"
git push -u origin release/1.7.8
gh pr create --base main --title "Release 1.7.8" --fill   # lands the bump on main
gh pr create --base stable --head main --title "Release 1.7.8" --fill

# With the stable merge commit SHA in hand:
gh workflow run stable-release.yml -f version=1.7.8 -f commit_sha=<sha> \
  -f release_notes="Fixes lyric offset drift after seeking."
#  → validate: green. release: waiting for approval.
#  → Reviewer approves in the run page.
#  → Tag v1.7.8 created on <sha>; Vinz-1.7.8-release.apk published on the Releases page.
```

## What this costs

Everything runs on standard `ubuntu-latest` runners with Gradle, dependency and SDK caching. A public
repository does not consume billable Actions minutes; the expensive part of each run is the Android SDK
install, which is why lint and the build share one job per workflow rather than fanning out.
