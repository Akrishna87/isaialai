"""Print the song titles listed under "Up next" on the full player, one per line, in order.

Usage: up_next.py <uiautomator dump> <current title>
The current song's own title (shown under the cover list) is left out.
"""
import sys
import xml.etree.ElementTree as ET

path, current = sys.argv[1], sys.argv[2]
texts = [n.get("text") or "" for n in ET.parse(path).iter("node")]
if "Up next" not in texts:
    sys.exit("the Up next list isn't on screen")
after = texts[texts.index("Up next") + 1:]
for t in after:
    if t.startswith("Tune ") and t != current:
        print(t)
