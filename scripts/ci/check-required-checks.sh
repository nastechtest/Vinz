#!/usr/bin/env bash
# Fails unless a commit has the CI evidence a stable release requires:
#   - a successful run of pr-checks.yml (the PR it came from) or main-build.yml (the merge), and
#   - a successful CodeQL run, from this repository's codeql.yml or from GitHub's default setup.
# Runs before any signing secret is touched, so an unverified commit cannot be released.
#
#   check-required-checks.sh <commit-sha>
#
# Env: GH_TOKEN (actions:read + checks:read), GITHUB_REPOSITORY.
set -euo pipefail

die() { printf '::error::%s\n' "$*" >&2; exit 1; }

sha=${1:-}
[[ $sha =~ ^[0-9a-f]{40}$ ]] || die "usage: check-required-checks.sh <full-40-char-commit-sha>"
[[ -n ${GH_TOKEN:-} && -n ${GITHUB_REPOSITORY:-} ]] || die "GH_TOKEN and GITHUB_REPOSITORY are required"

repo=$GITHUB_REPOSITORY

successful_runs() { # <workflow-file>
  gh api --paginate \
    "repos/${repo}/actions/workflows/$1/runs?head_sha=${sha}&per_page=100" \
    --jq '[.workflow_runs[] | select(.conclusion == "success")] | length' |
    awk '{ total += $1 } END { printf "%d", total + 0 }'
}

found=0
for workflow in pr-checks.yml main-build.yml; do
  count=$(successful_runs "$workflow")
  if [[ ${count:-0} -gt 0 ]]; then
    printf 'ok:    %s succeeded for %s (%s run(s))\n' "$workflow" "${sha:0:7}" "$count"
    found=1
  else
    printf 'missing: no successful %s run for %s\n' "$workflow" "${sha:0:7}"
  fi
done
[[ $found -eq 1 ]] ||
  die "commit ${sha:0:7} has no successful PR or Main build. Push it through a pull request (or main) first."

if [[ ${SKIP_CODEQL:-false} == true ]]; then
  printf 'skipped: CodeQL check disabled by SKIP_CODEQL\n'
else
  # CodeQL reports itself one of two ways. With this repository's own codeql.yml it is a workflow run; with
  # GitHub's default setup it is a check suite named "CodeQL", whose per-language analyses are the
  # "Analyze (<language>)" check runs. Default setup is what is active here -- codeql.yml is
  # disabled_manually -- so asking only for a codeql.yml run could never be satisfied, and no stable
  # release could be published at all. Accept either, and keep failing when neither is green.
  codeql_runs=$(successful_runs codeql.yml 2>/dev/null || printf '0')
  codeql_checks=$(gh api --paginate \
    "repos/${repo}/commits/${sha}/check-runs?per_page=100" \
    --jq '[.check_runs[] | select((.name == "CodeQL" or (.name | startswith("Analyze"))) and .conclusion == "success")] | length' |
    awk '{ total += $1 } END { printf "%d", total + 0 }')
  if [[ ${codeql_runs:-0} -gt 0 ]]; then
    printf 'ok:    codeql.yml succeeded for %s (%s run(s))\n' "${sha:0:7}" "$codeql_runs"
  elif [[ ${codeql_checks:-0} -gt 0 ]]; then
    printf 'ok:    CodeQL default setup succeeded for %s (%s analysis(es))\n' "${sha:0:7}" "$codeql_checks"
  else
    die "commit ${sha:0:7} has no successful CodeQL run, from codeql.yml or the default setup. Re-run it, or set SKIP_CODEQL=true with a written reason."
  fi
fi

printf '\nAll required checks are green for %s.\n' "$sha"
