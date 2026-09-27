import sqlite3, sys, os
db = sys.argv[1] if len(sys.argv) > 1 else r"C:\workspace\x-spam-blocker\lspd\mc.db"
con = sqlite3.connect(db)
cur = con.cursor()
q = "select * from modules where module_pkg_name=?"
print("modules row:", list(cur.execute(q, ("com.dsh.xspamblock",))))
print("state row  :", list(cur.execute("select * from modules_state where module_pkg_name=?", ("com.dsh.xspamblock",))))
print("scope rows :", list(cur.execute("select * from scope where module_pkg_name=?", ("com.dsh.xspamblock",))))
print()
print("all modules:", [r[0] for r in cur.execute("select module_pkg_name from modules")])
