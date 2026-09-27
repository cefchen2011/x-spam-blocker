import pickle, re, sys
from collections import Counter
allcls, allm = pickle.load(open(r"C:\workspace\x-spam-blocker\tools\dexindex.pkl", "rb"))
prefix = sys.argv[1] if len(sys.argv) > 1 else "com.x.urt"
tpref = sys.argv[2] if len(sys.argv) > 2 else "androidx.compose.ui.text"
cnt = Counter()
for c, n, r, p, f in allm:
    if not c.startswith("L" + prefix.replace(".", "/")):
        continue
    for t in (r,) + p:
        if t.startswith(tpref):
            cnt[t] += 1
print("=== compose text types referenced by", prefix, "===")
for k, v in cnt.most_common(60):
    print("%6d  %s" % (v, k))
print()
print("=== classes in androidx/compose/ui/text with a (String,List,List) ctor ===")
for c in sorted(allcls):
    if not c.startswith("Landroidx/compose/ui/text/") or c.count("/") != 5:
        continue
    for cc, n, r, p, f in allm:
        if cc == c and n == "<init>" and len(p) == 3 and p[0] == "java.lang.String":
            print("   ", c, p, "->", r)
            break
