#!/usr/bin/env bash
# Makes extra test songs from the cached ones by changing their tags (no ffmpeg needed), plus a
# plain red picture to use as a cover.
#   "Arabic Test (From "Test Movie")" - no album saved; the movie is in the title
#   "Plain Download Song"            - no album saved at all
# Both go in the Download folder, where Android would call their album "Download".
# Usage: derive-test-songs.sh <songs dir>
set -euo pipefail
DIR="$1"
VENV="$(mktemp -d)/venv"
python3 -m venv "$VENV"
"$VENV/bin/pip" install --quiet mutagen==1.47.0
"$VENV/bin/python" - "$DIR" <<'PY'
import os, shutil, struct, sys, zlib
from mutagen.id3 import ID3, TIT2, TPE1

d = sys.argv[1]
for src, name, title, artist in [
    ("song3.mp3", "arabic-test.mp3", 'Arabic Test (From "Test Movie")', "Movie Band"),
    ("podcast.mp3", "plain-download.mp3", "Plain Download Song", "Download Band"),
]:
    out = os.path.join(d, name)
    shutil.copyfile(os.path.join(d, src), out)
    tags = ID3(out)
    for frame in ("TALB", "TPE2", "TCOM", "TDRC", "TRCK", "USLT"):
        tags.delall(frame)
    tags.add(TIT2(encoding=3, text=title))
    tags.add(TPE1(encoding=3, text=artist))
    tags.save()

# A 400x400 pure red PNG.
w = h = 400
raw = b"".join(b"\x00" + b"\xff\x00\x00" * w for _ in range(h))
def chunk(kind, data):
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b"")
open(os.path.join(d, "red-cover.png"), "wb").write(png)
PY
