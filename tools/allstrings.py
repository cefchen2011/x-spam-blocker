import sys, glob, os, struct, pickle

DEXDIR = r"C:\workspace\x-spam-blocker\apk\dex"
CACHE = r"C:\workspace\x-spam-blocker\tools\allstrings.pkl"

def uleb128(b, o):
    r = 0; s = 0
    while True:
        x = b[o]; o += 1
        r |= (x & 0x7f) << s
        if x < 0x80: break
        s += 7
    return r, o

def build():
    out = set()
    for f in sorted(glob.glob(os.path.join(DEXDIR, "*.dex"))):
        b = open(f, "rb").read()
        (ssize, soff) = struct.unpack_from("<2I", b, 56)
        for i in range(ssize):
            off = struct.unpack_from("<I", b, soff + 4*i)[0]
            n, o = uleb128(b, off)
            out.add(b[o:o+n].decode("utf-8", "replace"))
    pickle.dump(out, open(CACHE, "wb"))
    return out

if not os.path.exists(CACHE):
    build()
S = pickle.load(open(CACHE, "rb"))
pats = sys.argv[1:]
seen = 0
for s in sorted(S):
    for p in pats:
        if p in s:
            if len(s) < 400:
                print(s)
            seen += 1
            break
print("### matched:", seen, "of", len(S), file=sys.stderr)
