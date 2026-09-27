#!/system/bin/sh
set -e
D=/data/adb/lspd/config
B=/data/local/tmp/lspd_backup
rm -rf $B
mkdir -p $B
cp $D/modules_config.db $B/
[ -f $D/modules_config.db-wal ] && cp $D/modules_config.db-wal $B/ || true
[ -f $D/modules_config.db-shm ] && cp $D/modules_config.db-shm $B/ || true
chmod -R 666 $B
ls -la $B
echo "--- current config dir ---"
ls -la $D
