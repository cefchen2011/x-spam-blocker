"""Locate the appended affordance line in the accessibility tree and tap it."""
import subprocess, re, sys, time
import xml.etree.ElementTree as ET

SERIAL = "2029b7f5"
XML = r"C:\workspace\x-spam-blocker\shots\ui_t.xml"

def adb(*args):
    return subprocess.run(["adb", "-s", SERIAL] + list(args), capture_output=True)

adb("shell", "uiautomator", "dump", "/sdcard/ui_t.xml")
adb("pull", "/sdcard/ui_t.xml", XML)
tree = ET.parse(XML)

def bounds(b):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", b)
    return tuple(int(x) for x in m.groups())

def disp_width(s):
    return sum(2 if ord(c) > 0x1100 else 1 for c in s)

target = None
for n in tree.iter("node"):
    tx = n.get("text") or ""
    if "\U0001F6AB" in tx or "\u5c4f\u853d @" in tx:
        target = (tx, n.get("bounds"))
        break

if not target:
    print("affordance not found in the accessibility tree")
    sys.exit(1)

tx, b = target
left, top, right, bottom = bounds(b)
avail = right - left
segs = tx.split("\n")
total_lines = 0
for s in segs:
    total_lines += max(1, -(-disp_width(s) // (avail // 34)))  # ~34 half-width units per line
line_h = (bottom - top) / max(1, total_lines)
last = segs[-1]
y = int(bottom - line_h * 0.5)
x = int(left + min(avail * 0.4, disp_width(last[:4]) * (avail / max(1, disp_width(last))) + 60))
print("text=%r" % tx[:120])
print("bounds=%s lines=%d line_h=%.1f -> tap (%d,%d)" % (b, total_lines, line_h, x, y))

adb("logcat", "-c")
adb("shell", "su", "-c", "input tap %d %d" % (x, y))
time.sleep(5)
out = adb("logcat", "-d").stdout.decode("utf-8", "replace")
hits = [l.split("XSBlock", 1)[1] for l in out.splitlines() if "XSBlock" in l and
        ("tap on" in l or "MUTE" in l or "muted keyword" in l or "link handler" in l)]
print("handler:", hits if hits else "NONE")
