import json, re, collections

p = r"C:\workspace\x-spam-blocker\dumps\xsb\xsb_HomeTimeline_1.json"
raw = open(p, encoding="utf-8").read()
d = json.loads(raw)

print("### raw substring presence ###")
for k in ["user_mentions", "\"urls\"", "note_tweet", "entities", "display_text_range", "ext_media", "\"legacy\""]:
    print("  %-22s %s" % (k, raw.count(k)))

print()
print("### distinct key sets ###")
def walk(o, path=""):
    if isinstance(o, dict):
        yield path, o
        for k, v in o.items():
            yield from walk(v, path + "." + k)
    elif isinstance(o, list):
        for i, v in enumerate(o):
            yield from walk(v, path + "[%d]" % i)

res = collections.Counter()
det = collections.Counter()
for path, o in walk(d):
    if o.get("__typename") == "Tweet":
        res[tuple(sorted(o.keys()))] += 1
    if "full_text" in o:
        det[tuple(sorted(o.keys()))] += 1

for ks, n in res.most_common():
    print("  Tweet result x%d: %s" % (n, list(ks)))
print()
for ks, n in det.most_common():
    print("  details x%d: %s" % (n, list(ks)))

print()
print("### tweets containing an @ mention ###")
for path, o in walk(d):
    if "full_text" in o and re.search(r"@[A-Za-z0-9_]{2,}", o["full_text"]):
        print("  ", path.replace(".data.timeline_response.timeline.instructions", "I")[:70], "|", o["full_text"][:110].replace("\n", " / "))
