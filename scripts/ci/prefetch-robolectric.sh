#!/usr/bin/env bash
# Pre-fetch every Robolectric Android runtime used by the JVM suite into its local Maven repository.
#
# Robolectric resolves android-all-instrumented from Maven at test runtime. A transient Maven CDN 404/5xx
# used to fail whichever test happened to trigger the fetch (PR #19: one missing .pom.sha512 failed five
# render tests while the rest of the suite passed). Prefetch both the POM and the large runtime JAR before
# the test JVM starts. Validate each file and atomically move it into the local repository only when complete.
#
# The test suite must not silently fall back to a second, un-retried network request from inside Robolectric.
# If both Maven Central endpoints are unavailable after retries, fail here with an infrastructure error,
# before any unit test is reported as failed.
#
#   prefetch-robolectric.sh [--check]
#
#     --check   report what is missing and exit 1 if anything is; download nothing
set -euo pipefail

versions=(
  "14-robolectric-10818077-i7" # API 34
  "15-robolectric-12650502-i7" # API 35
)

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

if [[ -n "${ROBOLECTRIC_REPO_URL:-}" ]]; then
  repo_urls=("${ROBOLECTRIC_REPO_URL%/}" "https://repo.maven.apache.org/maven2")
else
  repo_urls=("https://repo1.maven.org/maven2" "https://repo.maven.apache.org/maven2")
fi

is_jar() {
  [[ -f "$1" && "$(head -c 2 "$1" 2>/dev/null)" == "PK" ]] && unzip -tq "$1" >/dev/null 2>&1
}

is_pom() {
  [[ -f "$1" ]] && python3 - "$1" <<'PY'
import sys
import xml.etree.ElementTree as ET
try:
    root = ET.parse(sys.argv[1]).getroot()
    raise SystemExit(0 if root.tag.endswith("project") else 1)
except (OSError, ET.ParseError):
    raise SystemExit(1)
PY
}

download_validated() {
  local url="$1" destination="$2" kind="$3" part="${2}.part"
  local mirror
  rm -f "$part"
  for mirror in "${repo_urls[@]}"; do
    echo "Fetching $(basename "$destination") from $mirror"
    if curl --fail --silent --show-error --location \
        --retry 5 --retry-delay 2 --retry-all-errors \
        --connect-timeout 20 --max-time 900 \
        --output "$part" "$mirror/$url"; then
      if [[ "$kind" == jar ]] && is_jar "$part"; then
        mv "$part" "$destination"
        return 0
      elif [[ "$kind" == pom ]] && is_pom "$part"; then
        mv "$part" "$destination"
        return 0
      fi
      echo "prefetch-robolectric: invalid $kind returned by $mirror/$url" >&2
    else
      echo "prefetch-robolectric: download failed from $mirror/$url" >&2
    fi
    rm -f "$part"
  done
  echo "::error title=Robolectric dependency unavailable::Could not fetch and validate $url from either Maven Central endpoint." >&2
  return 1
}

missing=0
for version in "${versions[@]}"; do
  base="${group_path}/${version}/android-all-instrumented-${version}"
  jar="${repo_dir}/${base}.jar"
  pom="${repo_dir}/${base}.pom"

  if ! is_jar "$jar"; then
    missing=$((missing + 1))
    if [[ "$check_only" == true ]]; then
      echo "missing or invalid: ${base}.jar" >&2
    else
      mkdir -p "$(dirname "$jar")"
      download_validated "${base}.jar" "$jar" jar
    fi
  fi
  if ! is_pom "$pom"; then
    missing=$((missing + 1))
    if [[ "$check_only" == true ]]; then
      echo "missing or invalid: ${base}.pom" >&2
    else
      mkdir -p "$(dirname "$pom")"
      download_validated "${base}.pom" "$pom" pom
    fi
  fi
done

if [[ "$check_only" == true ]]; then
  [[ $missing -eq 0 ]] || exit 1
  echo "Robolectric POMs and runtimes cached: ${#versions[@]}/${#versions[@]}"
  exit 0
fi

echo "Robolectric POMs and runtimes are ready in ${repo_dir}/${group_path}"
