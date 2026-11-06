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

# The file is the minimum unreleased target: an ahead-of-release floor is not bumped twice.
# A stale branch automatically advances when a newer stable tag is fetched.
expect_eq "patch respects the unreleased target" "2.3.4" "$("$ci/version.sh" bump-name patch)"
expect_eq "minor resets everything after it" "2.4.0" "$("$ci/version.sh" bump-name minor)"
expect_eq "major resets everything after it" "3.0.0" "$("$ci/version.sh" bump-name major)"
expect_eq "latest stable version"            "2.3.0" "$("$ci/version.sh" latest-stable-version)"
expect_eq "versionCode is one above the file" "78"   "$("$ci/version.sh" bump-code)"
expect_fail "rejects an unknown bump kind" "$ci/version.sh" bump-name sideways

# A released tag records its versionCode, because the APK is stamped rather than committed: the file still
# says 77, but the next release must not reuse 78.
git tag -a v2.4.0 -m "Vinz 2.4.0" -m "versionCode: 90"
expect_eq "versionCode continues past the recorded one" "91" "$("$ci/version.sh" bump-code)"
expect_eq "the tag is now the highest version"        "2.4.1" "$("$ci/version.sh" bump-name patch)"
expect_eq "minor from a released tag"                 "2.5.0" "$("$ci/version.sh" bump-name minor)"

# A tag made before versionCode was recorded falls back to the file at that tag.
git tag -a v2.6.0 -m "legacy release"
expect_eq "an older tag without a record cannot hide a recorded one" "91" "$("$ci/version.sh" bump-code)"

# Legacy four-part inputs migrate to short versions without reusing an old tag.
printf 'versionName=1.7.7.1\nversionCode=47\n' > version.properties
git commit -qam "four-part version"
while IFS= read -r old_tag; do git tag -d "$old_tag" >/dev/null 2>&1 || true; done < <(git tag -l 'v*')
expect_eq "patch migrates a four-part version" "1.7.8" "$("$ci/version.sh" bump-name patch)"
expect_eq "minor migrates a four-part version" "1.8.0" "$("$ci/version.sh" bump-name minor)"
expect_eq "major migrates a four-part version" "2.0.0" "$("$ci/version.sh" bump-name major)"

# Reproduce the real stale-file/legacy-tag combination, then publish a short release without editing
# the branch file. Main, PR and stable must all move together to the next target.
printf 'versionName=1.8.2\nversionCode=57\n' > version.properties
git commit -qam "three-part target"
git tag -a v1.8.1.2 -m "legacy stable" -m "versionCode: 59"
git tag v9.9.9-preview.99
git tag vgarbage
expect_eq "legacy release resolves to 1.8.2" "1.8.2" "$("$ci/version.sh" development-name)"
expect_eq "stable patch uses the same target" "1.8.2" "$("$ci/version.sh" bump-name patch)"
expect_eq "next code passes published legacy code" "60" "$("$ci/version.sh" bump-code)"
git tag -a v1.8.2 -m "Vinz 1.8.2" -m "versionCode: 60"
expect_eq "release automatically advances development name" "1.8.3" "$("$ci/version.sh" development-name)"
expect_eq "PR ignores stale file after release" "pr-31-1.8.3-18" "$("$ci/version.sh" pr 31 18)"
expect_eq "main ignores stale file after release" "v1.8.3-main.$("$ci/version.sh" main-count)" "$("$ci/version.sh" main)"
expect_eq "next release uses same target" "1.8.3" "$("$ci/version.sh" bump-name patch)"
expect_eq "next code also advances after release" "61" "$("$ci/version.sh" bump-code)"
expect_eq "minor output has three parts" "1.9.0" "$("$ci/version.sh" bump-name minor)"
expect_eq "major output has three parts" "2.0.0" "$("$ci/version.sh" bump-name major)"
expect_eq "prerelease and malformed tags are ignored" "v1.8.2" "$("$ci/version.sh" previous-stable-tag)"

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

echo "install-emulator-runtime.sh"
runtime_bin="$fixture/emulator-runtime-bin"
runtime_log="$fixture/emulator-runtime.log"
mkdir -p "$runtime_bin"
cat > "$runtime_bin/sudo" <<'EOF'
#!/usr/bin/env bash
exec "$@"
EOF
cat > "$runtime_bin/apt-get" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$EMULATOR_RUNTIME_LOG"
EOF
cat > "$runtime_bin/ldconfig" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' 'libpulse.so.0 (libc6,x86-64) => /lib/x86_64-linux-gnu/libpulse.so.0'
EOF
chmod +x "$runtime_bin/sudo" "$runtime_bin/apt-get" "$runtime_bin/ldconfig"
runtime_output=$(PATH="$runtime_bin:$PATH" EMULATOR_RUNTIME_LOG="$runtime_log" "$ci/install-emulator-runtime.sh")
expect_contains "installs libpulse0 for the emulator" "$(<"$runtime_log")" "install --no-install-recommends -y libpulse0"
expect_contains "confirms the emulator runtime library" "$runtime_output" "libpulse.so.0"
cat > "$runtime_bin/ldconfig" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
chmod +x "$runtime_bin/ldconfig"
expect_fail "fails when libpulse.so.0 is absent after install" env PATH="$runtime_bin:$PATH" EMULATOR_RUNTIME_LOG="$runtime_log" "$ci/install-emulator-runtime.sh"

echo "apply-github-settings.sh"
output=$("$repo_root/scripts/apply-github-settings.sh" --repo example/repo --dry-run 2>&1)
expect_contains "dry run lists the branch ruleset"  "$output" "protect-main-and-stable"
expect_contains "dry run protects release tags"     "$output" "immutable-release-tags"
expect_contains "dry run requires the lint check"   "$output" '"Lint"'
expect_contains "dry run requires the test check"   "$output" '"Unit tests and debug build"'

echo "workflows"
if grep -Fq './scripts/ci/install-emulator-runtime.sh' "$repo_root/.github/workflows/icons-shapes.yml"; then
  pass "icons workflow installs emulator runtime libraries"
else
  fail "icons workflow installs emulator runtime libraries" "runtime installer is not invoked"
fi
icons_workflow=$(<"$repo_root/.github/workflows/icons-shapes.yml")
expect_contains "emulator RAM meets the API 34 floor and cores are bounded" "$icons_workflow" "-memory 2560 -cores 2"
expect_contains "emulator uses supported software GLES rendering" "$icons_workflow" "-gpu swiftshader -feature -Vulkan"
if grep -Fq -- '-gpu swiftshader_indirect' <<< "$icons_workflow"; then
  fail "emulator avoids the deprecated indirect renderer" "deprecated software renderer remains configured"
else
  pass "emulator avoids the deprecated indirect renderer"
fi
expect_contains "emulator build avoids a retained Kotlin daemon" "$icons_workflow" "-Pkotlin.compiler.execution.strategy=in-process"
expect_contains "emulator Gradle concurrency is bounded" "$icons_workflow" "--no-daemon --no-parallel --max-workers=2"
expect_contains "emulator test Gradle heap is bounded" "$icons_workflow" '-Dorg.gradle.jvmargs="-Xmx2g -Dfile.encoding=UTF-8"'
expect_contains "emulator failure records host memory evidence" "$icons_workflow" "memory.current memory.max memory.events"
expect_contains "emulator failure records process exit status" "$icons_workflow" "emulator-logs/emulator-exit-code.txt"
expect_contains "emulator captures logs before a disconnect" "$icons_workflow" "emulator-logs/logcat-live.txt"
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

bash "$repo_root/scripts/tests/publish-main-test.sh" || failures=$((failures + 1))

printf '\n%s checks, %s failure(s)\n' "$checks" "$failures"
[[ $failures -eq 0 ]]
