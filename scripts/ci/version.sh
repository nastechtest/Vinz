#!/usr/bin/env bash
# Shared version resolver: published stable tags plus the minimum target in version.properties.
# New releases and development builds use the same three-part name; build identity is kept separately.
#
#   version.sh name                    minimum unreleased target at HEAD
#   version.sh development-name        next shared three-part version (e.g. 1.8.2)
#   version.sh code                    versionCode at HEAD            (e.g. 47)
#   version.sh main                    main development label         (e.g. v1.8.2-main.15)
#   version.sh main-count              commits on the integration branch (the "15" above)
#   version.sh pr <number> <build>     PR label                       (e.g. pr-42-1.8.2-3)
#   version.sh name-at <git-ref>       versionName at a ref
#   version.sh code-at <git-ref>       versionCode at a ref
#   version.sh previous-stable-tag     highest non-prerelease v* tag, empty if none
#   version.sh latest-stable-version   the version part of that tag (1.7.7), empty if none
#   version.sh bump-name <kind>        next versionName for patch|minor|major
#   version.sh bump-code               next versionCode (always higher than anything published)
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
  printf 'v%s-main.%s' "$(cmd_development_name)" "$(cmd_main_count)"
}

cmd_pr() {
  [[ $# -eq 2 ]] || die "usage: version.sh pr <pr-number> <build-number>"
  local pr=$1 build=$2
  [[ $pr =~ ^[0-9]+$ ]] || die "PR number must be numeric, got '$pr'"
  [[ $build =~ ^[0-9]+$ ]] || die "build number must be numeric, got '$build'"
  printf 'pr-%s-%s-%s' "$pr" "$(cmd_development_name)" "$build"
}

# Prereleases carry a hyphen after the version (v1.7.8-rc.1); stable tags do not (v1.7.8).
cmd_previous_stable_tag() {
  git -C "$repo_root" tag -l 'v[0-9]*' | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+(\.[0-9]+)?$' | sort -V | tail -1 || true
}


# version.properties is the minimum *unreleased* target. Published stable tags are authoritative;
# main, PRs and stable releases all derive their next version here rather than trusting a branch copy.
cmd_latest_stable_version() {
  local tag
  tag=$(cmd_previous_stable_tag)
  [[ -n $tag ]] || return 1
  printf '%s' "${tag#v}"
}

cmd_development_name() { cmd_bump_name patch; }

# Legacy four-part tags remain immutable and readable, but every new version has three parts.
# A legacy file is rounded up (1.8.1.2 -> 1.8.2), never shortened onto an already published version.
cmd_bump_name() {
  [[ $# -eq 1 ]] || die "usage: version.sh bump-name <patch|minor|major>"
  require_full_history
  local kind=$1 floor tag base major minor micro candidate floor_name
  local -a parts
  case "$kind" in
    patch|minor|major) ;;
    *) die "bump must be patch, minor or major, got '$kind'" ;;
  esac
  floor=$(cmd_name)
  [[ $floor =~ ^[0-9]+\.[0-9]+\.[0-9]+(\.[0-9]+)?$ ]] || die "invalid version floor '$floor'"
  IFS=. read -ra parts <<< "$floor"
  major=$((10#${parts[0]})); minor=$((10#${parts[1]})); micro=$((10#${parts[2]}))
  if [[ ${#parts[@]} -eq 4 ]]; then micro=$((micro + 1)); fi
  floor_name="${major}.${minor}.${micro}"
  tag=$(cmd_latest_stable_version) || tag=""
  base=${tag:-$floor}
  IFS=. read -ra parts <<< "$base"
  major=$((10#${parts[0]})); minor=$((10#${parts[1]})); micro=$((10#${parts[2]}))
  case "$kind" in
    patch)
      if [[ -z $tag ]]; then printf '%s' "$floor_name"; return; fi
      micro=$((micro + 1)) ;;
    minor) minor=$((minor + 1)); micro=0 ;;
    major) major=$((major + 1)); minor=0; micro=0 ;;
  esac
  candidate="${major}.${minor}.${micro}"
  printf '%s\n%s\n' "$floor_name" "$candidate" | sort -V | tail -1
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
  require_full_history
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
  [[ $high -lt 2100000000 ]] || die "versionCode exhausted Android's 2100000000 limit"
  printf '%s' $(( high + 1 ))
}

sub=${1:-}
shift || true
case "$sub" in
  name) cmd_name ;;
  development-name) cmd_development_name ;;
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
  ""|-h|--help|help) sed -nE 's/^# ?//p' "$0" | sed -n '1,20p' ;;
  *) die "unknown subcommand '$sub' (try: version.sh help)" ;;
esac
