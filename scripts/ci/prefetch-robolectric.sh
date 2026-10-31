#!/usr/bin/env bash
# Puts Robolectric's Android runtime jars in the local Maven repository before the tests start.
#
# Robolectric downloads its runtime jar -- android-all-instrumented, one per API level, ~110 MB each -- from Maven
# Central the first time a test needs that API level: inside the test JVM, with no retry, and a failed request is
# reported as a test failure rather than as a network problem. That is not hypothetical. On PR #19 one 404 on
# "android-all-instrumented-14-robolectric-10818077-i7.pom.sha512" failed five render tests while the other 388 in
# the same run passed, and the same suite had been green on the commit before it.
#
# Robolectric skips the network when the jar is already in the local Maven repository (MavenArtifactFetcher:
# "assume that if the file exists in the local repository, it has been fetched successfully"), so fetching it here
# -- before the JVM starts, with retries, and only ever writing a complete zip into place -- turns a coin flip into
# a step that either works or says why it did not.
#
# This never fails a test run: anything it cannot download is left to Robolectric, exactly as before it existed.
#
#   prefetch-robolectric.sh [--check]
#
#     --check   report what is missing and exit 1 if anything is; download nothing
set -uo pipefail

# The API levels these tests run at: robolectric.properties pins 34 for every test that does not say otherwise, and
# the suite also asks for 35. A level missing from this list is still fetched the old way, so a stale list costs a
# flaky run rather than a broken one; keep it in step with robolectric.properties and @Config(sdk = ...).
versions=(
  "14-robolectric-10818077-i7" # API 34
  "15-robolectric-12650502-i7" # API 35
)

repo_url="${ROBOLECTRIC_REPO_URL:-https://repo1.maven.org/maven2}"
repo_dir="${MAVEN_REPO_LOCAL:-${HOME}/.m2/repository}"
group_path="org/robolectric/android-all-instrumented"
check_only=false

for arg in "$@"; do
  case "$arg" in
    --check) check_only=true ;;
    -h|--help) echo "usage: prefetch-robolectric.sh [--check]"; exit 0 ;;
    *) echo "prefetch-robolectric: unknown argument: $arg" >&2; exit 2 ;;
  esac
done

warn() {
  if [[ -n "${GITHUB_ACTIONS:-}" ]]; then
    echo "::warning title=Robolectric prefetch::$*"
  else
    echo "prefetch-robolectric: $*" >&2
  fi
}

# Only a zip is a jar. A truncated transfer or a proxy's error page would otherwise sit in the cache looking like a
# hit and fail every test on that API level with a class-format error instead of a download.
is_jar() {
  [[ -f "$1" && "$(head -c 2 "$1" 2>/dev/null)" == "PK" ]]
}

missing=0
unavailable=0
for version in "${versions[@]}"; do
  jar="${repo_dir}/${group_path}/${version}/android-all-instrumented-${version}.jar"
  is_jar "$jar" && continue
  missing=$((missing + 1))
  if [[ "$check_only" == true ]]; then
    echo "missing: ${version}" >&2
    continue
  fi
  if ! command -v curl >/dev/null 2>&1; then
    warn "curl is not installed, so ${version} was not fetched; Robolectric will download it itself"
    unavailable=$((unavailable + 1))
    continue
  fi
  echo "Fetching Robolectric's Android ${version%%-*} runtime (android-all-instrumented-${version})"
  if ! mkdir -p "$(dirname "$jar")"; then
    warn "cannot create $(dirname "$jar")"
    unavailable=$((unavailable + 1))
    continue
  fi
  # --retry-all-errors retries the 404s and 5xx that Maven Central's CDN returns under load, which is exactly the
  # failure this script exists for. The download lands in .part and is renamed only once it is a complete zip, so
  # an interrupted run never leaves a half jar behind for the next one to trust.
  if curl --fail --silent --show-error --location \
      --retry 5 --retry-delay 2 --retry-all-errors \
      --connect-timeout 20 --max-time 900 \
      --output "${jar}.part" \
      "${repo_url}/${group_path}/${version}/android-all-instrumented-${version}.jar"; then
    if is_jar "${jar}.part"; then
      mv "${jar}.part" "$jar"
      continue
    fi
    warn "${version} arrived as something other than a jar; discarding it"
  else
    warn "could not download ${version}; Robolectric will download it itself"
  fi
  rm -f "${jar}.part"
  unavailable=$((unavailable + 1))
done

if [[ "$check_only" == true ]]; then
  [[ $missing -eq 0 ]] || exit 1
  echo "Robolectric runtimes cached: ${#versions[@]}/${#versions[@]}"
  exit 0
fi

echo "Robolectric runtimes ready: $(( ${#versions[@]} - unavailable ))/${#versions[@]} in ${repo_dir}/${group_path}"
