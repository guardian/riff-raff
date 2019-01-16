#!/usr/bin/env bash
set -x
set -e

function HELP {
>&2 cat << EOF

  Usage: ${0} -s stage

  This script installs riff-raff, downloads config and setup service

    -s stage      The stage of the instance [PROD|CODE]
    -h            Displays this help message. No further functions are
                  performed.

EOF
exit 1
}

while getopts ":s:" OPT; do
  case $OPT in
    s)
      STAGE="$OPTARG"
      ;;
    h)
      HELP
      ;;
    \?)
      echo "Invalid option -$OPTARG" >&2
      HELP
      ;;
  esac
done

if [ "${STAGE}" != "CODE" ] && [ "${STAGE}" != "PROD"  ]; then
  echo "Must specify a valid stage (PROD or CODE)"
  exit 1
fi

REGION="eu-west-1"
STACK="deploy"
APP="riff-raff"
CONFIG_BUCKET="deploy-tools-configuration"
APP_BUCKET="deploy-tools-dist"
USER=$APP
HOME="/home/$APP"

# Install a new copy of the properties file
id -u ${USER} &>/dev/null || adduser --system --home ${HOME} --disabled-password ${USER}
CONFIG_ORIGIN="s3://$CONFIG_BUCKET/$STACK/$APP/$STAGE.conf"
mkdir -p ${HOME}/.gu
CONFIG_DESTINATION=${HOME}/.gu/riff-raff.conf
aws s3 cp ${CONFIG_ORIGIN} ${CONFIG_DESTINATION} --region ${REGION}

# Install the application that was packaged by the sbt-native-packager
aws --region ${REGION} s3 cp s3://${APP_BUCKET}/${STACK}/${STAGE}/${APP}/${APP}.tgz /tmp/
tar -C ${HOME} -xzf /tmp/${APP}.tgz

chown -R ${USER} ${HOME}

# try to clean up
rm /tmp/${APP}.tgz || true

# Service
cat > /etc/systemd/system/${APP}.service << EOF
[Unit]
Description=${APP}

[Service]
WorkingDirectory=${HOME}
ExecStart=${HOME}/${APP}/bin/${APP}
ExecReload=/bin/kill -HUP $MAINPID
Restart=on-failure
SuccessExitStatus=143
User=${USER}
ExecStartPre=/opt/${APP}/bootstrap.sh -s ${STAGE}
PermissionsStartOnly=true
RestartForceExitStatus=217

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload

STATUS=$(systemctl is-active ${APP} || true)
if [ "$STATUS" = "inactive" ]; then
  systemctl start ${APP}
fi
