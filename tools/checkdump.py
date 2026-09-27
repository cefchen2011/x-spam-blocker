import json, sys

p = r"C:\workspace\x-spam-blocker\dumps\xsb\xsb_transformed_1790490844820.json"
d = json.load(open(p, encoding="utf-8"))

def walk(o, path=""):
    if isinstance(o, dict):
        yield path, o
        for k, v in o.items():
            yield from walk(v, path + "." + k)
    elif isinstance(o, list):
        for i, v in enumerate(o):
            yield from walk(v, path + "[%d]" % i)

n = 0
for path, o in walk(d):
    if o.get("__typename") != "Tweet":
        continue
    det = o.get("details") or {}
    txt = det.get("full_text", "")
    if "G:" not in txt:
        continue
    n += 1
    print("=" * 78)
    print("full_text     :", json.dumps(txt, ensure_ascii=False))
    print("display_range :", det.get("display_text_range"))
    print("len(full_text):", len(txt))
    print("url_entities  :", json.dumps(o.get("url_entities"), ensure_ascii=False))
    print("mention_ents  :", json.dumps(o.get("mention_entities"), ensure_ascii=False)[:200])
    if n >= 2:
        break
print("matched", n)
