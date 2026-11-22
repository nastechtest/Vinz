#!/usr/bin/env bash
# Checks an APK before it is published: package id, version identity, and that it is signed with a real
# release key (never the Android debug key).
#
#   verify-apk.sh <apk> [expected.package.id]
#
# Env: ALLOW_DEBUG_SIGNATURE=true  permits a debug-signed APK (debug/dev builds only, never a release).
#      EXPECTED_VERSION_NAME / EXPECTED_VERSION_CODE  additionally assert the stamping worked.
set -euo pipefail

die() { printf '::error::%s\n' "$*" >&2; exit 1; }
note() { printf '%s\n' "$*"; }

apk=${1:-}
expected_package=${2:-com.vinz.music}
[[ -n $apk ]] || die "usage: verify-apk.sh <apk> [expected.package.id]"
[[ -s $apk ]] || die "APK missing or empty: $apk"
[[ -n ${ANDROID_HOME:-} ]] || die "ANDROID_HOME is not set"

tool() { find "$ANDROID_HOME/build-tools" -maxdepth 2 -type f -name "$1" 2>/dev/null | sort -V | tail -1; }

badging_tool=$(tool aapt2)
[[ -n $badging_tool ]] || badging_tool=$(tool aapt)
apksigner=$(tool apksigner)
[[ -n $badging_tool ]] || die "aapt2/aapt not found in \$ANDROID_HOME/build-tools"
[[ -n $apksigner ]] || die "apksigner not found in \$ANDROID_HOME/build-tools"

badging=$("$badging_tool" dump badging "$apk")

package_name=$(printf '%s\n' "$badging" | sed -nE "s/^package: name='([^']+)'.*/\1/p" | head -1)
version_code=$(printf '%s\n' "$badging" | sed -nE "s/^package: name='[^']+' versionCode='([^']+)'.*/\1/p" | head -1)
version_name=$(printf '%s\n' "$badging" | sed -nE "s/^package: name='[^']+' versionCode='[^']+' versionName='([^']+)'.*/\1/p" | head -1)

[[ $package_name == "$expected_package" ]] || die "package is '$package_name', expected '$expected_package'"
[[ -n $version_code && -n $version_name ]] || die "could not read versionCode/versionName from the APK"
note "package:     $package_name"
note "versionName: $version_name"
note "versionCode: $version_code"

if [[ -n ${EXPECTED_VERSION_NAME:-} && $version_name != "$EXPECTED_VERSION_NAME" ]]; then
  die "versionName is '$version_name', expected '$EXPECTED_VERSION_NAME' (-PciVersionName not applied?)"
fi
if [[ -n ${EXPECTED_VERSION_CODE:-} && $version_code != "$EXPECTED_VERSION_CODE" ]]; then
  die "versionCode is '$version_code', expected '$EXPECTED_VERSION_CODE'"
fi

certs=$("$apksigner" verify --print-certs "$apk") ||
  die "apksigner verify failed: the APK is unsigned or its signature is invalid"

if printf '%s\n' "$certs" | grep -q 'CN=Android Debug'; then
  [[ ${ALLOW_DEBUG_SIGNATURE:-false} == true ]] ||
    die "APK is signed with the Android debug key. Refusing to publish it as a release: the release keystore secrets are probably missing."
  note "signature:   debug key (allowed for this build type)" >&2
else
  note "signature:   release key verified"
fi

printf '%s\n' "$certs" | sed -nE 's/^Signer #([0-9]+) certificate DN: (.*)$/signer \1:   \2/p' || true
