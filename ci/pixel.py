"""Print the average colour "r g b" of a small square of the screen, from a raw `screencap`.

Usage: adb exec-out screencap | python3 pixel.py <x> <y>
"""
import struct
import sys

x, y = int(sys.argv[1]), int(sys.argv[2])
data = sys.stdin.buffer.read()
w, h = struct.unpack_from("<II", data, 0)
header = len(data) - w * h * 4  # 12 bytes, or 16 on newer Android
r = g = b = n = 0
for dy in range(-10, 11, 5):
    for dx in range(-10, 11, 5):
        i = header + ((y + dy) * w + (x + dx)) * 4
        r += data[i]
        g += data[i + 1]
        b += data[i + 2]
        n += 1
print(r // n, g // n, b // n)
