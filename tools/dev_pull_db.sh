#!/system/bin/sh
cp /data/adb/lspd/config/modules_config.db /data/local/tmp/v.db
if [ -f /data/adb/lspd/config/modules_config.db-wal ]; then cp /data/adb/lspd/config/modules_config.db-wal /data/local/tmp/v.db-wal; fi
if [ -f /data/adb/lspd/config/modules_config.db-shm ]; then cp /data/adb/lspd/config/modules_config.db-shm /data/local/tmp/v.db-shm; fi
chmod 666 /data/local/tmp/v.db
chmod 666 /data/local/tmp/v.db-wal 2>/dev/null
chmod 666 /data/local/tmp/v.db-shm 2>/dev/null
ls -la /data/local/tmp/v.db*
