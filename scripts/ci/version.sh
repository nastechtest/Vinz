#!/usr/bin/env bash
# Version helpers for CI. The single source of truth is version.properties at the repo root; this script
# only *derives* build labels from it, so no workflow has to parse Gradle files or duplicate the rules.
#
#   version.sh name                    versionName at HEAD            (e.g. 1.7.7.1)
#   version.sh code                    versionCode at HEAD            (e.g. 47)
#   version.sh main                    main development label         (e.g. v1.7.7.1-main.15)
#   version.sh main-count              commits on the integration branch (the "15" above)
#   version.sh pr <number> <build>     PR label                       (e.g. pr-42-1.7.7.1-3)
#   version.sh name-at <git-ref>       versionName at a ref
#   version.sh code-at <git-ref>       versionCode at a ref
#   version.sh previous-stable-tag     highest non-prerelease v* tag, empty if none
#   version.sh latest-stable-version   the version part of that tag (1.7.7), empty if none
#   version.sh bump-name <kind>        next versionName for patch|minor|major
#   version.sh bump-code               next versionCode (always higher than anything published)
#   version.sh build-code <build>      versionCode for a channel build: VinzM's or VinzP's own counter
#
# Exit codes: 0 success, 1 usage/environment error.
set -euo pipefail

die() { printf 'error: %s\n' "$*" >&2; exit 1; }

repo_root=$(git rev-parse --show-toplevel 2>/dev/null) || die "not inside a git repository"

# A shallow clone silently reports a wrong commit count, which would hand two different commits the same
# main build number. Refuse rather than produce a duplicate version.
require_full_history() {
  [[ $(git -C "$repo_root" rev-parse --is-shallow-repository) == false ]] ||
    die "shallow clone: fetch full history (actions/checkout fetch-depth: 0) before deriving build numbers"
}

read_key() { # <key> <"file" | git-ref>
  local key=$1 source=$2 content value
  if [[ $source == file ]]; then
    [[ -f $repo_root/version.properties ]] || die "version.properties not found at the repository root"
    content=$(cat "$repo_root/version.properties")
  else
    content=$(git -C "$repo_root" show "$source:version.properties" 2>/dev/null) ||
      die "version.properties does not exist at $source"
  fi
  value=$(printf '%s\n' "$content" |
    sed -nE "s/^[[:space:]]*${key}[[:space:]]*=[[:space:]]*([^[:space:]]+)[[:space:]]*\$/\1/p" |
    head -1)
  [[ -n $value ]] || die "$key is missing or empty in version.properties ($source)"
  printf '%s' "$value"
}

cmd_name() { read_key versionName file; }

cmd_code() {
  local code
  code=$(read_key versionCode file)
  [[ $code =~ ^[0-9]+$ ]] || die "versionCode must be an integer, got '$code'"
  printf '%s' "$code"
}

cmd_name_at() { [[ $# -eq 1 ]] || die "usage: version.sh name-at <git-ref>"; read_key versionName "$1"; }

cmd_code_at() {
  [[ $# -eq 1 ]] || die "usage: version.sh code-at <git-ref>"
  local code
  code=$(read_key versionCode "$1")
  [[ $code =~ ^[0-9]+$ ]] || die "versionCode at $1 must be an integer, got '$code'"
  printf '%s' "$code"
}

# The integration branch whose length defines the main build number. Defaults to the local/remote main.
cmd_main_count() {
  local ref=${1:-main}
  git -C "$repo_root" rev-parse --verify --quiet "$ref^{commit}" >/dev/null ||
    ref=origin/main
  git -C "$repo_root" rev-parse --verify --quiet "$ref^{commit}" >/dev/null ||
    die "cannot find a 'main' ref to count commits on"
  require_full_history
  git -C "$repo_root" rev-list --count "$ref^{commit}"
}

cmd_main() {
  printf 'v%s-main.%s' "$(cmd_name)" "$(cmd_main_count)"
}

cmd_pr() {
  [[ $# -eq 2 ]] || die "usage: version.sh pr <pr-number> <build-number>"
  local pr=$1 build=$2
  [[ $pr =~ ^[0-9]+$ ]] || die "PR number must be numeric, got '$pr'"
  [[ $build =~ ^[0-9]+$ ]] || die "build number must be numeric, got '$build'"
  printf 'pr-%s-%s-%s' "$pr" "$(cmd_name)" "$build"
}

# Prereleases carry a hyphen after the version (v1.7.8-rc.1); stable tags do not (v1.7.8).
cmd_previous_stable_tag() {
  git -C "$repo_root" tag -l 'v[0-9]*' | grep -v -- '-' | sort -V | tail -1 || true
}


# The highest version anyone has claimed: what version.properties says, and what the newest stable tag
# says. Normally these agree. When a release pushed a bump that never reached main, they don't, and the
# newer of the two wins so a version is never handed out twice.
highest_version() {
  local file tag
  file=$(cmd_name)
  tag=$(cmd_latest_stable_version) || tag=""
  if [[ -z $tag ]]; then printf '%s' "$file"; return; fi
  printf '%s\n%s\n' "$file" "$tag" | sort -V | tail -1
}

cmd_latest_stable_version() {
  local tag
  tag=$(cmd_previous_stable_tag)
  [[ -n $tag ]] || return 1
  printf '%s' "${tag#v}"
}

# patch bumps the last number (1.7.7.1 -> 1.7.7.2; 1.7.8 -> 1.7.9); minor and major bump their position
# and reset everything after it (1.7.7.1 -> 1.8.0.0 -> 2.0.0.0). The number of parts never changes.
cmd_bump_name() {
  [[ $# -eq 1 ]] || die "usage: version.sh bump-name <patch|minor|major>"
  local kind=$1 base i
  case "$kind" in
    patch|minor|major) ;;
    *) die "bump must be patch, minor or major, got '$kind'" ;;
  esac
  base=$(highest_version)
  [[ $base =~ ^[0-9]+(\.[0-9]+)+$ ]] || die "cannot bump '$base': not a dotted numeric version"
  IFS=. read -ra parts <<< "$base"
  local last=$(( ${#parts[@]} - 1 ))
  case "$kind" in
    patch) parts[last]=$(( parts[last] + 1 )) ;;
    minor) parts[1]=$(( parts[1] + 1 )); for ((i=2; i<=last; i++)); do parts[i]=0; done ;;
    major) parts[0]=$(( parts[0] + 1 )); for ((i=1; i<=last; i++)); do parts[i]=0; done ;;
  esac
  local out
  out=$(IFS=.; printf '%s' "${parts[*]}")
  printf '%s' "$out"
}

# The versionCode a stable tag was released with. Release tags are annotated and carry it in their
# message ("versionCode: 48"), so it survives even though the APK is stamped rather than committed and
# version.properties therefore still says whatever it said before. Falls back to the file at that tag for
# tags made before this was recorded.
stable_tag_code() {
  local tag=$1 recorded=""
  recorded=$(git -C "$repo_root" tag -l --format='%(contents)' "$tag" 2>/dev/null |
    sed -nE 's/^[[:space:]]*versionCode:[[:space:]]*([0-9]+)[[:space:]]*$/\1/p' | head -1)
  if [[ -n $recorded ]]; then printf '%s' "$recorded"; return 0; fi
  cmd_code_at "$tag"
}

# Always one more than the highest versionCode ever released, which is the file's or the newest stable
# tag's recorded one: Android refuses an APK whose versionCode does not increase.
cmd_bump_code() {
  local file high tag recorded
  file=$(cmd_code)
  high=$file
  # Every stable tag, not just the newest: a tag made before versionCodes were recorded must not hide a
  # higher one recorded beside it.
  while IFS= read -r tag; do
    [[ -n $tag ]] || continue
    recorded=$(stable_tag_code "$tag" 2>/dev/null || true)
    [[ $recorded =~ ^[0-9]+$ ]] || continue
    if [[ $recorded -gt $high ]]; then high=$recorded; fi
  done <<< "$(git -C "$repo_root" tag -l 'v[0-9]*' | grep -v -- '-' || true)"
  printf '%s' $(( high + 1 ))
}

# versionCodes for the side-by-side channels. VinzM and VinzP are separate packages (com.vinz.music.main and
# com.vinz.music.pr), so their codes only have to increase within their own app -- but they do have to increase,
# or Android refuses the update and the in-app updater rejects the APK as "not newer". Multiplying the stable
# code by 1000 leaves room for 1000 builds per release to bump it, and keeps a development build's code in a
# band of its own instead of colliding with the stable numbering.
cmd_build_code() {
  [[ $# -eq 1 ]] || die "usage: version.sh build-code <build-number>"
  local build=$1
  [[ $build =~ ^[0-9]+$ ]] || die "build number must be numeric, got '$build'"
  printf '%s' $(( $(cmd_code) * 1000 + build ))
}

sub=${1:-}
shift || true
case "$sub" in
  name) cmd_name ;;
  code) cmd_code ;;
  main) cmd_main ;;
  main-count) cmd_main_count "$@" ;;
  pr) cmd_pr "$@" ;;
  name-at) cmd_name_at "$@" ;;
  code-at) cmd_code_at "$@" ;;
  previous-stable-tag) cmd_previous_stable_tag ;;
  latest-stable-version) cmd_latest_stable_version ;;
  bump-name) cmd_bump_name "$@" ;;
  bump-code) cmd_bump_code ;;
  build-code) cmd_build_code "$@" ;;
  ""|-h|--help|help) sed -nE 's/^# ?//p' "$0" | sed -n '1,20p' ;;
  *) die "unknown subcommand '$sub' (try: version.sh help)" ;;
esac
