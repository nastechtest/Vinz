#!/usr/bin/env bash
# Prints the build number for a pull request: 1 for the first build of that PR, +1 for every later one.
#
# Derived from the API rather than a counter file so it is unique per PR across runners, and falls back to
# GITHUB_RUN_NUMBER (repo-wide, still unique and increasing) if the API is unavailable. Needs a read-only
# token with actions:read; it never writes anything.
#
# Env: GH_TOKEN, GITHUB_REPOSITORY, GITHUB_RUN_NUMBER, PR_NUMBER (or $1).
set -euo pipefail

pr=${1:-${PR_NUMBER:-}}
[[ $pr =~ ^[0-9]+$ ]] || { echo "error: PR number required (arg or \$PR_NUMBER)" >&2; exit 1; }

fallback() {
  printf '%s\n' "${GITHUB_RUN_NUMBER:-1}"
}

if [[ -z ${GH_TOKEN:-} || -z ${GITHUB_REPOSITORY:-} ]]; then
  echo "warning: GH_TOKEN/GITHUB_REPOSITORY unset, falling back to GITHUB_RUN_NUMBER" >&2
  fallback
  exit 0
fi

# Count the workflow runs this PR has already had. --paginate applies --jq per page, hence the awk sum.
count=$(gh api --paginate \
  "repos/${GITHUB_REPOSITORY}/actions/workflows/pr-checks.yml/runs?event=pull_request&per_page=100" \
  --jq "[.workflow_runs[] | select(.pull_requests | any(.number == ${pr}))] | length" \
  2>/dev/null | awk '{ total += $1 } END { printf "%d", total + 0 }') || count=''

if [[ ! $count =~ ^[0-9]+$ ]]; then
  echo "warning: could not count previous runs for PR #${pr}, falling back to GITHUB_RUN_NUMBER" >&2
  fallback
  exit 0
fi

printf '%s\n' "$((count + 1))"
