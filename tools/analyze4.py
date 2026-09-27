import json, collections

p = r"C:\workspace\x-spam-blocker\dumps\xsb\xsb_HomeTimeline_1.json"
d = json.load(open(p, encoding="utf-8"))

def walk(o, path=""):
    if isinstance(o, dict):
        yield path, o
        for k, v in o.items():
            yield from walk(v, path + "." + k)
    elif isinstance(o, list):
        for i, v in enumerate(o):
            yield from walk(v, path + "[%d]" % i)

shown = 0
for path, o in walk(d):
    if o.get("__typename") != "Tweet":
        continue
    ue = o.get("url_entities")
    me = o.get("mention_entities")
    det = o.get("details") or {}
    text = det.get("full_text", "")
    if not ue and not me:
        continue
    if shown >= 5:
        break
    shown += 1
    print("=" * 78)
    print("full_text:", json.dumps(text, ensure_ascii=False)[:200])
    print("display_text_range:", det.get("display_text_range"))
    print("url_entities:", json.dumps(ue, ensure_ascii=False, indent=1)[:1200])
    print("mention_entities:", json.dumps(me, ensure_ascii=False, indent=1)[:900])
    print()

print("### distinct key names inside url_entities / mention_entities ###")
ku = collections.Counter(); km = collections.Counter()
for path, o in walk(d):
    if o.get("__typename") != "Tweet":
        continue
    for e in (o.get("url_entities") or []):
        if isinstance(e, dict):
            ku[tuple(sorted(e.keys()))] += 1
    for e in (o.get("mention_entities") or []):
        if isinstance(e, dict):
            km[tuple(sorted(e.keys()))] += 1
print("url_entities key sets:", ku.most_common())
print("mention_entities key sets:", km.most_common())
