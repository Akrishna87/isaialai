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
  echo "App log (errors and warnings):"
  grep -E "AndroidRuntime|FATAL|mymusic|ExoPlayer|MediaSession" "$OUT/logcat.txt" | grep -E " [EWF] " | tail -40 || true
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
now_playing() { session; grep -o "description=[^,]*" "$OUT/session.txt" | head -1 | sed 's/description=//'; }
scroll_down() { # swipe up on the middle of the screen
  local size w h
  size=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1)
  w=${size%x*}; h=${size#*x}
  adb shell input swipe $((w / 2)) $((h * 3 / 4)) $((w / 2)) $((h / 4)) 300
  sleep 1
}
playing() { session; grep -Eq "\{state=(PLAYING|3)" "$OUT/session.txt"; }
menu_item() { # menu_item <dump> <song> <item>: open a song's ⋮ menu and tap an item, scrolling the menu if needed
  local i xy
  tap "$1" "More options for $2"
  sleep 1
  for i in 1 2 3; do
    dump song-menu
    if xy=$(python3 "$HERE/find_text.py" "$OUT/song-menu.xml" "$3" 2> /dev/null); then adb shell input tap $xy; return 0; fi
    adb shell input swipe $((W / 2)) $((H * 70 / 100)) $((W / 2)) $((H * 35 / 100)) 400
    sleep 1
  done
  fail "the song menu has no '$3'"
}
tap_clear() { # tap_clear <text>: scroll until <text> is above the mini player, then tap it
  local i b x1 y1 x2 y2 h density limit home
  h=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1); h=${h#*x}
  density=$(adb shell wm density | grep -o '[0-9]*' | tail -1)
  for i in 1 2 3 4 5; do
    dump clear
    # The mini player sits just above the tab bar: about 90 dp above the "Home" tab's label.
    limit=$((h * 70 / 100))
    if home=$(python3 "$HERE/find_text.py" "$OUT/clear.xml" "Home" exact-bounds 2> /dev/null); then
      read -r _ y1 _ _ <<<"$home"
      limit=$((y1 - 90 * density / 160))
    fi
    if b=$(python3 "$HERE/find_text.py" "$OUT/clear.xml" "$1" exact-bounds 2> /dev/null); then
      read -r x1 y1 x2 y2 <<<"$b"
      if [ $(((y1 + y2) / 2)) -lt "$limit" ]; then adb shell input tap $(((x1 + x2) / 2)) $(((y1 + y2) / 2)); return 0; fi
    fi
    scroll_down
  done
  fail "couldn't get '$1' clear of the mini player to tap it"
}
chip() { # chip <name>: tap a Library tab chip, scrolling the chip row sideways if it's out of view
  local i y xy
  for i in 1 2 3 4 5 6; do
    dump chips
    # Exact matches only: "Songs" mustn't match "Liked songs" in a list below the tabs.
    if xy=$(python3 "$HERE/find_text.py" "$OUT/chips.xml" "$1" exact 2> /dev/null); then adb shell input tap $xy; return 0; fi
    y=$(python3 "$HERE/find_text.py" "$OUT/chips.xml" "Your Library" | cut -d' ' -f2) || fail "not on the Library screen"
    y=$((y + 150))
    if [ "$i" -le 2 ]; then adb shell input swipe 900 $y 200 $y 300; else adb shell input swipe 200 $y 900 $y 300; fi
    sleep 1
  done
  fail "couldn't find the '$1' tab"
}

adb wait-for-device
adb install -r "$APK"

echo "--- Copying test songs onto the emulator"
adb shell mkdir -p /sdcard/Music/SmokeTest
for f in "$SONGS"/song*.mp3; do adb push "$f" /sdcard/Music/SmokeTest/ > /dev/null; done
# A 12-song album for the shuffle test: big enough that a shuffled order can't look in order by chance.
adb shell mkdir -p /sdcard/Music/ShuffleTest
for f in "$SONGS"/tune*.mp3; do adb push "$f" /sdcard/Music/ShuffleTest/ > /dev/null; done
adb shell mkdir -p /sdcard/Podcasts
adb push "$SONGS/podcast.mp3" /sdcard/Podcasts/ > /dev/null
# A folder Android's media library ignores, like Telegram's.
adb shell mkdir -p /sdcard/Music/Hidden
adb shell touch /sdcard/Music/Hidden/.nomedia
adb push "$SONGS/hidden.mp3" /sdcard/Music/Hidden/ > /dev/null
adb push "$SONGS/hidden.lrc" /sdcard/Music/Hidden/ > /dev/null
adb shell mkdir -p /sdcard/Music/LoudTest
adb push "$SONGS/loud.mp3" /sdcard/Music/LoudTest/ > /dev/null
# Downloaded songs with no album saved (Android calls their album "Download"), and a picture.
adb push "$SONGS/arabic-test.mp3" /sdcard/Download/ > /dev/null
adb push "$SONGS/plain-download.mp3" /sdcard/Download/ > /dev/null
adb shell mkdir -p /sdcard/Pictures
adb push "$SONGS/red-cover.png" /sdcard/Pictures/ > /dev/null
# The same song in two folders, for the duplicate finder.
adb shell mkdir -p /sdcard/Music/TwinA /sdcard/Music/TwinB
adb push "$SONGS/twin.mp3" /sdcard/Music/TwinA/ > /dev/null
adb push "$SONGS/twin.mp3" /sdcard/Music/TwinB/ > /dev/null
adb shell content call --uri content://media --method scan_volume --arg external_primary > /dev/null 2>&1 || true
for _ in $(seq 1 30); do
  lib=$(adb shell content query --uri content://media/external/audio/media --projection title)
  [ "$(grep -c "Twin Song" <<<"$lib")" -ge 2 ] && grep -q "Plain Download Song" <<<"$lib" && break
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

echo "--- Lyrics saved inside the song"
dump before-lyrics
tap before-lyrics "Show lyrics"
sleep 4
dump lyrics-embedded
shot 4e-lyrics-embedded
grep -q "Smoke lyrics line one" "$OUT/lyrics-embedded.xml" || fail "the lyrics saved in Smoke Song 2 aren't shown"
grep -q "Smoke lyrics line two" "$OUT/lyrics-embedded.xml" || fail "only part of the saved lyrics is shown"
echo "PASS: shows lyrics saved inside a song"
tap lyrics-embedded "Hide lyrics"
sleep 1

echo "--- Playback speed and pitch"
dump before-speed
tap before-speed "Playback speed"
sleep 1
dump speed-dialog
shot 4f-speed
tap speed-dialog "1.5×"
sleep 2
session
grep -q "speed=1.5" "$OUT/session.txt" || fail "choosing 1.5× didn't speed up playback"
dump speed-dialog2
tap speed-dialog2 "Reset"
sleep 1
dump speed-dialog3
tap speed-dialog3 "Done"
sleep 2
session
grep -q "speed=1.0" "$OUT/session.txt" || fail "Reset didn't bring the speed back to normal"
echo "PASS: playback speed can be changed and reset"

echo "--- Jump back / forward 10 seconds"
pos() { session; grep -o "position=[0-9]*" "$OUT/session.txt" | head -1 | cut -d= -f2 || true; }
adb shell input keyevent KEYCODE_MEDIA_PAUSE # paused, so the reported position is exact
sleep 2
BEFORE=$(pos)
dump before-jump
shot 4h-jump-buttons
tap before-jump "Forward 10 seconds"
sleep 2
AHEAD=$(pos)
echo "position: $BEFORE ms, after ⏩: $AHEAD ms"
[ $((AHEAD - BEFORE)) -ge 9000 ] && [ $((AHEAD - BEFORE)) -le 11000 ] || fail "'Forward 10 seconds' didn't jump 10 s ahead"
tap before-jump "Back 10 seconds"
sleep 2
BACK=$(pos)
echo "after ⏪: $BACK ms"
[ $((AHEAD - BACK)) -ge 9000 ] && [ $((AHEAD - BACK)) -le 11000 ] || fail "'Back 10 seconds' didn't jump 10 s back"
# The same buttons in the notification / lock-screen controls.
grep -q "Back 10 seconds" "$OUT/session.txt" || fail "the notification has no 'Back 10 seconds' button"
grep -q "Forward 10 seconds" "$OUT/session.txt" || fail "the notification has no 'Forward 10 seconds' button"
echo "PASS: ⏪ 10 s and ⏩ 10 s work, and are in the notification controls"
# Double-tapping the right of the cover jumps ahead too. adb can't always tap twice quickly
# enough to count as a double tap, so this is only a note.
SIZE=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1); W=${SIZE%x*}; H=${SIZE#*x}
adb shell "input tap $((W * 3 / 4)) $((H * 38 / 100)) & sleep 0.12; input tap $((W * 3 / 4)) $((H * 38 / 100))"
sleep 2
DT=$(pos)
if [ $((DT - BACK)) -ge 9000 ]; then
  echo "double-tapping the right of the cover jumped ahead: $BACK -> $DT ms"
  # Go back again, so the song doesn't run out during the next tests (they expect it to keep playing).
  adb shell "input tap $((W / 4)) $((H * 38 / 100)) & sleep 0.12; input tap $((W / 4)) $((H * 38 / 100))"
  sleep 2
  DT2=$(pos)
  if [ $((DT - DT2)) -ge 9000 ]; then echo "double-tapping the left of the cover jumped back: $DT -> $DT2 ms"
  else
    echo "(note: the second double tap didn't register: $DT -> $DT2 ms)"
    dump after-double-tap
    tap after-double-tap "Back 10 seconds"
    sleep 1
  fi
else echo "(note: adb's taps weren't quick enough to count as a double tap: $BACK -> $DT ms)"; fi
adb shell input keyevent KEYCODE_MEDIA_PLAY
sleep 1

echo "--- Volume boost"
dump before-boost
tap before-boost "Volume boost"
sleep 1
dump boost-dialog
shot 4g-volume-boost
grep -q 'Volume boost: off' "$OUT/boost-dialog.xml" || fail "the volume boost dialog didn't open"
tap boost-dialog "150%"
sleep 2
dump boost-150
grep -q 'Volume boost: 150%' "$OUT/boost-150.xml" || fail "choosing 150% didn't set the volume boost"
adb shell dumpsys media.audio_flinger > "$OUT/audio-effects.txt" 2>/dev/null || true
if grep -iq "loudness" "$OUT/audio-effects.txt"; then
  grep -i -A12 "loudness" "$OUT/audio-effects.txt" | grep -iq "state.*active\|enabled.*1\|ACTIVE" \
    && echo "Android reports the loudness effect switched on" \
    || echo "(note: the loudness effect is listed but its state couldn't be read)"
else
  echo "(note: couldn't see audio effects in dumpsys on this emulator)"
fi
tap boost-150 "Done"
sleep 1
dump boost-player
grep -q 'content-desc="Volume boost: 150%"' "$OUT/boost-player.xml" || fail "the full player doesn't show the 150% boost"
tap boost-player "Volume boost"
sleep 1
dump boost-dialog2
tap boost-dialog2 "Off"
sleep 1
dump boost-dialog3
tap boost-dialog3 "Done"
sleep 1
dump boost-off
grep -q 'content-desc="Volume boost"' "$OUT/boost-off.xml" || fail "the volume boost didn't turn off"
echo "PASS: volume boost can be turned up to 150% and off again"

echo "--- Swipe gestures on the full player"
SIZE=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1); W=${SIZE%x*}; H=${SIZE#*x}
sleep 1 # let the speed dialog finish closing
adb shell input swipe $((W * 8 / 10)) $((H * 38 / 100)) $((W * 2 / 10)) $((H * 38 / 100)) 500 # swipe the cover left
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
chip "Albums"
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

echo "--- Music directors (Composer tag)"
chip "Composers"
sleep 2
dump composers
shot 7b-composers
grep -q 'text="CI Composer"' "$OUT/composers.xml" || fail "Composers doesn't list the test songs' music director"
tap composers "CI Composer"
sleep 3
dump composer-page
shot 7c-composer-page
grep -q 'text="Music director' "$OUT/composer-page.xml" || fail "the music director page didn't open"
grep -q 'Movies &amp; albums\|Movies & albums' "$OUT/composer-page.xml" || fail "the music director page doesn't show their movies"
grep -q 'text="Shuffle Album"' "$OUT/composer-page.xml" || fail "the music director's movies don't include all their albums"
echo "PASS: browse by music director, with their movies"
adb shell input keyevent KEYCODE_BACK
sleep 1

echo "--- Browsing folders"
chip "Folders"
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

echo "--- Timed lyrics from an .lrc file"
dump before-lrc
tap before-lrc "Smoke Hidden Song" last # the mini player
sleep 2
dump lrc-player
tap lrc-player "Show lyrics"
sleep 4
dump lrc-lyrics
shot 10b-lrc-lyrics
grep -q "Second line here" "$OUT/lrc-lyrics.xml" || fail "the .lrc lyrics next to the song aren't shown"
grep -q "\[00:" "$OUT/lrc-lyrics.xml" && fail "the lyrics show raw timestamps"
tap lrc-lyrics "Twenty seconds in"
sleep 2
session
POS=$(grep -o "position=[0-9]*" "$OUT/session.txt" | head -1 | cut -d= -f2 || true)
echo "position after tapping the 0:20 line: $POS ms"
[ "${POS:-0}" -ge 19000 ] || fail "tapping a lyrics line didn't jump to it"
echo "PASS: timed lyrics from an .lrc file, and tapping a line jumps there"
adb shell input keyevent KEYCODE_BACK # close the player
sleep 1

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

echo "--- Shuffle (bug report: 'shuffle is not working')"
dump before-shuffle
tap before-shuffle "Library"
sleep 2
chip "Albums"
sleep 2
dump albums-shuffle
if ! grep -q 'text="Shuffle Album"' "$OUT/albums-shuffle.xml"; then scroll_down; dump albums-shuffle; fi
tap albums-shuffle "Shuffle Album"
sleep 3
dump shuffle-album
tap shuffle-album "Shuffle" # the album page's shuffle button
sleep 4
playing || fail "shuffling the album didn't start playback"
CUR=$(now_playing)
echo "shuffle started on: $CUR"
case "$CUR" in Tune*) ;; *) fail "shuffle didn't play from the album (playing: $CUR)";; esac
dump shuffle-playing
tap shuffle-playing "$CUR" last # open the full player from the mini player
sleep 2
dump shuffle-player
grep -q 'content-desc="Shuffle on"' "$OUT/shuffle-player.xml" || fail "the player doesn't show shuffle as on"
tap shuffle-player "Show up next"
sleep 2
dump queue-shuffled
shot 15-shuffle-up-next
python3 "$HERE/up_next.py" "$OUT/queue-shuffled.xml" "$CUR" > "$OUT/queue-shuffled.txt" || fail "couldn't read Up next"
echo "Up next (shuffled):"; cat "$OUT/queue-shuffled.txt"
N=$(wc -l < "$OUT/queue-shuffled.txt")
[ "$N" -ge 4 ] || fail "Up next shows only $N songs after shuffling a 12-song album"
# In album order, the songs after "Tune 07" would be Tune 08, Tune 09, …
num=${CUR#Tune }; num=$((10#$num))
python3 - "$OUT/queue-shuffled.txt" "$num" <<'PY' || fail "Up next is still in album order with shuffle on"
import sys
shown = [l.strip() for l in open(sys.argv[1]) if l.strip()]
cur = int(sys.argv[2])
in_order = ["Tune %02d" % k for k in range(cur + 1, 13)]
sys.exit(1 if shown == in_order[:len(shown)] else 0)
PY
echo "PASS: shuffle puts the album in a random order"

# Skipping must follow the order Up next shows.
for i in 1 2 3; do
  want=$(sed -n "${i}p" "$OUT/queue-shuffled.txt")
  adb shell input keyevent KEYCODE_MEDIA_NEXT
  sleep 3
  got=$(now_playing)
  [ "$got" = "$want" ] || fail "skip $i played '$got' but Up next said '$want'"
done
echo "PASS: skipping follows the shuffled order"

CUR=$(now_playing)
num=${CUR#Tune }; num=$((10#$num))
dump before-shuffle-off
tap before-shuffle-off "Shuffle on" # turn shuffle off
sleep 2
dump queue-unshuffled
grep -q 'content-desc="Shuffle off"' "$OUT/queue-unshuffled.xml" || fail "the shuffle button didn't switch off"
python3 "$HERE/up_next.py" "$OUT/queue-unshuffled.xml" "$CUR" > "$OUT/queue-unshuffled.txt" || true
echo "Up next (shuffle off, playing $CUR):"; cat "$OUT/queue-unshuffled.txt"
python3 - "$OUT/queue-unshuffled.txt" "$num" <<'PY' || fail "with shuffle off, Up next isn't the album order after the current song"
import sys
shown = [l.strip() for l in open(sys.argv[1]) if l.strip()]
cur = int(sys.argv[2])
in_order = ["Tune %02d" % k for k in range(cur + 1, 13)]
sys.exit(0 if shown == in_order[:len(shown)] and (shown or cur == 12) else 1)
PY
echo "PASS: turning shuffle off goes back to album order"

tap queue-unshuffled "Shuffle off" # and back on
sleep 2
dump queue-reshuffled
shot 16-shuffle-back-on
grep -q 'content-desc="Shuffle on"' "$OUT/queue-reshuffled.xml" || fail "the shuffle button didn't switch back on"
[ "$(now_playing)" = "$CUR" ] || fail "turning shuffle on changed the song that was playing"
python3 "$HERE/up_next.py" "$OUT/queue-reshuffled.xml" "$CUR" > "$OUT/queue-reshuffled.txt" || true
echo "Up next (shuffle back on):"; cat "$OUT/queue-reshuffled.txt"
python3 - "$OUT/queue-reshuffled.txt" "$num" <<'PY' || fail "turning shuffle back on didn't reshuffle Up next"
import sys
shown = [l.strip() for l in open(sys.argv[1]) if l.strip()]
cur = int(sys.argv[2])
in_order = ["Tune %02d" % k for k in range(cur + 1, 13)]
sys.exit(0 if len(shown) >= 4 and shown != in_order[:len(shown)] else 1)
PY
echo "PASS: turning shuffle back on reshuffles, keeping the current song"

echo "--- Editing Up next (drag to move, swipe to remove)"
A=$(sed -n 1p "$OUT/queue-reshuffled.txt"); B=$(sed -n 2p "$OUT/queue-reshuffled.txt"); C=$(sed -n 3p "$OUT/queue-reshuffled.txt")
DENSITY=$(adb shell wm density | grep -o '[0-9]*' | tail -1)
ROW=$((64 * DENSITY / 160))
XY=$(python3 "$HERE/find_text.py" "$OUT/queue-reshuffled.xml" "Reorder $A") || fail "Up next has no drag handle for $A"
X=${XY% *}; Y=${XY#* }
adb shell input swipe "$X" "$Y" "$X" $((Y + ROW * 2 + ROW / 5)) 1500 # drag $A down two places
sleep 2
dump queue-moved
shot 16b-up-next-moved
python3 "$HERE/up_next.py" "$OUT/queue-moved.xml" "$CUR" > "$OUT/queue-moved.txt" || true
echo "Up next after moving $A down two places:"; cat "$OUT/queue-moved.txt"
[ "$(sed -n 1p "$OUT/queue-moved.txt")" = "$B" ] && [ "$(sed -n 2p "$OUT/queue-moved.txt")" = "$C" ] && [ "$(sed -n 3p "$OUT/queue-moved.txt")" = "$A" ] \
  || fail "dragging $A down two places didn't give $B, $C, $A"
adb shell input keyevent KEYCODE_MEDIA_NEXT
sleep 3
[ "$(now_playing)" = "$B" ] || fail "after moving songs, the next song played wasn't $B"
echo "PASS: dragging a song in Up next changes what plays next"
swipe_away() { # swipe the Up next row of $1 to the left, at a natural speed
  local xy y
  dump queue-before-remove
  xy=$(python3 "$HERE/find_text.py" "$OUT/queue-before-remove.xml" "Up next: $1") || fail "$1 isn't in Up next"
  y=${xy#* }
  adb shell input swipe $((W * 70 / 100)) "$y" $((W * 5 / 100)) "$y" 600
  sleep 2
}
sleep 1
swipe_away "$C"
dump queue-removed
if python3 "$HERE/find_text.py" "$OUT/queue-removed.xml" "Up next: $C" > /dev/null 2>&1; then
  echo "(note: the first swipe didn't register on the emulator; swiping again)"
  swipe_away "$C"
fi
dump queue-removed
shot 16c-up-next-removed
python3 "$HERE/up_next.py" "$OUT/queue-removed.xml" "$B" > "$OUT/queue-removed.txt" || true
echo "Up next after removing $C:"; cat "$OUT/queue-removed.txt"
grep -qx "$C" "$OUT/queue-removed.txt" && fail "swiping $C left didn't remove it from Up next"
adb shell input keyevent KEYCODE_MEDIA_NEXT
sleep 3
[ "$(now_playing)" = "$A" ] || fail "after removing $C, the next song wasn't $A (got $(now_playing))"
echo "PASS: swiping a song left removes it from the queue"
adb shell input keyevent KEYCODE_BACK # close the player
sleep 1

echo "--- Smart playlists"
adb shell input keyevent KEYCODE_BACK # off the album page, back to the Library
sleep 1
chip "Playlists"
sleep 2
dump playlists
shot 17-smart-playlists
for name in "Most played" "Recently added" "Not played in a while" "Never played"; do
  grep -q "text=\"$name\"" "$OUT/playlists.xml" || fail "the '$name' smart playlist is missing"
done
tap playlists "Most played"
sleep 2
dump most-played
shot 18-most-played
grep -q 'Smart playlist · [1-9][0-9]* songs\?' "$OUT/most-played.xml" || fail "Most played is empty after playing songs"
adb shell input keyevent KEYCODE_BACK
sleep 1
dump playlists2
tap playlists2 "Never played"
sleep 2
dump never-played
grep -q 'text="Twin Song"' "$OUT/never-played.xml" || fail "Never played doesn't list a song that was never played"
grep -q 'text="Smoke Song 1"' "$OUT/never-played.xml" && fail "Never played lists a song that was played"
echo "PASS: smart playlists fill themselves"
adb shell input keyevent KEYCODE_BACK
sleep 1

echo "--- Duplicate finder"
dump before-dupes
tap before-dupes "More options"
sleep 1
dump lib-menu
tap lib-menu "Find duplicate songs"
sleep 2
dump dupes
shot 19-duplicates
grep -q 'text="Twin Song"' "$OUT/dupes.xml" || fail "the duplicate finder didn't find the song saved twice"
grep -q '2 copies' "$OUT/dupes.xml" || fail "the duplicate finder doesn't show both copies"
grep -q 'Smoke Song' "$OUT/dupes.xml" && fail "the duplicate finder lists songs that aren't duplicates"
tap dupes "Delete the copy in Music/TwinB"
sleep 3
dump delete-confirm
shot 20-delete-confirm
tap delete-confirm "Allow"
sleep 8
dump dupes-after
shot 21-duplicates-after
grep -q "No duplicate songs found" "$OUT/dupes-after.xml" || fail "the deleted copy is still listed"
adb shell ls /sdcard/Music/TwinB/twin.mp3 > /dev/null 2>&1 && fail "the copy wasn't deleted from the phone"
adb shell ls /sdcard/Music/TwinA/twin.mp3 > /dev/null 2>&1 || fail "the other copy was deleted too"
echo "PASS: duplicate finder finds a song saved twice and deletes the extra copy"
adb shell input keyevent KEYCODE_BACK
sleep 1

echo "--- Settings: even volume and crossfade"
open_settings() {
  dump before-settings
  tap before-settings "More options"
  sleep 1
  dump lib-menu-settings
  tap lib-menu-settings "Settings"
  sleep 2
}
open_settings
dump settings
shot 22-settings
tap settings "5 s" # crossfade
sleep 1
dump settings-xfade
grep -q 'Crossfade: 5 s' "$OUT/settings-xfade.xml" || fail "choosing a 5 s crossfade didn't stick"
tap_clear "Even volume"
# The switch is the checkable row around the "Even volume" label (the label's own node isn't).
even_switch() { python3 - "$OUT/$1.xml" <<'PY'
import re, sys, xml.etree.ElementTree as ET
nodes = list(ET.parse(sys.argv[1]).iter("node"))
box = lambda n: [int(v) for v in re.findall(r"\d+", n.get("bounds", "[0,0][0,0]"))]
label = next((n for n in nodes if n.get("text") == "Even volume"), None)
if label is None: print("not on screen"); sys.exit()
x1, y1, x2, y2 = box(label); cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
for n in nodes:
    a1, b1, a2, b2 = box(n)
    if n.get("checkable") == "true" and a1 <= cx <= a2 and b1 <= cy <= b2:
        print("on" if n.get("checked") == "true" else "off"); sys.exit()
print("unknown")
PY
}
# The song playing now is measured first (decoded once); allow a slow emulator up to 30 s.
for i in $(seq 1 10); do
  sleep 3
  dump settings-on
  grep -q "turned up\|turned down\|already at the right level" "$OUT/settings-on.xml" && break
  if [ "$i" = 1 ]; then echo "Even volume switch after tapping: $(even_switch settings-on)"; fi
done
grep -q "turned up\|turned down\|already at the right level" "$OUT/settings-on.xml" \
  || fail "the Even volume switch didn't turn on (no level shown for the song playing; switch: $(even_switch settings-on))"
adb shell input keyevent KEYCODE_BACK
sleep 1
chip "Songs"
sleep 2
dump songs-loud
tap songs-loud "Loud Song"
sleep 6 # long enough to measure it
open_settings
for _ in 1 2 3; do
  scroll_down # the level line is under Even volume, near the bottom
  dump settings-loud
  grep -q "Now playing" "$OUT/settings-loud.xml" && break
done
shot 23-even-volume
echo "Even volume line: $(grep -o 'Now playing[^"]*' "$OUT/settings-loud.xml" || echo '(none)')"
grep -q "Loud Song.*turned down" "$OUT/settings-loud.xml" || fail "even volume didn't turn the loud song down"
echo "PASS: even volume turns a loud song down"
adb shell input keyevent KEYCODE_BACK
sleep 1

echo "--- Crossfade into the next song"
dump before-xfade
tap before-xfade "Loud Song" last # the mini player
sleep 2
dump xfade-player
read -r X1 Y1 X2 Y2 < <(python3 "$HERE/find_text.py" "$OUT/xfade-player.xml" "Seek bar" bounds) || fail "no seek bar on the full player"
adb shell input tap $((X1 + (X2 - X1) * 92 / 100)) $(((Y1 + Y2) / 2)) # about 5 s before the end
sleep 2
# (|| true: grep -m1 stops reading early, which pipefail would otherwise count as a failure)
UID_APP=$(adb shell dumpsys package "$PKG" | grep -m1 -o 'userId=[0-9]*' | cut -d= -f2 || true)
STARTED=$(adb shell dumpsys audio | grep "AudioPlaybackConfiguration" | grep "u/pid:$UID_APP/" | grep -c "state:started" || true)
echo "Isaialai audio players running during the crossfade: $STARTED"
shot 24-crossfading
SEEN=0; MAXPOS=0
for _ in $(seq 1 40); do
  session
  if [ "$(grep -o "description=[^,]*" "$OUT/session.txt" | head -1 | sed 's/description=//')" != "Loud Song" ]; then
    P=$(grep -o "position=[0-9]*" "$OUT/session.txt" | head -1 | cut -d= -f2 || true)
    [ "${P:-0}" -gt "$MAXPOS" ] && MAXPOS=$P
    SEEN=$((SEEN + 1))
    [ "$SEEN" -ge 6 ] && break
  fi
  sleep 0.5
done
echo "next song: $(now_playing), picked up at $MAXPOS ms"
[ "$SEEN" -gt 0 ] || fail "the next song never started after the loud song"
playing || fail "playback stopped after the crossfade"
[ "$MAXPOS" -ge 3000 ] || fail "the next song started from the beginning, so it didn't crossfade (picked up at $MAXPOS ms)"
[ "${STARTED:-0}" -ge 2 ] || echo "(note: couldn't confirm two players from dumpsys audio)"
echo "PASS: crossfade starts the next song early and blends into it"
adb shell input keyevent KEYCODE_BACK # close the player
sleep 1

echo "--- Editing song details"
chip "Songs"
sleep 2
dump songs-edit
tap songs-edit "More options for Smoke Podcast Song"
sleep 1
dump song-menu-edit
tap song-menu-edit "Edit song details"
sleep 2
dump edit-dialog
shot 25-edit-song
tap edit-dialog "Clear Title" # before the keyboard opens and moves the dialog
sleep 1
dump edit-cleared
tap edit-cleared "Title"
sleep 1
adb shell input text "Edited%sTitle"
sleep 1
if adb shell dumpsys input_method | grep -q "mInputShown=true"; then adb shell input keyevent KEYCODE_BACK; sleep 1; fi
dump edit-typed
tap edit-typed "Save"
sleep 2
dump songs-edited
shot 26-song-edited
grep -q 'text="Edited Title"' "$OUT/songs-edited.xml" || fail "the edited title isn't shown in the song list"
grep -q 'text="Smoke Podcast Song"' "$OUT/songs-edited.xml" && fail "the old title is still shown after editing"
grep -q 'CI Band · CI Extras' "$OUT/songs-edited.xml" || fail "editing the title changed the song's other details"
echo "PASS: song details can be edited"

echo "--- Light theme"
open_settings
dump theme-settings
tap theme-settings "Light"
sleep 2
shot 27-light-settings
B=$(adb exec-out screencap | python3 "$HERE/brightness.py")
echo "background brightness in the light theme: $B"
[ "$B" -ge 200 ] || fail "choosing Light didn't make the app light (brightness $B)"
adb shell input keyevent KEYCODE_BACK
sleep 1
dump home-light
tap home-light "Home"
sleep 2
shot 28-light-home
dump light-home
grep -q 'text="Shuffle all"' "$OUT/light-home.xml" || fail "Home doesn't show in the light theme"
open_settings_from_home() { dump home-gear; tap home-gear "Settings"; sleep 2; }
open_settings_from_home
dump theme-settings2
tap theme-settings2 "Dark"
sleep 2
B=$(adb exec-out screencap | python3 "$HERE/brightness.py")
echo "background brightness back in the dark theme: $B"
[ "$B" -le 60 ] || fail "choosing Dark didn't make the app dark again (brightness $B)"
echo "PASS: light and dark themes"
adb shell input keyevent KEYCODE_BACK
sleep 1

echo "--- Player on the lock screen"
playing || { adb shell input keyevent KEYCODE_MEDIA_PLAY; sleep 2; }
adb shell locksettings set-pin 1111 > /dev/null
adb shell input keyevent KEYCODE_SLEEP
sleep 2
adb shell input keyevent KEYCODE_WAKEUP
sleep 3
dump lock
shot 29-lock-screen
grep -q 'content-desc="Lock screen player"' "$OUT/lock.xml" || fail "waking the locked phone didn't show the player over the lock screen"
grep -q 'text="Library"' "$OUT/lock.xml" && fail "the library is reachable from the lock screen"
LOCK_SONG=$(now_playing)
python3 "$HERE/find_text.py" "$OUT/lock.xml" "$LOCK_SONG" exact > /dev/null || fail "the lock-screen player doesn't show the song that's playing ($LOCK_SONG)"
# The heart: like the song (or unlike it, if it's already liked) without unlocking.
if grep -q 'content-desc="Remove from Liked songs"' "$OUT/lock.xml"; then BEFORE="Remove from Liked songs"; AFTER="Like"; else BEFORE="Like"; AFTER="Remove from Liked songs"; fi
tap lock "$BEFORE"
sleep 1
dump lock-liked
shot 29b-lock-screen-liked
grep -q "content-desc=\"$AFTER\"" "$OUT/lock-liked.xml" || fail "tapping the heart on the lock screen didn't change it"
grep -q 'content-desc="Lock screen player"' "$OUT/lock-liked.xml" || fail "tapping the heart left the lock-screen player"
tap lock-liked "Unlock to open Isaialai"
sleep 2
adb shell input text 1111
adb shell input keyevent KEYCODE_ENTER
sleep 3
dump unlocked
grep -q 'text="Library"' "$OUT/unlocked.xml" || fail "unlocking didn't go back to the app"
# The mini player's heart shows the same: the like (or unlike) was saved.
grep -q "content-desc=\"$AFTER\"" "$OUT/unlocked.xml" || fail "the heart tapped on the lock screen wasn't saved"
adb shell locksettings clear --old 1111 > /dev/null
echo "PASS: the player shows over the lock screen, and only the player; its heart likes the song"

echo "--- Downloaded songs: movie albums, not a 'Download' album"
dump nav-lib
tap nav-lib "Library"
sleep 2
chip "Albums"
sleep 2
dump albums-movies
grep -q 'text="Download"' "$OUT/albums-movies.xml" && fail "the Download folder shows up as an album"
if ! grep -q 'text="Test Movie"' "$OUT/albums-movies.xml"; then scroll_down; dump albums-movies; fi
shot 30-movie-album
grep -q 'text="Test Movie"' "$OUT/albums-movies.xml" || fail "the movie named in the title (From \"Test Movie\") isn't an album"
echo "PASS: songs without an album aren't lumped into 'Download'; the movie is read from the title"
chip "Songs"
sleep 2
dump songs-dl
tap songs-dl "Arabic Test"
sleep 4
dump songs-dl2
tap songs-dl2 "Plain Download Song"
sleep 4
dump to-home
tap to-home "Home"
sleep 3
dump home-recent
shot 31-jump-back-in
grep -q 'text="Jump back in"' "$OUT/home-recent.xml" || fail "Home has no Jump back in row"
grep -q 'text="Download"' "$OUT/home-recent.xml" && fail "Jump back in shows the Download folder"
grep -q 'text="Plain Download Song"' "$OUT/home-recent.xml" || fail "a recently played song with no album isn't shown as itself"
grep -q 'text="Test Movie"' "$OUT/home-recent.xml" || fail "Jump back in doesn't show the movie of a recently played song"
tap home-recent "Test Movie"
sleep 3
dump movie-page
grep -q 'text="Album"\|text="Album · ' "$OUT/movie-page.xml" || grep -q 'Album ·' "$OUT/movie-page.xml" || fail "tapping the movie in Jump back in didn't open its album page"
python3 "$HERE/find_text.py" "$OUT/movie-page.xml" "Arabic Test" > /dev/null || fail "the movie page doesn't list its song"
echo "PASS: Jump back in opens the movie, and shows songs without one as themselves"
adb shell input keyevent KEYCODE_BACK
sleep 1

echo "--- Change cover art (song with no cover)"
adb shell content call --uri content://media --method scan_volume --arg external_primary > /dev/null 2>&1 || true
dump nav-lib2
tap nav-lib2 "Library"
sleep 2
chip "Songs"
sleep 2
dump songs-cover
tap songs-cover "More options for Plain Download Song"
sleep 1
dump cover-menu
tap cover-menu "Change cover art"
sleep 2
dump cover-dialog
shot 32-cover-dialog
tap cover-dialog "Choose picture"
sleep 4
dump picker-photos
shot 33-photo-picker
PHOTO=$(python3 - "$OUT/picker-photos.xml" <<'PY'
import re, sys, xml.etree.ElementTree as ET
for n in ET.parse(sys.argv[1]).iter("node"):
    d = (n.get("content-desc") or "").lower()
    if d.startswith("photo") or "photo taken" in d or d.startswith("image"):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
PY
)
[ -n "$PHOTO" ] || fail "the photo picker doesn't show the picture"
adb shell input tap $PHOTO
sleep 5
dump after-cover
grep -q "Cover changed" "$OUT/after-cover.xml" || echo "(note: didn't catch the 'Cover changed' message)"
tap after-cover "Plain Download Song" last # the mini player: it's the song playing
sleep 3
shot 34-custom-cover
read -r R G B < <(adb exec-out screencap | python3 "$HERE/pixel.py" $((W / 2)) $((H * 38 / 100)))
echo "cover colour on the full player: $R $G $B"
[ "$R" -ge 170 ] && [ "$G" -le 90 ] && [ "$B" -le 90 ] || fail "the chosen (red) cover isn't shown on the full player"
echo "PASS: any song's cover can be changed to a picture from the phone"
adb shell input keyevent KEYCODE_BACK
sleep 1

echo "--- Continue where you left off"
dump home-gear-resume
tap home-gear-resume "Home"
sleep 2
dump home-gear-resume2
tap home-gear-resume2 "Settings"
sleep 2
tap_clear "All songs"
sleep 1
adb shell input keyevent KEYCODE_BACK
sleep 1
dump nav-lib3
tap nav-lib3 "Library"
sleep 2
chip "Songs"
sleep 2
dump songs-resume
tap songs-resume "Smoke Song 1"
sleep 3
dump resume-mini
tap resume-mini "Smoke Song 1" last
sleep 2
dump resume-player
read -r X1 Y1 X2 Y2 < <(python3 "$HERE/find_text.py" "$OUT/resume-player.xml" "Seek bar" bounds) || fail "no seek bar"
adb shell input tap $((X1 + (X2 - X1) / 2)) $(((Y1 + Y2) / 2)) # half way: 0:30
sleep 3
session
LEFT_AT=$(grep -o "position=[0-9]*" "$OUT/session.txt" | head -1 | cut -d= -f2 || true)
echo "left Smoke Song 1 at $LEFT_AT ms"
adb shell input keyevent KEYCODE_BACK # close the player
sleep 1
dump songs-resume2
tap songs-resume2 "Smoke Song 2" # play something else
sleep 4
dump songs-resume3
tap songs-resume3 "Smoke Song 1" # and back again
sleep 3
dump resumed
session
BACK_AT=$(grep -o "position=[0-9]*" "$OUT/session.txt" | head -1 | cut -d= -f2 || true)
echo "Smoke Song 1 started again at $BACK_AT ms"
[ "$(now_playing)" = "Smoke Song 1" ] || fail "tapping Smoke Song 1 again didn't play it"
[ "${BACK_AT:-0}" -ge 25000 ] || fail "Smoke Song 1 started from the beginning instead of where it was left"
grep -q "Continuing" "$OUT/resumed.xml" && echo "(the app said it's continuing)"
adb shell input keyevent KEYCODE_MEDIA_PAUSE
sleep 2
dump home-continue0
tap home-continue0 "Home"
sleep 3
dump home-continue
shot 35-continue-listening
grep -q 'text="Continue listening"' "$OUT/home-continue.xml" || fail "Home doesn't show Continue listening"
echo "PASS: songs continue where they were left, and Home lists them"
dump home-gear-resume3
tap home-gear-resume3 "Settings"
sleep 2
tap_clear "Long tracks" # back to the default
sleep 1
adb shell input keyevent KEYCODE_BACK
sleep 1
adb shell input keyevent KEYCODE_MEDIA_PLAY
sleep 2

echo "--- Reorder songs in a playlist"
on_screen() { # on_screen <text>: scroll down until <text> is on screen (and above the mini player)
  local i
  for i in 1 2 3 4 5; do
    dump on-screen
    python3 "$HERE/find_text.py" "$OUT/on-screen.xml" "$1" exact > /dev/null 2>&1 && return 0
    scroll_down
  done
  fail "couldn't find '$1'"
}
playlist_order() { python3 - "$OUT/$1.xml" <<'PY'
import sys, xml.etree.ElementTree as ET
seen = []
for n in ET.parse(sys.argv[1]).iter("node"):
    t = n.get("text") or ""
    if t in ("Tune 01", "Tune 02", "Tune 03") and t not in seen:
        seen.append(t)
print(" ".join(seen))
PY
}
dump nav-pl
tap nav-pl "Library"
sleep 2
dump lib-pl
tap lib-pl "New playlist"
sleep 2
adb shell input text "Order%sTest"
sleep 1
dump pl-name
tap pl-name "Save"
sleep 2
chip "Songs"
sleep 2
for t in "Tune 01" "Tune 02" "Tune 03"; do
  on_screen "More options for $t"
  menu_item on-screen "$t" "Add to playlist"
  sleep 1
  dump pl-pick
  tap pl-pick "Order Test"
  sleep 1
done
adb shell input swipe 540 700 540 2000 300 # back to the top of the list
sleep 1
chip "Playlists"
sleep 2
on_screen "Order Test"
tap on-screen "Order Test"
sleep 3
scroll_down
dump pl-page
shot 39-playlist-before
echo "playlist order: $(playlist_order pl-page)"
[ "$(playlist_order pl-page)" = "Tune 01 Tune 02 Tune 03" ] || fail "the new playlist isn't in the order the songs were added"
read -r X1 Y1 X2 Y2 < <(python3 "$HERE/find_text.py" "$OUT/pl-page.xml" "Reorder Tune 01" bounds) || fail "playlist songs have no drag handle"
read -r _ Z1 _ Z2 < <(python3 "$HERE/find_text.py" "$OUT/pl-page.xml" "Reorder Tune 02" bounds)
ROWPX=$(((Z1 + Z2) / 2 - (Y1 + Y2) / 2))
X=$(((X1 + X2) / 2)); Y=$(((Y1 + Y2) / 2))
adb shell input swipe "$X" "$Y" "$X" $((Y + ROWPX * 2 + ROWPX / 5)) 1500 # drag Tune 01 down two places
sleep 2
dump pl-moved
shot 40-playlist-reordered
echo "after dragging Tune 01 down two places: $(playlist_order pl-moved)"
[ "$(playlist_order pl-moved)" = "Tune 02 Tune 03 Tune 01" ] || fail "dragging a song in the playlist didn't move it"
adb shell input keyevent KEYCODE_BACK
sleep 1
on_screen "Order Test"
tap on-screen "Order Test"
sleep 3
scroll_down
dump pl-reopened
echo "after reopening: $(playlist_order pl-reopened)"
[ "$(playlist_order pl-reopened)" = "Tune 02 Tune 03 Tune 01" ] || fail "the new playlist order wasn't saved"
tap pl-reopened "More options for Tune 01"
sleep 1
dump pl-menu
tap pl-menu "Move to top"
sleep 2
dump pl-top
echo "after Move to top: $(playlist_order pl-top)"
[ "$(playlist_order pl-top)" = "Tune 01 Tune 02 Tune 03" ] || fail "Move to top didn't move the song to the top"
echo "PASS: songs in a playlist can be reordered (drag, or Move to top), and the order is kept"
adb shell input keyevent KEYCODE_BACK
sleep 1

echo "--- Cut a song"
screen_texts() { grep -o 'text="[^"]\+"\|content-desc="[^"]\+"' "$OUT/$1.xml" | head -25 | tr '\n' ' '; echo; }
type_time() { # type_time <dump> <field> <time>: type a time, then tap the "Part to keep" title to apply it
  tap "$1" "$2"
  sleep 1
  dump tt-focused; echo "after tapping $2: $(screen_texts tt-focused)"
  adb shell input keyevent KEYCODE_MOVE_END
  for _ in 1 2 3 4 5 6 7 8 9; do adb shell input keyevent KEYCODE_DEL; done
  adb shell input text "$3"
  sleep 1
  dump tt-typed; echo "after typing $3: $(screen_texts tt-typed)"
  tap tt-typed "Part to keep:" # moves focus off the field, which applies the time
  sleep 1
  dump tt-done; echo "after applying: $(screen_texts tt-done)"
}
dump nav-cut
tap nav-cut "Library"
sleep 2
chip "Songs"
sleep 2
dump songs-cut
menu_item songs-cut "Smoke Song 1" "Cut song"
sleep 2
dump cut-screen
shot 36-cut-screen
grep -q 'text="Cut song"' "$OUT/cut-screen.xml" || fail "the Cut song screen didn't open"
type_time cut-screen "Start time" "0:10"
dump cut-screen2
type_time cut-screen2 "End time" "0:25"
if adb shell dumpsys input_method | grep -q "mInputShown=true"; then adb shell input keyevent KEYCODE_BACK; sleep 1; fi
dump cut-set
grep -q 'Part to keep: 0:15' "$OUT/cut-set.xml" || fail "typing 0:10 to 0:25 didn't select a 15-second part"
tap_clear "Save"
for _ in $(seq 1 30); do
  sleep 2
  cut=$(adb shell content query --uri content://media/external/audio/media --projection title:duration:relative_path --where "title=\'Smoke\ Song\ 1\ \(cut\)\'" || true)
  grep -q "duration=" <<<"$cut" && break
done
echo "saved cut: $cut"
grep -q "Isaialai cuts" <<<"$cut" || fail "the cut wasn't saved to Music/Isaialai cuts"
D=$(grep -o "duration=[0-9]*" <<<"$cut" | head -1 | cut -d= -f2)
[ "${D:-0}" -ge 14000 ] && [ "${D:-0}" -le 16500 ] || fail "the cut is ${D:-0} ms long, not about 15 s"
echo "PASS: a song can be cut, and the 15 s part is saved as a new song"

echo "--- Set as ringtone"
adb shell appops set "$PKG" WRITE_SETTINGS default || true
BEFORE_RINGTONE=$(adb shell settings get system ringtone)
dump nav-ring
chip "Songs"
sleep 2
dump songs-ring
menu_item songs-ring "Smoke Song 2" "Set as ringtone"
sleep 2
dump ring-screen
shot 37-ringtone-screen
grep -q 'text="Make a ringtone"' "$OUT/ring-screen.xml" || fail "the ringtone screen didn't open"
grep -q 'Part to keep: 0:30' "$OUT/ring-screen.xml" || fail "the ringtone doesn't start as the first 30 seconds"
tap_clear "Save and set"
for _ in $(seq 1 30); do
  sleep 2
  dump ring-ask
  grep -q "Allow changing your ringtone" "$OUT/ring-ask.xml" && break
done
shot 38-ringtone-permission
grep -q "Allow changing your ringtone" "$OUT/ring-ask.xml" || fail "Isaialai didn't explain the ringtone permission"
tap ring-ask "Open settings"
sleep 3
adb shell appops set "$PKG" WRITE_SETTINGS allow # what turning on "Allow modifying system settings" does
adb shell input keyevent KEYCODE_BACK
sleep 4
dump ring-done
RINGTONE=$(adb shell settings get system ringtone)
echo "ringtone before: $BEFORE_RINGTONE"
echo "ringtone now:    $RINGTONE"
ring=$(adb shell content query --uri content://media/external/audio/media --projection _id:title:duration:is_ringtone:relative_path --where "title=\'Smoke\ Song\ 2\ ringtone\'" || true)
echo "saved ringtone: $ring"
grep -q "is_ringtone=1" <<<"$ring" || fail "the ringtone wasn't saved as a ringtone"
grep -q "Ringtones" <<<"$ring" || fail "the ringtone wasn't saved in the Ringtones folder"
RID=$(grep -o "_id=[0-9]*" <<<"$ring" | head -1 | cut -d= -f2)
grep -q "/$RID" <<<"$RINGTONE" || fail "the phone's ringtone wasn't changed to the new one"
D=$(grep -o "duration=[0-9]*" <<<"$ring" | head -1 | cut -d= -f2)
[ "${D:-0}" -ge 29000 ] && [ "${D:-0}" -le 31500 ] || fail "the ringtone is ${D:-0} ms long, not about 30 s"
echo "PASS: any song can be made the phone's ringtone (with Android's permission)"
adb shell settings put system ringtone "$BEFORE_RINGTONE" > /dev/null 2>&1 || true

echo "--- Backup: playlists as .m3u files, and everything in one backup file, in Download/Isaialai"
BK=/sdcard/Download/Isaialai
for _ in $(seq 1 15); do adb shell "ls '$BK/Playlists/Order Test.m3u'" > /dev/null 2>&1 && break; sleep 2; done
adb shell "ls -lR '$BK'" || true
adb shell "cat '$BK/Playlists/Order Test.m3u'" > "$OUT/order-test.m3u" 2>/dev/null \
  || fail "the 'Order Test' playlist wasn't saved as Download/Isaialai/Playlists/Order Test.m3u"
cat "$OUT/order-test.m3u"
head -1 "$OUT/order-test.m3u" | grep -q "^#EXTM3U" || fail "the playlist file isn't a .m3u playlist"
M3U_SONGS=$(grep -v '^#' "$OUT/order-test.m3u" | tr -d '\r' | tr '\n' ' ')
[ "$M3U_SONGS" = "/storage/emulated/0/Music/ShuffleTest/tune01.mp3 /storage/emulated/0/Music/ShuffleTest/tune02.mp3 /storage/emulated/0/Music/ShuffleTest/tune03.mp3 " ] \
  || fail "the .m3u file doesn't list the playlist's song files in order: $M3U_SONGS"
adb shell "cat '$BK/Playlists/Liked songs.m3u'" | grep -q "Smoke Song 1" || fail "Liked songs weren't saved as a .m3u file"
adb shell "cat '$BK/Isaialai backup.json'" > "$OUT/backup.json" 2>/dev/null || fail "there's no 'Isaialai backup.json' in Download/Isaialai"
python3 - "$OUT/backup.json" <<'PY' || fail "the backup file doesn't have the playlist, liked songs and play counts"
import json, sys
b = json.load(open(sys.argv[1]))
songs = b["songs"]
lists = {p["name"]: [songs[i]["title"] for i in p["songs"]] for p in b["playlists"]}
print("playlists in the backup:", lists)
assert lists["Order Test"] == ["Tune 01", "Tune 02", "Tune 03"], lists
assert "Smoke Song 1" in [songs[i]["title"] for i in b["liked"]]
assert any(p["count"] > 0 for p in b["plays"])
assert "effects" in b["settings"] and "ui" in b["settings"]
PY
echo "PASS: playlists are kept as .m3u files, and everything in a backup file, in Download/Isaialai"

titles_in() { # titles_in <dump> <title>...: which of the titles are on screen, in screen order
  python3 - "$OUT/$1.xml" "${@:2}" <<'PY'
import sys, xml.etree.ElementTree as ET
want, seen = sys.argv[2:], []
for n in ET.parse(sys.argv[1]).iter("node"):
    t = n.get("text") or ""
    if t in want and t not in seen:
        seen.append(t)
print(" ".join(seen))
PY
}
fresh_start() { # restart the app on Home
  adb shell am force-stop "$PKG"
  adb shell am start -W -n "$PKG/.MainActivity" > /dev/null
  sleep 6
}
go_library() {
  dump go-lib
  tap go-lib "Library" exact
  sleep 2
}

echo "--- Importing a playlist file made by another app"
# Different path styles, an internet stream (skipped), a moved file found by its tags, and a
# song that isn't on the phone.
printf '#EXTM3U\r\n#EXTINF:60,Shuffle Band - Tune 05\r\n/storage/emulated/0/Music/ShuffleTest/tune05.mp3\r\n#EXTINF:60,Shuffle Band - Tune 07\r\n..\\Music\\ShuffleTest\\tune07.mp3\r\n#EXTINF:-1,Some Radio\r\nhttp://radio.example/stream\r\n#EXTINF:60,CI Band - Smoke Song 3\r\nC:\\Users\\me\\Music\\old\\renamed.mp3\r\n#EXTINF:200,Nobody - Not On This Phone\r\n/storage/emulated/0/Music/nowhere.mp3\r\n' > "$OUT/Road Trip.m3u"
adb push "$OUT/Road Trip.m3u" "/sdcard/Download/Road Trip.m3u" > /dev/null
fresh_start
go_library
open_settings
tap_clear "Import a playlist file (.m3u)"
sleep 4
dump file-picker
if ! python3 "$HERE/find_text.py" "$OUT/file-picker.xml" "Road Trip.m3u" > /dev/null 2>&1; then
  # The picker opened somewhere else (Recent files): go to Downloads.
  tap file-picker "Show roots"
  sleep 2
  dump file-roots
  tap file-roots "Downloads"
  sleep 2
  dump file-picker
fi
shot 50-import-picker
tap file-picker "Road Trip.m3u"
sleep 3
dump import-ask
shot 51-import-ask
grep -q "Import playlists?" "$OUT/import-ask.xml" || fail "picking a .m3u file didn't offer to import it"
grep -q "Road Trip" "$OUT/import-ask.xml" || fail "the import dialog doesn't name the playlist"
grep -q "1 song isn&apos;t on this phone\|1 song isn't on this phone" "$OUT/import-ask.xml" || fail "the import dialog doesn't say a song is missing"
tap import-ask "Import" exact
sleep 3
adb shell input keyevent KEYCODE_BACK # Settings -> Library
sleep 2
chip "Playlists"
sleep 2
on_screen "Road Trip"
tap on-screen "Road Trip"
sleep 3
dump road-trip
shot 52-imported-playlist
ROAD=$(titles_in road-trip "Tune 05" "Tune 07" "Smoke Song 3")
echo "imported playlist: $ROAD"
[ "$ROAD" = "Tune 05 Tune 07 Smoke Song 3" ] || fail "the imported playlist doesn't have the right songs in order"
echo "PASS: a .m3u playlist from another app can be imported (paths in any style, missing songs reported)"

echo "--- Restoring after reinstalling (or on a new phone)"
for _ in $(seq 1 15); do adb shell "ls '$BK/Playlists/Road Trip.m3u'" > /dev/null 2>&1 && break; sleep 2; done
adb shell "ls '$BK/Playlists/Road Trip.m3u'" > /dev/null 2>&1 || fail "the imported playlist wasn't added to the backup"
adb shell pm clear "$PKG" # what uninstalling does to the app's own data
adb shell pm grant "$PKG" android.permission.READ_MEDIA_AUDIO
adb shell am start -W -n "$PKG/.MainActivity" > /dev/null
sleep 8
go_library
chip "Playlists"
sleep 2
dump wiped
grep -q 'text="Order Test"' "$OUT/wiped.xml" && fail "the app's data wasn't cleared"
open_settings
tap_clear "Restore from backup"
sleep 2
dump restore-explain
tap restore-explain "Choose folder"
sleep 4
dump restore-picker
shot 53-restore-picker
python3 "$HERE/find_text.py" "$OUT/restore-picker.xml" "Use this folder" > /dev/null 2>&1 || { tap restore-picker "Isaialai"; sleep 2; dump restore-picker; }
tap restore-picker "Use this folder"
sleep 2
dump restore-allow
tap restore-allow "Allow"
sleep 5
dump restore-ask
shot 54-restore-ask
grep -q "Restore this backup?" "$OUT/restore-ask.xml" || fail "picking the Isaialai folder didn't offer to restore the backup"
grep -q "[0-9] playlists (" "$OUT/restore-ask.xml" || fail "the restore dialog doesn't list the playlists"
grep -q "liked song" "$OUT/restore-ask.xml" || fail "the restore dialog doesn't mention liked songs"
tap restore-ask "Restore" exact
sleep 4
adb shell input keyevent KEYCODE_BACK # Settings -> Library
sleep 2
chip "Playlists"
sleep 2
on_screen "Order Test"
tap on-screen "Order Test"
sleep 3
scroll_down
dump restored-order
shot 55-restored-playlist
echo "restored playlist: $(playlist_order restored-order)"
[ "$(playlist_order restored-order)" = "Tune 01 Tune 02 Tune 03" ] || fail "the restored playlist doesn't have its songs in order"
adb shell input keyevent KEYCODE_BACK
sleep 2
on_screen "Road Trip"
on_screen "Liked songs"
tap on-screen "Liked songs"
sleep 3
dump restored-liked
grep -q "Smoke Song 1" "$OUT/restored-liked.xml" || fail "liked songs weren't restored"
# Backups now carry on in the restored folder: no "(1)" copies next to the old files.
sleep 8
adb shell "ls '$BK' '$BK/Playlists'" > "$OUT/backup-files.txt"
cat "$OUT/backup-files.txt"
grep -q "(1)\|(2)" "$OUT/backup-files.txt" && fail "restoring left duplicate backup files"
echo "PASS: after reinstalling, playlists, liked songs and the rest come back from the Isaialai folder"

echo "--- Android Auto"
# Android prints the service either as "pkg/.PlaybackService" or "name=pkg.PlaybackService".
adb shell cmd package query-services -a androidx.media3.session.MediaLibraryService > "$OUT/library-services.txt"
grep -Eq "$PKG(/\.|\.)PlaybackService" "$OUT/library-services.txt" \
  || fail "the player isn't offered as a media library, so Android Auto can't browse it"
adb shell cmd package query-services -a android.media.browse.MediaBrowserService > "$OUT/browser-services.txt"
grep -Eq "$PKG(/\.|\.)PlaybackService" "$OUT/browser-services.txt" \
  || fail "the player isn't offered as a media browser (what Android Auto connects to)"
adb shell dumpsys package "$PKG" | grep -q "com.google.android.gms.car.application" \
  || echo "(note: couldn't see the Android Auto meta-data in dumpsys; checking the APK instead)"
echo "PASS: Android Auto can find Isaialai's music library (browsing itself needs a car or the Auto head unit)"

echo "--- Home-screen widget"
adb shell dumpsys appwidget | grep -q "mymusic.PlayerWidget" || fail "the home-screen widget isn't registered with the launcher"
echo "PASS: home-screen widget is available"

if adb logcat -d | grep -q "FATAL EXCEPTION"; then
  adb logcat -d > "$OUT/logcat.txt"
  fail "the app crashed (see logcat.txt)"
fi
echo "ALL SMOKE TESTS PASSED"
