"""Find the injected 屏蔽 control, tap it (trying a few offsets), report module logs."""
import subprocess, re, sys, time
import xml.etree.ElementTree as ET

SERIAL = "2029b7f5"
XML = r"C:\workspace\x-spam-blocker\shots\ui_s.xml"
CJK = 46.0
HALF = 24.0

def adb(*a):
    return subprocess.run(["adb", "-s", SERIAL] + list(a), capture_output=True)

def width(ch):
    return CJK if ord(ch) > 0x1100 else HALF

def dump():
    adb("shell", "uiautomator", "dump", "/sdcard/ui_s.xml")
    adb("pull", "/sdcard/ui_s.xml", XML)
    return ET.parse(XML)

def bounds(b):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", b)
    return tuple(int(x) for x in m.groups())

tree = dump()
hit = None
for n in tree.iter("node"):
    tx = n.get("text") or ""
    if "\u5c4f\u853d" in tx:
        b = bounds(n.get("bounds"))
        # prefer a node comfortably on screen
        if b[1] > 250 and b[3] < 2500:
            hit = (tx, n.get("bounds"))
            break
        if hit is None:
            hit = (tx, n.get("bounds"))

if not hit:
    print("no affordance in the tree")
    sys.exit(1)

tx, b = hit
left, top, right, bottom = bounds(b)
avail = right - left
idx = tx.find("\u5c4f\u853d")

x = 0.0
line = 0
for ch in tx[:idx]:
    if ch == "\n":
        x = 0.0; line += 1; continue
    cw = width(ch)
    if x + cw > avail:
        x = 0.0; line += 1
    x += cw
lines = line + 1
cx = x
for ch in tx[idx:]:
    if ch == "\n":
        x = 0.0; lines += 1; continue
    cw = width(ch)
    if x + cw > avail:
        x = 0.0; lines += 1
    x += cw

line_h = (bottom - top) / max(1, lines)
base_x = left + cx
base_y = top + line_h * (line + 0.5)
print("text=%r" % tx[:110])
print("bounds=%s idx=%d line=%d/%d base=(%d,%d)" % (b, idx, line, lines, base_x, base_y))

adb("logcat", "-c")
for dx in (23, 45, 5, 65, -15):
    txp = int(base_x + dx)
    typ = int(base_y)
    adb("shell", "su", "-c", "input tap %d %d" % (txp, typ))
    time.sleep(3)
    out = adb("logcat", "-d").stdout.decode("utf-8", "replace")
    if "屏蔽 tapped" in out:
        print("hit at offset +%d" % dx)
        break
out = adb("logcat", "-d").stdout.decode("utf-8", "replace")
for l in out.splitlines():
    if "XSBlock" in l and "URL http" not in l:
        print("   " + l.split("XSBlock", 1)[1].strip())
