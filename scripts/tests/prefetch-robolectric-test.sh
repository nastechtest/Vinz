#!/usr/bin/env bash
# Offline regression test for the Robolectric prefetcher. Proves mirror fallback, artifact validation,
# and cache reuse without contacting Maven Central.
set -euo pipefail

root=$(git -C "$(dirname "${BASH_SOURCE[0]}")/../.." rev-parse --show-toplevel)
prefetch="$root/scripts/ci/prefetch-robolectric.sh"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/bin" "$tmp/home"

cat > "$tmp/bin/curl" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
out=""
url=""
while (($#)); do
  case "$1" in
    --output) out="$2"; shift 2 ;;
    --*) shift ;;
    *) url="$1"; shift ;;
  esac
done
printf '%s\n' "$url" >> "$MOCK_CURL_LOG"
# Force the first endpoint to fail, then serve a valid test artifact from the fallback endpoint.
[[ "$url" == *repo.maven.apache.org* ]] || exit 22
python3 - "$url" "$out" <<'PY'
import sys
import zipfile
url, path = sys.argv[1:]
if url.endswith(".jar"):
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("fixture.txt", "valid runtime fixture")
else:
    with open(path, "w", encoding="utf-8") as output:
        output.write('<?xml version="1.0"?><project><modelVersion>4.0.0</modelVersion></project>')
PY
MOCK
chmod +x "$tmp/bin/curl"

MOCK_CURL_LOG="$tmp/curl.log" HOME="$tmp/home" PATH="$tmp/bin:$PATH" "$prefetch"
repo="$tmp/home/.m2/repository/org/robolectric/android-all-instrumented"
for version in 14-robolectric-10818077-i7 15-robolectric-12650502-i7; do
  base="$repo/$version/android-all-instrumented-$version"
  [[ -s "$base.jar" && -s "$base.pom" ]]
done

grep -q 'repo1.maven.org' "$tmp/curl.log"
grep -q 'repo.maven.apache.org' "$tmp/curl.log"
before=$(wc -l < "$tmp/curl.log")
MOCK_CURL_LOG="$tmp/curl.log" HOME="$tmp/home" PATH="$tmp/bin:$PATH" "$prefetch" --check
after=$(wc -l < "$tmp/curl.log")
[[ "$before" == "$after" ]]

# A corrupt cached JAR must not be treated as a successful cache hit.
printf 'not a zip' > "$repo/14-robolectric-10818077-i7/android-all-instrumented-14-robolectric-10818077-i7.jar"
if HOME="$tmp/home" PATH="$tmp/bin:$PATH" "$prefetch" --check >/dev/null 2>&1; then
  echo "prefetch --check accepted a corrupt runtime JAR" >&2
  exit 1
fi

echo "Robolectric prefetch regression checks passed."
