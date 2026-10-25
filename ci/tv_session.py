#!/usr/bin/env python3
"""Prints the TV app's player as "<state> | <title>" from `dumpsys media_session` output.

The TV app's player is the media session with the id "tv" (the phone player's has none).
Usage: tv_session.py <dumpsys media_session output>
"""
import re
import sys

text = open(sys.argv[1], encoding="utf-8", errors="replace").read()
# Each session starts with a line naming it, indented by 4 spaces, then its details indented more.
blocks = re.split(r"\n(?=    \S)", text)
for b in blocks:
    head = b.strip().splitlines()[0] if b.strip() else ""
    if "id.tv" not in head and "tv io.github" not in head:
        continue
    state = re.search(r"\{state=([A-Z_]+\(\d+\)|\d+)", b)
    desc = re.search(r"description=([^,\n]*)", b)
    print(f"{state.group(1) if state else 'none'} | {desc.group(1).strip() if desc else ''}")
    sys.exit(0)
print("no tv session | ")
