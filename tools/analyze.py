import json, sys, re

p = sys.argv[1] if len(sys.argv) > 1 else r"C:\workspace\x-spam-blocker\dumps\xsb\xsb_HomeTimeline_1.json"
d = json.load(open(p, encoding="utf-8"))
print("top keys:", list(d.keys()))

def walk(o, path=""):
    """yield (path, dict) for every dict that looks like a tweet legacy object"""
    if isinstance(o, dict):
        if "full_text" in o:
            yield path, o
        for k, v in o.items():
            yield from walk(v, path + "." + k)
    elif isinstance(o, list):
        for i, v in enumerate(o):
            yield from walk(v, path + "[%d]" % i)

found = list(walk(d))
print("objects with full_text:", len(found))
print()
for i, (path, obj) in enumerate(found[:3]):
    print("=" * 70)
    print("PATH:", path)
    print("keys:", sorted(obj.keys()))
    print("full_text:", json.dumps(obj.get("full_text"))[:300])
    print("entities:", json.dumps(obj.get("entities"), ensure_ascii=False)[:700])
    print()
