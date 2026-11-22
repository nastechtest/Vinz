#!/usr/bin/env bash
# Installs the Android SDK packages this project needs.
#
# Why this isn't one sdkmanager call: AGP resolves compileSdk 37 to $ANDROID_HOME/platforms/android-37,
# but the SDK manager publishes that platform as "android-37.0" (a preview-style directory name). Without
# the symlink, every Gradle task fails with "Failed to find target with hash string 'android-37'". This
# mirrors what the repository's previous CI did and keeps the knowledge in one place.
#
# Requires: ANDROID_HOME set (the GitHub Ubuntu runner image already has the command line tools).
set -euo pipefail

die() { printf '::error::%s\n' "$*" >&2; exit 1; }

[[ -n ${ANDROID_HOME:-} ]] || die "ANDROID_HOME is not set"

sdkmanager=$(find "$ANDROID_HOME/cmdline-tools" -type f -name sdkmanager 2>/dev/null | sort -V | tail -1)
[[ -n $sdkmanager ]] || die "sdkmanager not found under $ANDROID_HOME/cmdline-tools"

# Licences are already accepted on hosted runners, but a self-hosted machine would otherwise hang here.
yes | "$sdkmanager" --sdk_root="$ANDROID_HOME" --licenses >/dev/null 2>&1 || true

"$sdkmanager" --sdk_root="$ANDROID_HOME" 'platforms;android-37.0' 'platform-tools'
[[ -d $ANDROID_HOME/platforms/android-37.0 ]] || die "platforms;android-37.0 did not install"

if [[ ! -e $ANDROID_HOME/platforms/android-37 ]]; then
  ln -s android-37.0 "$ANDROID_HOME/platforms/android-37"
fi
[[ -d $ANDROID_HOME/platforms/android-37 ]] || die "could not expose platforms/android-37"

printf 'Android SDK ready:%s\n' "$(find "$ANDROID_HOME/platforms" -maxdepth 1 -mindepth 1 -type d -printf ' %f')"
