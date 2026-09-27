import pickle, struct, glob, os, sys, re

DEXDIR = r"C:\workspace\x-spam-blocker\apk\dex"
CACHE = r"C:\workspace\x-spam-blocker\tools\hierarchy.pkl"

def uleb128(b, o):
    r = 0; s = 0
    while True:
        x = b[o]; o += 1
        r |= (x & 0x7f) << s
        if x < 0x80: break
        s += 7
    return r, o

def build():
    hier = {}
    for f in sorted(glob.glob(os.path.join(DEXDIR, "*.dex"))):
        b = open(f, "rb").read()
        (ssize, soff, tsize, toff, psize, poff, fsize, foff,
         msize, moff, csize, coff) = struct.unpack_from("<12I", b, 56)
        strings = []
        for i in range(ssize):
            off = struct.unpack_from("<I", b, soff + 4*i)[0]
            n, o = uleb128(b, off)
            strings.append(b[o:o+n].decode("utf-8", "replace"))
        types = [strings[struct.unpack_from("<I", b, toff + 4*i)[0]] for i in range(tsize)]
        for ci in range(csize):
            base = coff + 32*ci
            cidx = struct.unpack_from("<I", b, base)[0]
            sidx = struct.unpack_from("<I", b, base + 8)[0]
            src = struct.unpack_from("<I", b, base + 16)[0]
            hier[types[cidx]] = (types[sidx] if sidx != 0xffffffff else None,
                                 strings[src] if src != 0xffffffff else None)
    pickle.dump(hier, open(CACHE, "wb"))
    return hier

if not os.path.exists(CACHE):
    build()
hier = pickle.load(open(CACHE, "rb"))

def ancestors(c, depth=12):
    out = []
    cur = c
    for _ in range(depth):
        v = hier.get(cur)
        if not v or not v[0]: break
        cur = v[0]
        out.append(cur)
    return out

mode = sys.argv[1]; pat = sys.argv[2] if len(sys.argv) > 2 else ""
rx = re.compile(pat, re.I)

if mode == "subclasses":
    want = sys.argv[2]
    print("classes whose ancestry includes", want)
    n = 0
    for c in sorted(hier):
        a = ancestors(c)
        if want in a:
            n += 1
            if n <= 60: print("   ", c, " src=", hier[c][1])
    print("total", n)
elif mode == "info":
    for c in sorted(hier):
        if rx.search(c):
            print(c, "extends", hier[c][0], "src", hier[c][1])
elif mode == "top":
    from collections import Counter
    cnt = Counter()
    for c in hier:
        if re.search(pat, c):
            v = hier[c]
            cnt[v[0]] += 1
    for k, v in cnt.most_common(30):
        print(v, k)
