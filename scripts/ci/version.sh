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
  ""|-h|--help|help) sed -nE 's/^# ?//p' "$0" | sed -n '1,20p' ;;
  *) die "unknown subcommand '$sub' (try: version.sh help)" ;;
esac
