#!/usr/bin/env bash
# Runs the JVM unit tests that are deterministic in CI.
#
# Classes whose names contain Live or end in ApiTest call real YouTube Music / lyric endpoints and can
# fail on network hiccups or upstream changes, so they are excluded here. All other discovered JVM test
# classes -- Robolectric, Compose UI, Room migrations, audio DSP -- run and gate a merge.
#
#   run-unit-tests.sh [extra gradle args...]
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

cd "${GITHUB_WORKSPACE:-$(git rev-parse --show-toplevel)}"

# Fail before tests if a required Vinz brand asset is missing or has drifted.
python3 "${script_dir}/check_branding.py"

# Robolectric's POM and runtime JARs are fetched and validated before the test JVM starts. This is a hard
# prerequisite: silently falling back to Robolectric's one-shot network fetch made Maven CDN hiccups look like
# random test failures. The prefetcher retries and uses a second Maven Central endpoint before failing clearly.
"${script_dir}/prefetch-robolectric.sh"

mapfile -t sources < <(find app/src/test/java -type f -name '*Test.kt' | sort)
[[ ${#sources[@]} -gt 0 ]] || { echo "::error::no JVM test sources found under app/src/test/java" >&2; exit 1; }

args=()
skipped=0
for source in "${sources[@]}"; do
  case "$source" in
    *Live*|*ApiTest.kt)
      skipped=$((skipped + 1))
      continue
      ;;
  esac
  class_name="${source#app/src/test/java/}"
  class_name="${class_name%.kt}"
  class_name="${class_name//\//.}"
  args+=(--tests "$class_name")
done

[[ ${#args[@]} -gt 0 ]] || { echo "::error::no deterministic JVM test classes were selected" >&2; exit 1; }

echo "Running $(( ${#args[@]} / 2 )) deterministic test classes ($skipped network-dependent class(es) skipped)."
exec ./gradlew :app:testDebugUnitTest "${args[@]}" --no-daemon "$@"
