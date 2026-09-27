"""Enable com.dsh.xspamblock in LSPosed and grant it com.twitter.android scope.

Works on a copy: the source db (plus its -wal) is checkpointed first so no other
module's recent configuration is lost.
"""
import sqlite3, os, shutil, sys

SRC = r"C:\workspace\x-spam-blocker\lspd\mc.db"
WORK = r"C:\workspace\x-spam-blocker\lspd\work.db"
MODULE = "com.dsh.xspamblock"
TARGET = "com.twitter.android"

for suffix in ("", "-wal", "-shm"):
    p = WORK + suffix
    if os.path.exists(p):
        os.remove(p)
shutil.copyfile(SRC, WORK)
for suffix in ("-wal", "-shm"):
    p = SRC + suffix
    if os.path.exists(p):
        shutil.copyfile(p, WORK + suffix)
        print("copied", p)

con = sqlite3.connect(WORK)
cur = con.cursor()
cur.execute("PRAGMA wal_checkpoint(TRUNCATE)")
print("checkpoint:", cur.fetchone())

before_state = list(cur.execute("select * from modules_state where module_pkg_name=?", (MODULE,)))
before_scope = list(cur.execute("select * from scope where module_pkg_name=?", (MODULE,)))
print("before state:", before_state)
print("before scope:", before_scope)

row = list(cur.execute("select apk_path from modules where module_pkg_name=?", (MODULE,)))
if not row:
    print("FATAL: module not registered in modules table")
    sys.exit(1)
print("apk_path:", row[0][0])

cur.execute(
    "INSERT OR REPLACE INTO modules_state(module_pkg_name,user_id,enabled,scope_request_blocked) VALUES(?,?,?,?)",
    (MODULE, 0, 1, 0))
cur.execute(
    "INSERT OR REPLACE INTO scope(module_pkg_name,app_pkg_name,user_id) VALUES(?,?,?)",
    (MODULE, TARGET, 0))
con.commit()
cur.execute("PRAGMA wal_checkpoint(TRUNCATE)")

print("after state:", list(cur.execute("select * from modules_state where module_pkg_name=?", (MODULE,))))
print("after scope:", list(cur.execute("select * from scope where module_pkg_name=?", (MODULE,))))
print("total modules:", cur.execute("select count(*) from modules").fetchone()[0])
print("total scope  :", cur.execute("select count(*) from scope").fetchone()[0])
con.close()
print("work db ready:", WORK, os.path.getsize(WORK), "bytes")
