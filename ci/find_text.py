"""Print the centre "x y" of the on-screen element whose text (or description) matches."""
import re
import sys
import xml.etree.ElementTree as ET

path, needle = sys.argv[1], sys.argv[2]
which = sys.argv[3] if len(sys.argv) > 3 else "first"
nodes = [n for n in ET.parse(path).iter("node") if needle in (n.get("text"), n.get("content-desc"))]
if not nodes:
    sys.exit(f"'{needle}' is not on screen")
node = nodes[0] if which == "first" else nodes[-1]
x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
print((x1 + x2) // 2, (y1 + y2) // 2)
