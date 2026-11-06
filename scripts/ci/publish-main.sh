#!/usr/bin/env bash
# Publish the one mutable development prerelease. The helper has no git dependency; it verifies
# downloaded build artifacts and names the repository explicitly for every GitHub CLI call.
set -euo pipefail

: "${GH_REPO:?GH_REPO is required (owner/repository)}"
: "${TARGET_SHA:?TARGET_SHA is required (the commit actually built)}"
: "${APK_NAME:?APK_NAME is required}"
: "${LABEL:?LABEL is required}"
: "${VERSION_NAME:?VERSION_NAME is required}"

readonly rolling_tag="main-latest"
readonly dist_dir="${DIST_DIR:-dist}"
readonly apk="${dist_dir}/${APK_NAME}"
readonly checksum_file="${apk}.sha256"

if [[ ! "$TARGET_SHA" =~ ^[0-9a-fA-F]{40}$ ]]; then
  printf 'TARGET_SHA must be a full 40-character commit SHA, got: %s\n' "$TARGET_SHA" >&2
  exit 2
fi
if [[ ! -f "$apk" || ! -s "$apk" || ! -f "$checksum_file" ]]; then
  printf 'Missing staged APK or checksum: %s and %s\n' "$apk" "$checksum_file" >&2
  exit 2
fi
checksum=$(tr -d '[:space:]' < "$checksum_file")
if [[ ! "$checksum" =~ ^[0-9a-fA-F]{64}$ ]]; then
  printf 'Invalid SHA-256 in %s\n' "$checksum_file" >&2
  exit 2
fi
actual_checksum=$(sha256sum "$apk" | awk '{print $1}')
if [[ "$actual_checksum" != "$checksum" ]]; then
  printf 'Checksum mismatch for %s\n' "$apk" >&2
  exit 2
fi

# Fail before moving the rolling tag when the supplied build SHA is inaccessible. This also keeps a
# stale workflow-dispatch event from pointing Main — Latest at a commit other than the one it built.
gh api "repos/${GH_REPO}/git/commits/${TARGET_SHA}" >/dev/null

work_dir=$(mktemp -d)
trap 'rm -rf "$work_dir"' EXIT
notes_file="${work_dir}/release-notes.md"
cat > "$notes_file" <<EOF
**DEVELOPMENT BUILD — NOT A STABLE RELEASE**

Automatic build of \`main\`. It may be broken, half-finished or unreviewed by anyone but its author.
For a build you can rely on, use the latest stable release.

| | |
| --- | --- |
| Build | \`${LABEL}\` |
| versionName | \`${VERSION_NAME}\` |
| Commit | \`${TARGET_SHA}\` |
| APK | \`${APK_NAME}\` |
| SHA-256 | \`${checksum}\` |

### Install

\`\`\`bash
adb install -r ${APK_NAME}
\`\`\`

Debug-signed: uninstall an official Vinz first, since Android will not apply an update signed with
a different key. Settings -> About shows \`${VERSION_NAME}\`.
EOF

# Only this non-version tag is mutable. Ref/release authorization and network failures must abort,
# not masquerade as missing resources. Check them before uploading or moving anything.
ref_exists=false
if ref_error=$(gh api "repos/${GH_REPO}/git/ref/tags/${rolling_tag}" 2>&1); then
  ref_exists=true
elif [[ "$ref_error" != *"HTTP 404"* ]]; then
  printf '%s\n' "$ref_error" >&2
  exit 1
fi

move_rolling_tag() {
  if [[ "$ref_exists" == true ]]; then
    gh api --method PATCH "repos/${GH_REPO}/git/refs/tags/${rolling_tag}" \
      -f sha="$TARGET_SHA" -F force=true >/dev/null
  else
    gh api --method POST "repos/${GH_REPO}/git/refs" \
      -f ref="refs/tags/${rolling_tag}" -f sha="$TARGET_SHA" >/dev/null
  fi
  echo "${rolling_tag} -> ${TARGET_SHA}"
}

if release_error=$(gh api "repos/${GH_REPO}/releases/tags/${rolling_tag}" 2>&1); then
  # Upload first: a failed upload must not move the existing release's tag. Build-specific names
  # preserve its old working assets until the new APK and checksum have both uploaded successfully.
  gh release upload "$rolling_tag" \
    "$apk#${APK_NAME}" "$checksum_file#${APK_NAME}.sha256" \
    --repo "$GH_REPO" --clobber
  move_rolling_tag
  gh release edit "$rolling_tag" \
    --repo "$GH_REPO" --target "$TARGET_SHA" \
    --title "Main — Latest (development)" --notes-file "$notes_file" \
    --prerelease --latest=false
elif [[ "$release_error" == *"HTTP 404"* ]]; then
  move_rolling_tag
  gh release create "$rolling_tag" \
    "$apk#${APK_NAME}" "$checksum_file#${APK_NAME}.sha256" \
    --repo "$GH_REPO" --target "$TARGET_SHA" \
    --title "Main — Latest (development)" --notes-file "$notes_file" \
    --prerelease --latest=false
else
  printf '%s\n' "$release_error" >&2
  exit 1
fi

# Build-specific filenames change on each run. Remove superseded Vinz main assets only AFTER success,
# otherwise downloaders that choose the first APK would silently keep fetching an old build.
assets=$(gh release view "$rolling_tag" --repo "$GH_REPO" --json assets --jq '.assets[].name')
while IFS= read -r asset; do
  [[ "$asset" != "$APK_NAME" && "$asset" != "${APK_NAME}.sha256" ]] || continue
  if [[ "$asset" == Vinz-main-*-build-*.apk || "$asset" == Vinz-main-*-build-*.apk.sha256 ||
        "$asset" == Vinz-*-main.*.apk || "$asset" == Vinz-*-main.*.apk.sha256 ]]; then
    gh release delete-asset "$rolling_tag" "$asset" --repo "$GH_REPO" --yes
  fi
done <<< "$assets"

printf 'Latest development build: %s (%s)\n' "$LABEL" "$TARGET_SHA"
