#!/bin/bash
# Magenta Deploy script
# This should download the latest megenta version to a tmp dir and execute it
# It takes no parameters, but if you set MAGENTA_VERSION before running, it will download a specified version

# You need to set the URL that you can download artifacts from, i.e. TeamCity, some apache server, nfs etc
CMD=wget -q
SRC=http://teamcity.mydomain.com:8111/httpAuth/repository/download/tools:deploy/${MAGENTA_VERSION:=.lastSuccessful}/magenta.jar

DIR=$(mktemp -dt magenta.XXXXX)
cd $DIR
echo "Getting magenta version ${MAGENTA_VERSION:=.lastSuccessful}"
$CMD $SRC
if [ $? -eq 0 ]; then
    java -jar magenta.jar $*
else
    echo "Failed to download Magenta from $SRC"
fi