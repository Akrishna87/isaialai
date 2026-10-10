#!/usr/bin/env bash
# Runs inside the Android emulator job, after the smoke test: checks Settings > App updates.
# It taps "Check for updates" and expects a clear answer (up to date, an update is ready, or a
# "couldn't check" message such as no connection), and that the app doesn't crash. It can't
# expect "an update is ready" here, because that needs a newer published build than this one.
# Usage: updater-test.sh <apk> <output dir>
set -euo pipefail

APK="$1"
OUT="$2"
PKG=io.github.akrishna87.mymusic
HERE="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$OUT"

fail() {
  echo "UPDATER TEST FAILED: $*"
  adb exec-out screencap -p > "$OUT/failure.png" || true
  adb logcat -d > "$OUT/logcat.txt" || true
  echo "App log (errors and warnings):"
  grep -E "AndroidRuntime|FATAL|AppUpdater|mymusic" "$OUT/logcat.txt" | grep -E " [EWFI] " | tail -40 || true
  exit 1
}
dump() { adb shell uiautomator dump /sdcard/ui.xml > /dev/null && adb pull /sdcard/ui.xml "$OUT/$1.xml" > /dev/null; }
tap() { # tap <dump name> <text>
  local xy
  xy=$(python3 "$HERE/find_text.py" "$OUT/$1.xml" "$2") || fail "couldn't find '$2' on screen"
  adb shell input tap $xy
}

adb wait-for-device
adb install -r "$APK"

echo "== The APK asks for the permissions the updater needs"
requested=$(adb shell dumpsys package "$PKG")
grep -q "android.permission.INTERNET" <<<"$requested" || fail "the APK doesn't ask for INTERNET"
grep -q "android.permission.REQUEST_INSTALL_PACKAGES" <<<"$requested" || fail "the APK doesn't ask for REQUEST_INSTALL_PACKAGES"

echo "== Open Settings"
adb shell pm clear "$PKG" > /dev/null
adb shell settings put global hide_error_dialogs 1 || true
adb shell pm grant "$PKG" android.permission.READ_MEDIA_AUDIO
adb logcat -c
adb shell am start -W -n "$PKG/.MainActivity"
sleep 6
dump home
tap home "Settings"
sleep 2

size=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1)
W=${size%x*}
H=${size#*x}
found=no
for _ in 1 2 3 4 5 6 7 8 9 10; do
  dump settings
  if xy=$(python3 "$HERE/find_text.py" "$OUT/settings.xml" "Check for updates" exact 2> /dev/null); then
    read -r _ y <<<"$xy"
    # Far enough up the screen that nothing (a mini player, the gesture bar) covers it.
    if [ "$y" -lt $((H * 80 / 100)) ]; then found=yes; break; fi
  fi
  adb shell input swipe $((W / 2)) $((H * 3 / 4)) $((W / 2)) $((H / 4)) 300
  sleep 1
done
[ "$found" = yes ] || fail "Settings has no 'Check for updates' button"
grep -q "Isaialai build [0-9]" "$OUT/settings.xml" || fail "Settings doesn't say which build is installed"
adb exec-out screencap -p > "$OUT/updater-before.png"
echo "PASS: Settings shows the installed build and a Check for updates button"

echo "== Tap Check for updates"
tap settings "Check for updates"
answer=""
for _ in $(seq 1 40); do
  sleep 1
  dump result
  if grep -q "You have the latest build" "$OUT/result.xml"; then answer="up to date"; break; fi
  if grep -q "is ready\." "$OUT/result.xml"; then answer="an update is ready"; break; fi
  if grep -q "t check for updates\." "$OUT/result.xml"; then answer="couldn't check"; break; fi
done
adb exec-out screencap -p > "$OUT/updater-result.png"
adb logcat -d > "$OUT/logcat.txt" || true
echo "What the app says: ${answer:-nothing}"
grep -E "AppUpdater" "$OUT/logcat.txt" | tail -5 || true
[ -n "$answer" ] || fail "tapping Check for updates never produced an answer"
grep -q "FATAL EXCEPTION" "$OUT/logcat.txt" && fail "the app crashed while checking for updates"
grep -o 'text="[^"]*\(latest build\|is ready\|check for updates\.\)[^"]*"' "$OUT/result.xml" | head -3 || true
echo "PASS: Check for updates answers ($answer) and the app keeps running"
