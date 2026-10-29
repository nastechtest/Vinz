#!/usr/bin/env bash
# Runs the JVM unit tests that are deterministic in CI.
#
# Vinz has 111 JVM tests; the *LiveTest and *ApiTest classes call real YouTube Music / lyric endpoints and
# fail on network hiccups or upstream changes, so they are excluded here (this mirrors the filter the
# repository's CI already used). Everything else -- Robolectric, Compose UI, Room migration, audio DSP --
# runs on the JVM and is safe to gate a merge on.
#
#   run-unit-tests.sh [extra gradle args...]
set -euo pipefail

cd "${GITHUB_WORKSPACE:-$(git rev-parse --show-toplevel)}"

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
