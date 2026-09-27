#!/system/bin/sh
set -e
D=/data/adb/lspd/config
echo "--- stopping lspd ---"
for p in $(pidof lspd); do kill -9 $p; echo "killed $p"; done
sleep 1
cp /data/local/tmp/mc_new.db $D/modules_config.db
rm -f $D/modules_config.db-wal $D/modules_config.db-shm
chown root:root $D/modules_config.db
chmod 600 $D/modules_config.db
ls -la $D/modules_config.db*
echo "--- applied ---"
