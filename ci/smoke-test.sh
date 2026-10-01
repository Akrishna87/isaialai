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
adb push "$SONGS/hidden.lrc" /sdcard/Music/Hidden/ > /dev/null
adb shell mkdir -p /sdcard/Music/LoudTest
adb push "$SONGS/loud.mp3" /sdcard/Music/LoudTest/ > /dev/null
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
POS=$(grep -o "position=[0-9]*" "$OUT/session.txt" | head -1 | cut -d= -f2)
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
dump queue-before-remove
XY=$(python3 "$HERE/find_text.py" "$OUT/queue-before-remove.xml" "Up next: $C") || fail "$C isn't in Up next"
Y=${XY#* }
adb shell input swipe $((W * 60 / 100)) "$Y" $((W * 5 / 100)) "$Y" 250 # swipe $C away
sleep 2
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
tap settings "Even volume"
sleep 1
tap settings "5 s" # crossfade
sleep 1
dump settings-on
grep -q 'Crossfade: 5 s' "$OUT/settings-on.xml" || fail "choosing a 5 s crossfade didn't stick"
python3 - "$OUT/settings-on.xml" <<'PY' || fail "the Even volume switch didn't turn on"
import sys, xml.etree.ElementTree as ET
nodes = [n for n in ET.parse(sys.argv[1]).iter("node") if n.get("content-desc") == "Even volume"]
sys.exit(0 if nodes and nodes[0].get("checked") == "true" else 1)
PY
adb shell input keyevent KEYCODE_BACK
sleep 1
chip "Songs"
sleep 2
dump songs-loud
tap songs-loud "Loud Song"
sleep 6 # long enough to measure it
open_settings
dump settings-loud
shot 23-even-volume
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
UID_APP=$(adb shell dumpsys package "$PKG" | grep -m1 -o 'userId=[0-9]*' | cut -d= -f2)
STARTED=$(adb shell dumpsys audio | grep "AudioPlaybackConfiguration" | grep "u/pid:$UID_APP/" | grep -c "state:started" || true)
echo "Isaialai audio players running during the crossfade: $STARTED"
shot 24-crossfading
SEEN=0; MAXPOS=0
for _ in $(seq 1 40); do
  session
  if [ "$(grep -o "description=[^,]*" "$OUT/session.txt" | head -1 | sed 's/description=//')" != "Loud Song" ]; then
    P=$(grep -o "position=[0-9]*" "$OUT/session.txt" | head -1 | cut -d= -f2)
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
tap edit-dialog "Title"
sleep 1
tap edit-dialog "Clear Title"
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
grep -q "text=\"$(now_playing)\"" "$OUT/lock.xml" || fail "the lock-screen player doesn't show the song that's playing"
tap lock "Unlock to open Isaialai"
sleep 2
adb shell input text 1111
adb shell input keyevent KEYCODE_ENTER
sleep 3
dump unlocked
grep -q 'text="Library"' "$OUT/unlocked.xml" || fail "unlocking didn't go back to the app"
adb shell locksettings clear --old 1111 > /dev/null
echo "PASS: the player shows over the lock screen, and only the player"

echo "--- Android Auto"
adb shell cmd package query-services -a androidx.media3.session.MediaLibraryService | grep -q "$PKG/.PlaybackService" \
  || fail "the player isn't offered as a media library, so Android Auto can't browse it"
adb shell cmd package query-services -a android.media.browse.MediaBrowserService | grep -q "$PKG/.PlaybackService" \
  || fail "the player isn't offered as a media browser (what Android Auto connects to)"
echo "PASS: Android Auto can find Isaialai's music library (browsing itself needs a car or the Auto head unit)"

echo "--- Home-screen widget"
adb shell dumpsys appwidget | grep -q "mymusic.PlayerWidget" || fail "the home-screen widget isn't registered with the launcher"
echo "PASS: home-screen widget is available"

if adb logcat -d | grep -q "FATAL EXCEPTION"; then
  adb logcat -d > "$OUT/logcat.txt"
  fail "the app crashed (see logcat.txt)"
fi
echo "ALL SMOKE TESTS PASSED"
