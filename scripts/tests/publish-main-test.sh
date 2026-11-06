#!/usr/bin/env bash
# Contract tests for scripts/ci/publish-main.sh. A mock gh records calls: no network, token, repository,
# tag, or release is touched.
set -euo pipefail

repo_root=$(git -C "$(dirname "${BASH_SOURCE[0]}")/../.." rev-parse --show-toplevel)
publisher="$repo_root/scripts/ci/publish-main.sh"
fixture=$(mktemp -d)
trap 'rm -rf "$fixture"' EXIT
checks=0
failures=0
pass() { checks=$((checks + 1)); printf '  ok    %s\n' "$1"; }
fail() { checks=$((checks + 1)); failures=$((failures + 1)); printf '  FAIL  %s\n        %s\n' "$1" "$2"; }
expect_contains() { [[ "$2" == *"$3"* ]] && pass "$1" || fail "$1" "'$3' not found"; }
expect_not_contains() { [[ "$2" != *"$3"* ]] && pass "$1" || fail "$1" "unexpected '$3'"; }
expect_fail() { local description=$1; shift; if "$@" >/dev/null 2>&1; then fail "$description" "expected failure"; else pass "$description"; fi; }

make_mock() {
  mkdir -p "$fixture/bin"
  cat > "$fixture/bin/gh" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$GH_LOG"
case "$*" in
  "api repos/example/Vinz/git/commits/"*) exit 0 ;;
  "api repos/example/Vinz/git/ref/tags/main-latest")
    case "$GH_SCENARIO" in
      existing|release-auth|upload-fail|network) exit 0 ;;
      missing) printf 'gh: Not Found (HTTP 404)\n' >&2; exit 1 ;;
      ref-auth) printf 'gh: Resource not accessible by integration (HTTP 403)\n' >&2; exit 1 ;;
    esac ;;
  "api repos/example/Vinz/releases/tags/main-latest")
    case "$GH_SCENARIO" in
      existing|upload-fail) exit 0 ;;
      missing) printf 'gh: Not Found (HTTP 404)\n' >&2; exit 1 ;;
      release-auth) printf 'gh: Forbidden (HTTP 403)\n' >&2; exit 1 ;;
      network) printf 'connection timed out\n' >&2; exit 1 ;;
    esac ;;
  "release upload main-latest"*) [[ "$GH_SCENARIO" != upload-fail ]] || exit 7 ;;
  "release view main-latest --repo example/Vinz --json assets --jq .assets[].name")
    printf '%s\n' Vinz-main-1.2.2-build-3.apk Vinz-main-1.2.2-build-3.apk.sha256 Vinz.apk Vinz.apk.sha256 README.txt ;;
esac
MOCK
  chmod +x "$fixture/bin/gh"
}

run_publish() {
  local scenario=$1
  local dir="$fixture/$scenario"
  mkdir -p "$dir/dist"
  printf 'apk bytes for %s\n' "$scenario" > "$dir/dist/Vinz.apk"
  sha256sum "$dir/dist/Vinz.apk" | awk '{print $1}' > "$dir/dist/Vinz.apk.sha256"
  : > "$dir/gh.log"
  (cd "$dir" && PATH="$fixture/bin:$PATH" GH_LOG="$dir/gh.log" GH_SCENARIO="$scenario" \
    GH_REPO=example/Vinz TARGET_SHA=0123456789abcdef0123456789abcdef01234567 \
    APK_NAME=Vinz.apk LABEL=v1.2.3-main.4 VERSION_NAME=1.2.3 bash "$publisher")
}

make_mock
echo "publish-main.sh"
run_publish existing
existing_calls=$(cat "$fixture/existing/gh.log")
expect_contains "updates the rolling tag to built SHA" "$existing_calls" "api --method PATCH repos/example/Vinz/git/refs/tags/main-latest -f sha=0123456789abcdef0123456789abcdef01234567 -F force=true"
expect_contains "updates existing release assets" "$existing_calls" "release upload main-latest"
expect_contains "uses an explicit repository" "$existing_calls" "--repo example/Vinz"
expect_not_contains "never deletes the existing release" "$existing_calls" "release delete main-latest"
expect_not_contains "never touches stable tags" "$existing_calls" "refs/tags/v"

run_publish missing
missing_calls=$(cat "$fixture/missing/gh.log")
expect_contains "creates a missing rolling tag" "$missing_calls" "api --method POST repos/example/Vinz/git/refs -f ref=refs/tags/main-latest -f sha=0123456789abcdef0123456789abcdef01234567"
expect_contains "creates a missing prerelease" "$missing_calls" "release create main-latest"

expect_contains "pins release metadata to actual built SHA" "$missing_calls" "--target 0123456789abcdef0123456789abcdef01234567"
expect_contains "never promotes main to latest stable" "$missing_calls" "--latest=false"
expect_contains "prunes superseded main APK" "$existing_calls" "release delete-asset main-latest Vinz-main-1.2.2-build-3.apk --repo example/Vinz --yes"
expect_not_contains "keeps current APK" "$existing_calls" "release delete-asset main-latest Vinz.apk "
expect_not_contains "keeps unrelated assets" "$existing_calls" "release delete-asset main-latest README.txt "

for scenario in release-auth network upload-fail; do
  expect_fail "aborts on $scenario" run_publish "$scenario"
  calls=$(cat "$fixture/$scenario/gh.log")
  expect_not_contains "$scenario cannot move the tag" "$calls" "--method PATCH"
  expect_not_contains "$scenario cannot recreate a release" "$calls" "release create main-latest"
  expect_not_contains "$scenario keeps the previous assets" "$calls" "release delete-asset"
done

# A 403 must abort rather than being misclassified as a missing tag and creating one.
expect_fail "does not swallow tag authorization failures" run_publish ref-auth
ref_auth_calls=$(cat "$fixture/ref-auth/gh.log")
expect_not_contains "403 does not mutate a tag" "$ref_auth_calls" "--method PATCH"
expect_not_contains "403 does not create a tag" "$ref_auth_calls" "--method POST"

bad="$fixture/bad-checksum"
mkdir -p "$bad/dist"
printf apk > "$bad/dist/Vinz.apk"
printf '%064d\n' 0 > "$bad/dist/Vinz.apk.sha256"
: > "$bad/gh.log"
expect_fail "rejects an invalid staged checksum before GitHub calls" bash -c "cd '$bad' && PATH='$fixture/bin':\$PATH GH_LOG='$bad/gh.log' GH_SCENARIO=existing GH_REPO=example/Vinz TARGET_SHA=0123456789abcdef0123456789abcdef01234567 APK_NAME=Vinz.apk LABEL=x VERSION_NAME=x bash '$publisher'"
[[ ! -s "$bad/gh.log" ]] && pass "bad checksum makes no GitHub call" || fail "bad checksum makes no GitHub call" "mock was invoked"

printf '\n%s checks, %s failure(s)\n' "$checks" "$failures"
[[ $failures -eq 0 ]]
