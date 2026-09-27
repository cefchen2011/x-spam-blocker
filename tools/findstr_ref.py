"""Locate which dex class/method references given constant strings.

Scans every 16-bit code unit of each method's code_item for the const-string (0x1a)
and const-string/jumbo (0x1b) encodings.  This is a raw scan (not a full instruction
decoder) so it can over-report, but it never desyncs and therefore never misses a real
reference.
"""
import struct, sys, glob, os

DEXDIR = r"C:\workspace\x-spam-blocker\apk\dex"

def uleb128(b, o):
    r = 0; s = 0
    while True:
        x = b[o]; o += 1
        r |= (x & 0x7f) << s
        if x < 0x80: break
        s += 7
    return r, o

def load(b):
    (ssize, soff, tsize, toff, psize, poff, fsize, foff,
     msize, moff, csize, coff) = struct.unpack_from("<12I", b, 56)
    strings = []
    for i in range(ssize):
        off = struct.unpack_from("<I", b, soff + 4*i)[0]
        n, o = uleb128(b, off)
        strings.append(b[o:o+n].decode("utf-8", "replace"))
    types = [strings[struct.unpack_from("<I", b, toff + 4*i)[0]] for i in range(tsize)]
    methods = []
    for i in range(msize):
        cidx, pidx, nidx = struct.unpack_from("<HHI", b, moff + 8*i)
        methods.append((types[cidx], strings[nidx]))
    return strings, types, methods, (csize, coff)

def scan(path):
    b = open(path, "rb").read()
    strings, types, methods, (csize, coff) = load(b)
    for ci in range(csize):
        base = coff + 32*ci
        cidx = struct.unpack_from("<I", b, base)[0]
        cdata = struct.unpack_from("<I", b, base + 24)[0]
        desc = types[cidx]
        if cdata == 0:
            continue
        o = cdata
        sf, o = uleb128(b, o); inf, o = uleb128(b, o)
        dm, o = uleb128(b, o); vm, o = uleb128(b, o)
        # encoded_field: field_idx_diff, access_flags (2 x uleb128), for static then instance
        for _ in range(sf + inf):
            _a, o = uleb128(b, o); _a, o = uleb128(b, o)
        for _kind, count in (("d", dm), ("v", vm)):
            for _ in range(count):
                midx, o = uleb128(b, o)
                _acc, o = uleb128(b, o)
                coff2, o = uleb128(b, o)
                if coff2 == 0:
                    continue
                insns_size = struct.unpack_from("<I", b, coff2 + 12)[0]
                start = coff2 + 16
                units = struct.unpack_from("<%dH" % insns_size, b, start)
                used = set()
                for i in range(insns_size - 1):
                    u = units[i]
                    if (u & 0xff) == 0x1a:
                        used.add(units[i+1])
                    elif (u & 0xff) == 0x1b and i + 2 < insns_size:
                        used.add(units[i+1] | (units[i+2] << 16))
                if used:
                    yield desc, methods[midx][1], used, strings

def main():
    pats = sys.argv[1:]
    hits = {}
    for f in sorted(glob.glob(os.path.join(DEXDIR, "*.dex"))):
        for desc, mname, used, strings in scan(f):
            for i in used:
                if i >= len(strings):
                    continue
                s = strings[i]
                for p in pats:
                    if p in s:
                        hits.setdefault(desc, {}).setdefault(mname, set()).add(s)
    for d in sorted(hits):
        print(d)
        for m in sorted(hits[d]):
            print("    " + m)
            for s in sorted(hits[d][m])[:5]:
                print("         | " + s[:140])

if __name__ == "__main__":
    main()
