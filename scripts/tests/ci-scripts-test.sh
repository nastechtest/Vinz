#!/usr/bin/env bash
# Self-tests for the CI helper scripts. No Android SDK, no network, no GitHub token: everything runs
# against a throwaway git repository, so this proves the version arithmetic and the failure modes that
# the workflows depend on.
#
#   scripts/tests/ci-scripts-test.sh
set -uo pipefail

repo_root=$(git -C "$(dirname "${BASH_SOURCE[0]}")/../.." rev-parse --show-toplevel)
ci="$repo_root/scripts/ci"
failures=0
checks=0

pass() { checks=$((checks + 1)); printf '  ok    %s\n' "$1"; }
fail() { checks=$((checks + 1)); failures=$((failures + 1)); printf '  FAIL  %s\n        %s\n' "$1" "$2"; }
expect_eq() { if [[ "$2" == "$3" ]]; then pass "$1"; else fail "$1" "expected '$2', got '$3'"; fi; }
expect_fail() { local d=$1; shift; if "$@" >/dev/null 2>&1; then fail "$d" "expected a non-zero exit"; else pass "$d"; fi; }
expect_contains() { if [[ "$2" == *"$3"* ]]; then pass "$1"; else fail "$1" "'$3' not found in output"; fi; }

fixture=$(mktemp -d)
trap 'rm -rf "$fixture"' EXIT
cd "$fixture" || exit 1
git init -q .
git symbolic-ref HEAD refs/heads/main
git config user.email ci@example.invalid
git config user.name CI
git config commit.gpgsign false

printf 'versionName=2.3.4\nversionCode=77\n' > version.properties
git add version.properties
git commit -qm "base"

echo "version.sh"
expect_eq "reads versionName"            "2.3.4" "$("$ci/version.sh" name)"
expect_eq "reads versionCode"            "77"    "$("$ci/version.sh" code)"
expect_eq "formats a pull request label" "pr-9-2.3.4-2" "$("$ci/version.sh" pr 9 2)"
expect_eq "build number is commit count" "1"     "$("$ci/version.sh" main-count)"
expect_eq "formats the main label"       "v2.3.4-main.1" "$("$ci/version.sh" main)"

git commit -q --allow-empty -m one
git commit -q --allow-empty -m two
expect_eq "build number increments with commits" "3" "$("$ci/version.sh" main-count)"
expect_eq "main label follows the count"         "v2.3.4-main.3" "$("$ci/version.sh" main)"

expect_eq "no stable tag yet"  ""       "$("$ci/version.sh" previous-stable-tag)"
git tag -a v2.3.0 -m "old release"
git tag -a v2.3.9-rc.1 -m "prerelease"
expect_eq "ignores prerelease tags" "v2.3.0" "$("$ci/version.sh" previous-stable-tag)"
expect_eq "reads versionCode at a tag" "77" "$("$ci/version.sh" code-at v2.3.0)"

expect_fail "rejects a non-numeric PR number" "$ci/version.sh" pr abc 1
expect_fail "rejects a non-numeric build number" "$ci/version.sh" pr 1 abc
expect_fail "rejects an unknown subcommand" "$ci/version.sh" nonsense
expect_fail "fails when version.properties is absent" bash -c "rm -f version.properties && '$ci/version.sh' name"

echo "pr-build-number.sh"
expect_eq "falls back to GITHUB_RUN_NUMBER without a token" \
  "456" "$(PR_NUMBER=7 GITHUB_RUN_NUMBER=456 "$ci/pr-build-number.sh")"
expect_fail "rejects a missing PR number" bash -c "GITHUB_RUN_NUMBER=1 '$ci/pr-build-number.sh'"

echo "verify-apk.sh"
expect_fail "rejects a missing argument" "$ci/verify-apk.sh"
expect_fail "rejects an APK that does not exist" "$ci/verify-apk.sh" /nonexistent.apk

echo "install-android-sdk.sh"
expect_fail "fails without ANDROID_HOME" bash -c "unset ANDROID_HOME; '$ci/install-android-sdk.sh'"

echo "apply-github-settings.sh"
output=$("$repo_root/scripts/apply-github-settings.sh" --repo example/repo --dry-run 2>&1)
expect_contains "dry run lists the branch ruleset"  "$output" "protect-main-and-stable"
expect_contains "dry run protects release tags"     "$output" "immutable-release-tags"
expect_contains "dry run requires the lint check"   "$output" '"Lint"'
expect_contains "dry run requires the test check"   "$output" '"Unit tests and debug build"'

echo "workflows"
for workflow in "$repo_root"/.github/workflows/*.yml; do
  if grep -qE '^\s*permissions:' "$workflow"; then pass "$(basename "$workflow") declares permissions"; else fail "$(basename "$workflow") declares permissions" "no permissions block"; fi
done
if grep -rqE 'uses: [^ ]+@(v[0-9]|main|master)$' "$repo_root/.github/workflows/"; then
  fail "no unpinned action refs" "found an action referenced by tag instead of a commit SHA"
else
  pass "no unpinned action refs"
fi
if grep -rq 'pull_request_target' "$repo_root/.github/workflows/"; then
  fail "no pull_request_target" "pulls in untrusted code with secrets available"
else
  pass "no pull_request_target"
fi

printf '\n%s checks, %s failure(s)\n' "$checks" "$failures"
[[ $failures -eq 0 ]]
