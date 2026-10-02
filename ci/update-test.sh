#!/usr/bin/env bash
# Runs inside the Android emulator job, before the smoke test: checks that a phone with an
# older build (signed with the old public key) takes the new build as an in-place update,
# and, once Isaialai has its own private key, that an APK signed with only the old public
# key can no longer replace it (what someone holding that public key would try).
# Usage: update-test.sh <apk signed with the old key only> <new apk>
set -euo pipefail

OLD="$1"
NEW="$2"
PKG=io.github.akrishna87.mymusic
HERE="$(cd "$(dirname "$0")" && pwd)"

fail() { echo "UPDATE TEST FAILED: $*"; exit 1; }
first_installed() { adb shell dumpsys package "$PKG" | grep -m1 firstInstallTime | tr -d ' \r'; }

adb wait-for-device
adb uninstall "$PKG" > /dev/null 2>&1 || true

echo "== Install a build signed with the old key (like the phones that have it now)"
adb install "$OLD" || fail "couldn't install the old-key build"
before="$(first_installed)"

echo "== Update to the new build"
adb install -r "$NEW" || fail "the new build doesn't install as an update over the old one; phones would need to uninstall first"
after="$(first_installed)"
[ -n "$before" ] && [ "$before" = "$after" ] || fail "not an in-place update ($before vs $after)"
echo "Updated in place ($after)"

if [ -e "$HERE/../signing/release.keystore.gpg" ]; then
  echo "== An APK signed with only the old public key must not replace it now"
  if out="$(adb install -r "$OLD" 2>&1)"; then
    fail "an old-key APK replaced the new build: $out"
  fi
  echo "$out"
  grep -q "INSTALL_FAILED_UPDATE_INCOMPATIBLE" <<<"$out" || fail "refused, but not for the signing key: $out"
  echo "Refused, as it should be"
else
  echo "== No private key yet; skipping the old-key refusal check"
fi

adb uninstall "$PKG" > /dev/null
echo "UPDATE TEST PASSED"
