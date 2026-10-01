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
now_playing() { session; grep -o "description=[^,]*" "$OUT/session.txt" | head -1 | sed 's/description=//'; }
scroll_down() { # swipe up on the middle of the screen
  local size w h
  size=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1)
  w=${size%x*}; h=${size#*x}
  adb shell input swipe $((w / 2)) $((h * 3 / 4)) $((w / 2)) $((h / 4)) 300
  sleep 1
}
playing() { session; grep -Eq "\{state=(PLAYING|3)" "$OUT/session.txt"; }
chip() { # chip <name>: tap a Library tab chip, scrolling the chip row sideways if it's out of view
  local i y
  for i in 1 2 3 4 5 6; do
    dump chips
    if python3 "$HERE/find_text.py" "$OUT/chips.xml" "$1" > /dev/null 2>&1; then tap chips "$1"; return 0; fi
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
# The same song in two folders, for the duplicate finder.
adb shell mkdir -p /sdcard/Music/TwinA /sdcard/Music/TwinB
adb push "$SONGS/twin.mp3" /sdcard/Music/TwinA/ > /dev/null
adb push "$SONGS/twin.mp3" /sdcard/Music/TwinB/ > /dev/null
adb shell content call --uri content://media --method scan_volume --arg external_primary > /dev/null 2>&1 || true
for _ in $(seq 1 30); do
  [ "$(adb shell content query --uri content://media/external/audio/media --projection title | grep -c "Twin Song")" -ge 2 ] && break
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

echo "--- Home-screen widget"
adb shell dumpsys appwidget | grep -q "mymusic.PlayerWidget" || fail "the home-screen widget isn't registered with the launcher"
echo "PASS: home-screen widget is available"

if adb logcat -d | grep -q "FATAL EXCEPTION"; then
  adb logcat -d > "$OUT/logcat.txt"
  fail "the app crashed (see logcat.txt)"
fi
echo "ALL SMOKE TESTS PASSED"
