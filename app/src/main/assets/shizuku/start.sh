#!/system/bin/sh

SOURCE_PATH="/data/local/tmp/shizuku_starter"
STARTER_PATH="/data/local/tmp/shizuku_starter"

echo "info: start.sh begin"

recreate_tmp() {
  echo "info: /data/local/tmp is possible broken, recreating..."
  rm -rf /data/local/tmp
  mkdir -p /data/local/tmp
}

broken_tmp() {
  echo "fatal: /data/local/tmp is broken, please try reboot the device or manually recreate it..."
  exit 1
}

if [ -f "$SOURCE_PATH" ]; then
    chmod 700 $STARTER_PATH
    chown 2000 $STARTER_PATH
    chgrp 2000 $STARTER_PATH
fi

if [ -f $STARTER_PATH ]; then
  echo "info: exec $STARTER_PATH"
    $STARTER_PATH "$1"
    result=$?
    if [ ${result} -ne 0 ]; then
        echo "info: shizuku_starter exit with non-zero value $result"
    else
        echo "info: shizuku_starter exit with 0"
    fi
else
    echo "Starter file not exist, please open Shizuku and try again."
fi
