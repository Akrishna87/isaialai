#!/usr/bin/env bash
# Makes the songs the emulator test uses (sine tones with tags) in <dir>.
# CI caches the result by this file's hash, so ffmpeg is only needed when this file changes.
# Usage: make-test-songs.sh <dir>
set -euo pipefail
OUT="$1"
VENV="$(mktemp -d)/venv"
mkdir -p "$OUT"
for i in 1 2 3; do
  ffmpeg -nostdin -loglevel error -f lavfi -i "sine=frequency=$((300 + i * 110)):duration=60" -c:a libmp3lame \
    -metadata title="Smoke Song $i" -metadata artist="CI Band" -metadata album="CI Album" -metadata track=$i \
    -metadata composer="CI Composer" -metadata date=2021 \
    "$OUT/song$i.mp3"
done
# a 12-song album for the shuffle test
for i in $(seq -w 1 12); do
  ffmpeg -nostdin -loglevel error -f lavfi -i "sine=frequency=$((200 + 10#$i * 40)):duration=60" -c:a libmp3lame \
    -metadata title="Tune $i" -metadata artist="Shuffle Band" -metadata album="Shuffle Album" -metadata track=$((10#$i)) \
    -metadata composer="CI Composer" \
    "$OUT/tune$i.mp3"
done
# a much louder song, for even volume
ffmpeg -nostdin -loglevel error -f lavfi -i "sine=frequency=500:duration=60" -af volume=7 -c:a libmp3lame \
  -metadata title="Loud Song" -metadata artist="Loud Band" -metadata album="Loud Album" "$OUT/loud.mp3"
# the same song saved twice (in two folders), for the duplicate finder
ffmpeg -nostdin -loglevel error -f lavfi -i "sine=frequency=880:duration=45" -c:a libmp3lame \
  -metadata title="Twin Song" -metadata artist="Twin Band" -metadata album="Twin Album" "$OUT/twin.mp3"
# Android marks songs in a Podcasts folder as "not music"
ffmpeg -nostdin -loglevel error -f lavfi -i "sine=frequency=660:duration=60" -c:a libmp3lame \
  -metadata title="Smoke Podcast Song" -metadata artist="CI Band" -metadata album="CI Extras" "$OUT/podcast.mp3"
# goes in a folder with a .nomedia file, which Android's media library skips
ffmpeg -nostdin -loglevel error -f lavfi -i "sine=frequency=770:duration=60" -c:a libmp3lame \
  -metadata title="Smoke Hidden Song" -metadata artist="Hidden Band" -metadata album="Hidden Album" "$OUT/hidden.mp3"
# timed lyrics next to it, as an .lrc file
printf '%s\n' "[ti:Smoke Hidden Song]" "[00:00.50]First line of the hidden song" "[00:05.00]Second line here" \
  "[00:20.00]Twenty seconds in" "[00:40.00]Last line" > "$OUT/hidden.lrc"
# plain lyrics saved inside a song (an ID3 USLT frame)
python3 -m venv "$VENV" && "$VENV/bin/pip" install --quiet mutagen==1.47.0
"$VENV/bin/python" - "$OUT" <<'PY'
import sys
from mutagen.id3 import ID3, USLT
tags = ID3(sys.argv[1] + "/song2.mp3")
tags.add(USLT(encoding=3, lang="eng", desc="", text="Smoke lyrics line one\nSmoke lyrics line two"))
tags.save()
PY
