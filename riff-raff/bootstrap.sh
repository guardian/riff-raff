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

if [ "${STAGE}" != "CODE" ] && [ "${STAGE}" != "PROD" ]; then
  echo "Must specify a valid stage (PROD or CODE)"
  exit 1
fi

function updateScript {
	SCRIPT_LOCATION="/opt/riff-raff"

	aws --region eu-west-1 s3 cp s3://deploy-tools-dist/deploy/${STAGE}/riff-raff/bootstrap.sh /tmp/new-bootstrap.sh

	if cmp ${SCRIPT_LOCATION}/bootstrap.sh /tmp/new-bootstrap.sh > /dev/null; then
		echo "Bootstrap.sh has latest version."
	else
		echo "Bootstrap.sh has changed. Replacing with latest version."
		install /tmp/new-bootstrap.sh ${SCRIPT_LOCATION}/bootstrap.sh
		exec ./bootstrap.sh -s ${STAGE}
	fi
}

updateScript

REGION="eu-west-1"
STACK="deploy"
APP="riff-raff"
CONFIG_BUCKET="deploy-tools-configuration"
APP_BUCKET="deploy-tools-dist"
USER=$APP
HOME="/home/$APP"

INSTANCE_ROTATION_SCHEDULE="WED *-*-* 08:00:00 Europe/London"
if [ "$STAGE" == "PROD" ]; then
  INSTANCE_ROTATION_SCHEDULE="THU *-*-* 08:00:00 Europe/London"
fi

# Install a new copy of the properties file
id -u ${USER} &>/dev/null || adduser --system --home ${HOME} --disabled-password ${USER}
CONFIG_ORIGIN="s3://$CONFIG_BUCKET/$STACK/$APP/$STAGE.conf"
mkdir -p ${HOME}/.gu
CONFIG_DESTINATION=${HOME}/.gu/riff-raff.conf
aws s3 cp ${CONFIG_ORIGIN} ${CONFIG_DESTINATION} --region ${REGION}

# This script is designed to run multiple times on the same instance, so we need to delete all traces of the previous
# build artifact before we extract the new one.
# If we don't do this then config files from the previous build - such as evolutions - can remain on disk despite
# deploying a new build.
rm -rf ${HOME}/${APP}
# Install the application that was packaged by the sbt-native-packager
aws --region ${REGION} s3 cp s3://${APP_BUCKET}/${STACK}/${STAGE}/${APP}/${APP}.tgz /tmp/
tar -C ${HOME} -xzf /tmp/${APP}.tgz

chown -R ${USER} ${HOME}

# try to clean up
rm /tmp/${APP}.tgz || true

# create GC log directory (Java process fails to start without this pre-existing)
mkdir -p /var/log/${USER}
chown ${USER} /var/log/${USER}

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

cat > /etc/systemd/system/${APP}-instance-rotation.service << EOF
[Unit]
Description=${APP} instance rotation

[Service]
Type=oneshot
ExecStart=/usr/bin/curl --silent -X POST http://localhost:9000/requestInstanceRotation

[Install]
WantedBy=multi-user.target
EOF

cat > /etc/systemd/system/${APP}-instance-rotation.timer << EOF
[Unit]
Description=${APP} instance rotation schedule
Requires=${APP}-instance-rotation.service

[Timer]
Unit=${APP}-instance-rotation.service
OnCalendar=${INSTANCE_ROTATION_SCHEDULE}

[Install]
WantedBy=timers.target
EOF

systemctl daemon-reload

STATUS=$(systemctl is-active ${APP} || true)
if [ "$STATUS" = "inactive" ]; then
  systemctl start ${APP}
fi

INSTANCE_ROTATION_STATUS=$(systemctl is-active ${APP}-instance-rotation.timer || true)
if [ "$INSTANCE_ROTATION_STATUS" = "inactive" ]; then
  systemctl enable ${APP}-instance-rotation.timer
  systemctl start ${APP}-instance-rotation.timer
fi
