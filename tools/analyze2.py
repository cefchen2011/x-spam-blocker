import json, sys, re

p = r"C:\workspace\x-spam-blocker\dumps\xsb\xsb_HomeTimeline_1.json"
d = json.load(open(p, encoding="utf-8"))

def walk(o, path=""):
    if isinstance(o, dict):
        if "full_text" in o:
            yield path, o
        for k, v in o.items():
            yield from walk(v, path + "." + k)
    elif isinstance(o, list):
        for i, v in enumerate(o):
            yield from walk(v, path + "[%d]" % i)

found = list(walk(d))

print("### all full_text samples ###")
for path, obj in found:
    t = obj.get("full_text", "").replace("\n", " / ")
    print("  %-90s | %s" % (path.replace(".data.timeline_response.timeline.instructions", "I").replace(".content.content.tweet_results.result", "R")[:88], t[:100]))

# Look at the widest raw structure around a tweet_results node.
print()
print("### keys of one tweet_results.result ###")
def find_results(o, path=""):
    if isinstance(o, dict):
        if "tweet_results" in o:
            yield path + ".tweet_results", o["tweet_results"]
        for k, v in o.items():
            yield from find_results(v, path + "." + k)
    elif isinstance(o, list):
        for i, v in enumerate(o):
            yield from find_results(v, path + "[%d]" % i)

rs = list(find_results(d))
print("tweet_results nodes:", len(rs))
if rs:
    path, node = rs[3]
    print("path:", path)
    print(json.dumps(node, ensure_ascii=False, indent=1)[:3500])
