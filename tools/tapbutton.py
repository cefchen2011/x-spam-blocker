"""Find the injected affordance in the accessibility tree and tap it precisely."""
import subprocess, xml.etree.ElementTree as ET, re, sys, time

SERIAL = "2029b7f5"

def adb(*args):
    return subprocess.run(["adb", "-s", SERIAL] + list(args), capture_output=True)

def dump():
    adb("shell", "uiautomator", "dump", "/sdcard/ui_t.xml")
    adb("pull", "/sdcard/ui_t.xml", r"C:\workspace\x-spam-blocker\shots\ui_t.xml")
    return ET.parse(r"C:\workspace\x-spam-blocker\shots\ui_t.xml")

def parse_bounds(b):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", b)
    return tuple(int(x) for x in m.groups())

target = sys.argv[1] if len(sys.argv) > 1 else "屏蔽"
t = dump()
for n in t.iter("node"):
    tx = n.get("text") or ""
    if target not in tx:
        continue
    left, top, right, bottom = parse_bounds(n.get("bounds"))
    # the affordance is appended after a newline, so it is the last visual line
    lines = tx.count("\n") + 1
    # estimate wrapped lines by characters per line for CJK-heavy text
    print("node text:", repr(tx[:160]))
    print("bounds:", (left, top, right, bottom), "explicit lines:", lines)
    height = bottom - top
    # Locate the affordance substring position to estimate which wrapped line it is on.
    idx = tx.find(target)
    # Count display width: CJK chars count 2, others 1
    def width(s):
        return sum(2 if ord(c) > 0x2000 else 1 for c in s)
    total_w = width(tx)
    before_w = width(tx[:idx])
    # assume the box is filled top-to-bottom in reading order
    avail = right - left
    est_lines = max(1, int((total_w * (height / max(1, lines))) / max(1, avail)) if False else lines)
    line_h = height / max(1, est_lines)
    line_no = min(est_lines - 1, int(before_w / max(1, (total_w / est_lines))))
    y = int(top + line_h * (line_no + 0.5))
    x = int(left + min(avail - 10, (before_w % max(1, int(total_w / est_lines))) * (avail / max(1, int(total_w / est_lines)))) + 40)
    print("total_w=%d before_w=%d est_lines=%d line_no=%d -> tap (%d,%d)" % (total_w, before_w, est_lines, line_no, x, y))
    adb("logcat", "-c")
    adb("shell", "su", "-c", "input tap %d %d" % (x, y))
    time.sleep(3)
    out = adb("logcat", "-d").stdout.decode("utf-8", "replace")
    hits = [l for l in out.splitlines() if "XSBlock" in l and ("tap on" in l or "MUTE" in l or "link handler" in l)]
    print("handler hits:", hits if hits else "NONE")
    break
