import struct, sys, glob, os, re, pickle

DEXDIR = r"C:\workspace\x-spam-blocker\apk\dex"
CACHE = r"C:\workspace\x-spam-blocker\tools\dexindex.pkl"

def uleb128(b, o):
    r = 0; s = 0
    while True:
        x = b[o]; o += 1
        r |= (x & 0x7f) << s
        if x < 0x80: break
        s += 7
    return r, o

def parse(path):
    b = open(path, "rb").read()
    assert b[:4] == b"dex\n"
    (string_ids_size, string_ids_off, type_ids_size, type_ids_off,
     proto_ids_size, proto_ids_off, field_ids_size, field_ids_off,
     method_ids_size, method_ids_off, class_defs_size, class_defs_off) = struct.unpack_from("<12I", b, 56)

    strings = []
    for i in range(string_ids_size):
        off = struct.unpack_from("<I", b, string_ids_off + 4*i)[0]
        n, o = uleb128(b, off)
        strings.append(b[o:o+n].decode("utf-8", "replace"))

    types = []
    for i in range(type_ids_size):
        idx = struct.unpack_from("<I", b, type_ids_off + 4*i)[0]
        types.append(strings[idx])

    protos = []
    for i in range(proto_ids_size):
        shorty, ret, params_off = struct.unpack_from("<III", b, proto_ids_off + 12*i)
        params = []
        if params_off:
            sz = struct.unpack_from("<I", b, params_off)[0]
            for k in range(sz):
                ti = struct.unpack_from("<H", b, params_off + 4 + 2*k)[0]
                params.append(types[ti])
        protos.append((types[ret], params))

    methods = []
    for i in range(method_ids_size):
        cidx, pidx, nidx = struct.unpack_from("<HHI", b, method_ids_off + 8*i)
        ret, params = protos[pidx]
        methods.append((types[cidx], strings[nidx], ret, params))

    classes = []
    for i in range(class_defs_size):
        cidx = struct.unpack_from("<I", b, class_defs_off + 32*i)[0]
        src = struct.unpack_from("<I", b, class_defs_off + 32*i + 16)[0]
        classes.append((types[cidx], strings[src] if src != 0xffffffff else None))
    return classes, methods

def short(t):
    if t.startswith("L") and t.endswith(";"): return t[1:-1].replace("/", ".")
    if t.startswith("["): return short(t[1:]) + "[]"
    return t

def build():
    allcls = {}; allm = []
    for f in sorted(glob.glob(os.path.join(DEXDIR, "*.dex"))):
        cls, m = parse(f)
        for c, s in cls: allcls[c] = s
        for c, n, r, p in m:
            allm.append((c, n, short(r), tuple(short(x) for x in p), os.path.basename(f)))
        print("parsed", os.path.basename(f), len(m), file=sys.stderr)
    pickle.dump((allcls, allm), open(CACHE, "wb"))
    print("classes", len(allcls), "methods", len(allm), file=sys.stderr)

if __name__ == "__main__":
    if not os.path.exists(CACHE) or "--rebuild" in sys.argv:
        build()
    allcls, allm = pickle.load(open(CACHE, "rb"))
    pats = [p for p in sys.argv[1:] if not p.startswith("--")]
    if not pats:
        print("classes:", len(allcls), "methods:", len(allm)); sys.exit()
    cre = re.compile("|".join(pats), re.I)
    print("===== matching classes =====")
    for c, s in sorted(allcls.items()):
        if cre.search(c):
            print("  ", c, "  src=", s)
    print("===== matching methods (grouped) =====")
    byclass = {}
    for c, n, r, p, f in allm:
        if cre.search(c) or cre.search(n):
            byclass.setdefault(c, []).append((n, r, p, f))
    for c in sorted(byclass):
        print("--", c)
        for n, r, p, f in sorted(set(byclass[c]))[:60]:
            print("     ", n + "(" + ", ".join(p) + ") : " + r)
