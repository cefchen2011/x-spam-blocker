import os, re, sys, glob

DEX = r"C:\workspace\x-spam-blocker\apk\dex"

pats = [p.encode() for p in [
    "mute_keyword", "muted_keyword", "MuteKeyword", "muteKeyword", "muted_keyword",
    "com/twitter/model/core/Tweet", "BasicTextKt", "androidx/compose/foundation/text",
    "TweetText", "StatusText", "mute_keywords",
    "MutedKeyword", "addMutedKeyword", "muteKeyword",
]]

files = sorted(glob.glob(os.path.join(DEX, "*.dex")))
counts = {p: 0 for p in pats}
hits = {p: [] for p in pats}

for f in files:
    data = open(f, "rb").read()
    for p in pats:
        start = 0
        while True:
            i = data.find(p, start)
            if i < 0:
                break
            counts[p] += 1
            if len(hits[p]) < 6:
                # extract surrounding printable ascii
                lo = max(0, i - 60); hi = min(len(data), i + 90)
                ctx = data[lo:hi]
                ctx = bytes(c if 32 <= c < 127 else 46 for c in ctx).decode("ascii", "replace")
                hits[p].append((os.path.basename(f), i, ctx))
            start = i + 1

for p in pats:
    print("==== " + p.decode() + "  count=" + str(counts[p]))
    for (fn, off, ctx) in hits[p]:
        print("   [" + fn + "@" + str(off) + "] " + ctx)
