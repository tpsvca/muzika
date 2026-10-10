"""ui.py SERIAL find|tap|longtap|texts [regex] - uiautomator helper"""
import re, subprocess, sys, time, xml.etree.ElementTree as ET
import os
ADB = os.path.expanduser("~/android/sdk/platform-tools/adb")
serial, cmd = sys.argv[1], sys.argv[2]
pat = re.compile(sys.argv[3], re.I) if len(sys.argv) > 3 else None
def sh(*a): return subprocess.run([ADB, "-s", serial, "shell", *a], capture_output=True, text=True).stdout
def nodes():
    for _ in range(3):
        sh("uiautomator", "dump", "/sdcard/ui.xml")
        raw = sh("cat", "/sdcard/ui.xml")
        try: return list(ET.fromstring(raw[raw.index("<?xml"):] if "<?xml" in raw else raw).iter("node"))
        except Exception: time.sleep(1)
    return []
def center(n):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
    return (x1 + x2) // 2, (y1 + y2) // 2
ns = nodes()
if cmd == "texts":
    for n in ns:
        t = n.get("text") or n.get("content-desc")
        if t and (not pat or pat.search(t) or pat.search(n.get("resource-id",""))): print(f'{t!r} id={n.get("resource-id")} {n.get("bounds")}')
    sys.exit(0)
hits = [n for n in ns if pat.search(n.get("text","")) or pat.search(n.get("content-desc","")) or pat.search(n.get("resource-id",""))]
if not hits: print("NOTFOUND", sys.argv[3]); sys.exit(1)
x, y = center(hits[0])
if cmd == "tap": sh("input", "tap", str(x), str(y))
elif cmd == "longtap": sh("input", "swipe", str(x), str(y), str(x), str(y), "1500")
print(cmd, hits[0].get("text") or hits[0].get("content-desc") or hits[0].get("resource-id"), x, y)
