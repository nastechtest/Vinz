#!/usr/bin/env bash
# Installs the host libraries required by the Android emulator on GitHub's Ubuntu runners.
#
# The emulator's bundled qemu-system-x86_64 dynamically links libpulse.so.0 even when it
# is launched with -no-audio. Ubuntu 24.04 runner images do not guarantee that library,
# so install the smallest runtime package explicitly and verify the dynamic linker can see it.
set -euo pipefail

die() { printf '::error::%s\n' "$*" >&2; exit 1; }

command -v sudo >/dev/null || die "sudo is required to install Android emulator runtime libraries"
command -v apt-get >/dev/null || die "apt-get is required to install Android emulator runtime libraries"
command -v ldconfig >/dev/null || die "ldconfig is required to verify Android emulator runtime libraries"

sudo apt-get update
sudo apt-get install --no-install-recommends -y libpulse0
# Consume the full cache output: grep -q can SIGPIPE ldconfig under pipefail.
ldconfig -p | grep -F 'libpulse.so.0' >/dev/null || die "libpulse.so.0 is unavailable after installing libpulse0"

printf '%s\n' 'Android emulator runtime libraries ready: libpulse.so.0'
