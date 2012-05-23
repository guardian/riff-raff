#!/bin/bash

SBT_BOOT_DIR=$HOME/.sbt/boot/

if [ ! -d "$SBT_BOOT_DIR" ]; then
  mkdir -p $SBT_BOOT_DIR
fi

java -Xmx768M -XX:+UseCompressedOops -XX:MaxPermSize=384m \
	-Dhttp.proxyHost=devscreen.gudev.gnl -Dhttp.proxyPort=3128 \
	-Dsbt.boot.directory=$SBT_BOOT_DIR \
	$SBT_EXTRA_PARAMS \
	-jar `dirname $0`/sbt-launch-0.11.3.jar "$@"

