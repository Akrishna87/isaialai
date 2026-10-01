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
dump() {
  local i
  for i in 1 2 3; do
    adb shell uiautomator dump /sdcard/ui.xml > /dev/null && adb pull /sdcard/ui.xml "$OUT/$1.xml" > /dev/null
    # The emulator's own apps (e.g. the launcher) sometimes freeze while it warms up; wave the
    # "isn't responding" popup away so it doesn't cover My Music. Crashes of My Music itself are
    # still caught from logcat at the end.
    grep -q "isn&apos;t responding\|isn't responding" "$OUT/$1.xml" || return 0
    echo "(dismissing a system 'isn't responding' popup)"
    python3 "$HERE/find_text.py" "$OUT/$1.xml" "Wait" > /dev/null 2>&1 && adb shell input tap $(python3 "$HERE/find_text.py" "$OUT/$1.xml" "Wait")
    sleep 3
  done
}
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

echo "--- Launching the app (opens on Home)"
# Don't let other apps' "isn't responding" popups cover the screen during the test.
adb shell settings put global hide_error_dialogs 1 || true
adb shell pm grant "$PKG" android.permission.READ_MEDIA_AUDIO
adb logcat -c
adb shell am start -W -n "$PKG/.MainActivity"
sleep 6
dump home
shot 1-home
grep -q "Shuffle all" "$OUT/home.xml" || fail "Home doesn't show its quick tiles"
grep -qi 'text="Good ' "$OUT/home.xml" || fail "Home doesn't show the greeting"
echo "PASS: Home screen"

echo "--- Library: all songs"
tap home "Library"
sleep 2
dump library
shot 2-library
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
shot 3-mini-player

echo "--- Opening the full player"
tap playing "Smoke Song 1" last # the mini player
sleep 3
shot 4-now-playing
dump nowplaying
grep -qi "playing from" "$OUT/nowplaying.xml" || fail "the full player didn't open"
echo "PASS: full player opens"
tap nowplaying "Like" last # the heart on the full player (the mini player's is underneath)
sleep 1

echo "--- Equaliser"
tap nowplaying "Equalizer" last # the button on the full player (Home's is underneath)
sleep 2
dump eq
shot 4b-equalizer
grep -q 'text="Equalizer"' "$OUT/eq.xml" || fail "the equaliser screen didn't open"
if grep -q "Hz" "$OUT/eq.xml"; then
  tap eq "Equalizer on/off"
  sleep 1
  tap eq "Equalizer bands" # tap the middle of the curve: sets a band
  sleep 1
  shot 4c-equalizer-on
  echo "PASS: equaliser opens and can be adjusted"
elif grep -q "doesn" "$OUT/eq.xml"; then
  echo "PASS: equaliser opens (this emulator has no equaliser effect, and the screen says so)"
else
  fail "the equaliser screen shows neither bands nor an explanation"
fi
adb shell input keyevent KEYCODE_BACK # close the equaliser, back to the player
sleep 1

echo "--- Sleep timer"
dump before-sleep
tap before-sleep "Sleep timer"
sleep 1
dump sleep-dialog
tap sleep-dialog "15 minutes"
sleep 2
dump sleep-on
grep -q 'content-desc="Sleep timer: 1[45]:' "$OUT/sleep-on.xml" || fail "the sleep timer doesn't show its countdown"
shot 4d-sleep-timer
tap sleep-on "Sleep timer" # matches "Sleep timer: 14:58 left"
sleep 1
dump sleep-dialog2
tap sleep-dialog2 "Turn off timer"
sleep 2
dump sleep-off
grep -q 'content-desc="Sleep timer"' "$OUT/sleep-off.xml" || fail "the sleep timer didn't turn off"
echo "PASS: sleep timer can be set and turned off"

echo "--- Media 'next' button (headphones / lock screen)"
adb shell input keyevent KEYCODE_MEDIA_NEXT
sleep 3
session
grep -q "Smoke Song 2" "$OUT/session.txt" || fail "the media next button didn't skip to the next song"
echo "PASS: media next button works"

echo "--- Swipe gestures on the full player"
SIZE=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1); W=${SIZE%x*}; H=${SIZE#*x}
adb shell input swipe $((W * 8 / 10)) $((H * 38 / 100)) $((W * 2 / 10)) $((H * 38 / 100)) 250 # swipe the cover left
sleep 3
session
grep -q "Smoke Song 3" "$OUT/session.txt" || fail "swiping the cover left didn't skip to the next song"
echo "PASS: swipe the cover to change song"
adb shell input swipe $((W / 2)) $((H * 25 / 100)) $((W / 2)) $((H * 85 / 100)) 300 # swipe down
sleep 2
dump after-swipe-down
if grep -qi "playing from" "$OUT/after-swipe-down.xml"; then fail "swiping down didn't close the player"; fi
echo "PASS: swipe down closes the player"

echo "--- Background playback"
adb shell input keyevent KEYCODE_HOME
sleep 8
playing || fail "playback stopped when the app went to the background"
echo "PASS: keeps playing in the background"
adb shell cmd statusbar expand-notifications
sleep 2
shot 5-notification
adb shell cmd statusbar collapse

echo "--- Albums"
adb shell am start -W -n "$PKG/.MainActivity"
sleep 2
dump reopened
if grep -qi "playing from" "$OUT/reopened.xml"; then adb shell input keyevent KEYCODE_BACK; sleep 1; fi # close the full player if open
dump lib-again
tap lib-again "Albums"
sleep 2
shot 6-albums
dump albums
grep -q "CI Album" "$OUT/albums.xml" || fail "Albums doesn't show the test album"
tap albums "CI Album"
sleep 3
shot 7-album-page
dump album-page
grep -q "Smoke Song 3" "$OUT/album-page.xml" || fail "the album page doesn't list its songs"
echo "PASS: albums and album page"
adb shell input keyevent KEYCODE_BACK
sleep 1

echo "--- Browsing folders"
dump before-folders
tap before-folders "Folders"
sleep 2
dump folders
grep -q 'text="Music"' "$OUT/folders.xml" || fail "Folders doesn't show the Music folder"
grep -q 'text="Podcasts"' "$OUT/folders.xml" || fail "Folders doesn't show the Podcasts folder"
tap folders "Music"
sleep 2
dump folder-music
grep -q 'text="SmokeTest"' "$OUT/folder-music.xml" || fail "the Music folder doesn't list its SmokeTest subfolder"
tap folder-music "SmokeTest"
sleep 2
shot 8-folder
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
shot 9-added-folder
grep -q "Music/Hidden" "$OUT/after-add.xml" || fail "the added folder isn't listed under 'Missing songs?'"
# Back to the top of the folder list, where the Music folder is.
adb shell input swipe 540 700 540 2000 300
sleep 1
adb shell input swipe 540 700 540 2000 300
sleep 1
dump after-add-top
grep -q 'text="Music"' "$OUT/after-add-top.xml" || fail "the Music folder isn't at the top of the folder list"
tap after-add-top "Music"
sleep 2
dump music-after-add
grep -q 'text="Hidden"' "$OUT/music-after-add.xml" || fail "the added .nomedia folder doesn't show up under Music"
tap music-after-add "Hidden"
sleep 2
dump hidden-folder
shot 10-hidden-folder
grep -q "Smoke Hidden Song" "$OUT/hidden-folder.xml" || fail "the song in the added .nomedia folder isn't listed"
echo "PASS: a folder Android hides can be added"
tap hidden-folder "Smoke Hidden Song"
sleep 5
playing || fail "the song from the added folder didn't play"
grep -q "Smoke Hidden Song" "$OUT/session.txt" || fail "the player isn't showing the added folder's song"
echo "PASS: songs from an added folder play"

echo "--- Search"
dump before-search
tap before-search "Search"
sleep 2
dump search
shot 11-search-browse
tap search "Songs, artists, albums or folders"
sleep 1
adb shell input text Hidden
sleep 3
dump search-results
shot 12-search-results
grep -q "Smoke Hidden Song" "$OUT/search-results.xml" || fail "searching for 'Hidden' didn't find the song"
echo "PASS: search finds songs"

echo "--- Home after listening"
# Close the search keyboard first: it covers the tab bar.
if adb shell dumpsys input_method | grep -q "mInputShown=true"; then adb shell input keyevent KEYCODE_BACK; sleep 1; fi
dump before-home
tap before-home "Home"
sleep 3
dump home-after
shot 13-home-after
grep -q "Jump back in" "$OUT/home-after.xml" || fail "Home doesn't show 'Jump back in' after playing songs"
echo "PASS: Home shows recently played music"
tap home-after "Liked songs"
sleep 2
dump liked
shot 14-liked
grep -q "Smoke Song 1" "$OUT/liked.xml" || fail "the liked song isn't in Liked songs"
echo "PASS: liking a song adds it to Liked songs"

echo "--- Home-screen widget"
adb shell dumpsys appwidget | grep -q "mymusic.PlayerWidget" || fail "the home-screen widget isn't registered with the launcher"
echo "PASS: home-screen widget is available"

if adb logcat -d | grep -q "FATAL EXCEPTION"; then
  adb logcat -d > "$OUT/logcat.txt"
  fail "the app crashed (see logcat.txt)"
fi
echo "ALL SMOKE TESTS PASSED"
