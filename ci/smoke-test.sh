#!/usr/bin/env bash
# Runs inside the Android emulator job: installs the APK, puts three test songs on the
# "phone", and checks that the app finds them, plays them, and keeps playing in the background.
# Also checks that songs Android marks as "not music" (a Podcasts folder) show up, that folders
# can be browsed, and that a folder Android hides (.nomedia) can be added and played from.
# Usage: smoke-test.sh <apk> <songs dir> <output dir>
set -euo pipefail

APK="$1"
SONGS="$2"
OUT="$3"
PKG=io.github.akrishna87.mymusic
HERE="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$OUT"

fail() {
  echo "SMOKE TEST FAILED: $*"
  adb exec-out screencap -p > "$OUT/failure.png" || true
  if adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1 && adb pull /sdcard/ui.xml "$OUT/failure.xml" > /dev/null 2>&1; then
    echo "On screen:"; grep -o 'text="[^"]\+"' "$OUT/failure.xml" | head -40 || true
  fi
  adb logcat -d > "$OUT/logcat.txt" || true
  exit 1
}
dump() { adb shell uiautomator dump /sdcard/ui.xml > /dev/null && adb pull /sdcard/ui.xml "$OUT/$1.xml" > /dev/null; }
shot() { adb exec-out screencap -p > "$OUT/$1.png"; }
tap() { # tap <dump name> <text> [first|last]
  local xy
  xy=$(python3 "$HERE/find_text.py" "$OUT/$1.xml" "$2" "${3:-first}") || fail "couldn't find '$2' on screen"
  adb shell input tap $xy
}
session() { adb shell dumpsys media_session > "$OUT/session.txt"; }
scroll_down() { # swipe up on the middle of the screen
  local size w h
  size=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1)
  w=${size%x*}; h=${size#*x}
  adb shell input swipe $((w / 2)) $((h * 3 / 4)) $((w / 2)) $((h / 4)) 300
  sleep 1
}
playing() { session; grep -Eq "\{state=(PLAYING|3)" "$OUT/session.txt"; }

adb wait-for-device
adb install -r "$APK"

echo "--- Copying test songs onto the emulator"
adb shell mkdir -p /sdcard/Music/SmokeTest
for f in "$SONGS"/song*.mp3; do adb push "$f" /sdcard/Music/SmokeTest/ > /dev/null; done
adb shell mkdir -p /sdcard/Podcasts
adb push "$SONGS/podcast.mp3" /sdcard/Podcasts/ > /dev/null
# A folder Android's media library ignores, like Telegram's.
adb shell mkdir -p /sdcard/Music/Hidden
adb shell touch /sdcard/Music/Hidden/.nomedia
adb push "$SONGS/hidden.mp3" /sdcard/Music/Hidden/ > /dev/null
adb shell content call --uri content://media --method scan_volume --arg external_primary > /dev/null 2>&1 || true
for _ in $(seq 1 30); do
  adb shell content query --uri content://media/external/audio/media --projection title | grep -q "Smoke Song 3" && break
  sleep 2
done
adb shell content query --uri content://media/external/audio/media --projection title:is_music:is_podcast:relative_path || true
HIDDEN_IN_LIBRARY=no
adb shell content query --uri content://media/external/audio/media --projection title | grep -q "Smoke Hidden Song" && HIDDEN_IN_LIBRARY=yes
echo "Android's media library has the .nomedia song: $HIDDEN_IN_LIBRARY"

echo "--- Launching the app"
adb shell pm grant "$PKG" android.permission.READ_MEDIA_AUDIO
adb logcat -c
adb shell am start -W -n "$PKG/.MainActivity"
sleep 6
dump library
shot 1-library
grep -q "Smoke Song 1" "$OUT/library.xml" || fail "the song list doesn't show the test songs"
echo "PASS: library lists the songs on the phone"
grep -q "Smoke Podcast Song" "$OUT/library.xml" || fail "a song Android marks as 'not music' (Podcasts folder) is missing"
echo "PASS: songs Android marks as 'not music' are listed"
if [ "$HIDDEN_IN_LIBRARY" = no ] && grep -q "Smoke Hidden Song" "$OUT/library.xml"; then
  fail "the .nomedia song is listed before its folder was added"
fi

echo "--- Playing a song"
tap library "Smoke Song 1"
sleep 5
playing || fail "tapping a song didn't start playback"
grep -q "Smoke Song 1" "$OUT/session.txt" || fail "the media session isn't showing the song title"
echo "PASS: tapping a song plays it"
dump playing
shot 2-mini-player

echo "--- Opening the full player"
tap playing "Smoke Song 1" last
sleep 2
shot 3-now-playing
dump nowplaying
grep -q "NOW PLAYING" "$OUT/nowplaying.xml" || fail "the full player didn't open"
echo "PASS: full player opens"

echo "--- Media 'next' button (headphones / lock screen)"
adb shell input keyevent KEYCODE_MEDIA_NEXT
sleep 3
session
grep -q "Smoke Song 2" "$OUT/session.txt" || fail "the media next button didn't skip to the next song"
echo "PASS: media next button works"

echo "--- Background playback"
adb shell input keyevent KEYCODE_HOME
sleep 8
playing || fail "playback stopped when the app went to the background"
echo "PASS: keeps playing in the background"
adb shell cmd statusbar expand-notifications
sleep 2
shot 4-notification
adb shell cmd statusbar collapse

echo "--- Albums tab"
adb shell am start -W -n "$PKG/.MainActivity"
sleep 2
adb shell input keyevent KEYCODE_BACK # close the full player if it's still open
sleep 1
dump home
tap home "Albums"
sleep 2
shot 5-albums
dump albums
grep -q "CI Album" "$OUT/albums.xml" || fail "the Albums tab doesn't show the test album"
echo "PASS: albums tab"

echo "--- Browsing folders"
tap albums "Folders"
sleep 2
dump folders
grep -q 'text="Music"' "$OUT/folders.xml" || fail "the Folders tab doesn't show the Music folder"
grep -q 'text="Podcasts"' "$OUT/folders.xml" || fail "the Folders tab doesn't show the Podcasts folder"
tap folders "Music"
sleep 2
dump folder-music
grep -q 'text="SmokeTest"' "$OUT/folder-music.xml" || fail "the Music folder doesn't list its SmokeTest subfolder"
tap folder-music "SmokeTest"
sleep 2
shot 6-folder
dump folder-smoke
grep -q "Smoke Song 3" "$OUT/folder-smoke.xml" || fail "the SmokeTest folder doesn't list its songs"
echo "PASS: folders can be browsed like a file manager"
adb shell input keyevent KEYCODE_BACK
sleep 1
adb shell input keyevent KEYCODE_BACK
sleep 1

echo "--- Adding a folder Android hides (.nomedia)"
dump folders-root
if ! grep -qi 'text="Add a folder"' "$OUT/folders-root.xml"; then scroll_down; dump folders-root; fi
tap folders-root "Add a folder"
sleep 4
dump picker
python3 "$HERE/find_text.py" "$OUT/picker.xml" "Hidden" > /dev/null 2>&1 || { tap picker "Music"; sleep 2; dump picker; }
tap picker "Hidden"
sleep 2
dump picker-hidden
tap picker-hidden "Use this folder"
sleep 2
dump picker-allow
tap picker-allow "Allow"
sleep 8
adb shell am start -W -n "$PKG/.MainActivity" > /dev/null
sleep 2
dump after-add
if ! grep -q "Music/Hidden" "$OUT/after-add.xml"; then scroll_down; dump after-add; fi
shot 7-added-folder
grep -q "Music/Hidden" "$OUT/after-add.xml" || fail "the added folder isn't listed under 'Missing songs?'"
tap after-add "Songs"
sleep 2
dump songs-after-add
grep -q "Smoke Hidden Song" "$OUT/songs-after-add.xml" || fail "the song in the added .nomedia folder isn't listed"
echo "PASS: a folder Android hides can be added"
tap songs-after-add "Smoke Hidden Song"
sleep 5
playing || fail "the song from the added folder didn't play"
grep -q "Smoke Hidden Song" "$OUT/session.txt" || fail "the player isn't showing the added folder's song"
echo "PASS: songs from an added folder play"

if adb logcat -d | grep -q "FATAL EXCEPTION"; then
  adb logcat -d > "$OUT/logcat.txt"
  fail "the app crashed (see logcat.txt)"
fi
echo "ALL SMOKE TESTS PASSED"
