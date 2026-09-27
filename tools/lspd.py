import sqlite3, sys, json, os
d = r"C:\workspace\x-spam-blocker\lspd"
con = sqlite3.connect(os.path.join(d, "mc.db"))
con.row_factory = sqlite3.Row
cur = con.cursor()
print("=== schema ===")
for r in cur.execute("select type,name,sql from sqlite_master where type in ('table','index')"):
    print(r["type"], r["name"], "::", (r["sql"] or "")[:300])
print()
for t in [r[0] for r in cur.execute("select name from sqlite_master where type='table'")]:
    print("=== rows in", t, "===")
    try:
        rows = list(cur.execute("select * from " + t))
        print("count:", len(rows))
        for r in rows[:8]:
            print("   ", dict(r))
    except Exception as e:
        print("   err", e)
