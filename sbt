#!/bin/bash

SBT_BOOT_DIR=$HOME/.sbt/boot/

if [ ! -d "$SBT_BOOT_DIR" ]; then
  mkdir -p $SBT_BOOT_DIR
fi

java -Xmx768M -XX:+UseCompressedOops -XX:MaxPermSize=384m \
	-Dsbt.boot.directory=$SBT_BOOT_DIR \
	$SBT_EXTRA_PARAMS \
	-jar `dirname $0`/sbt-launch.jar "$@"

