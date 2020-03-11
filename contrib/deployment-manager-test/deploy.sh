#!/bin/bash
set -e

BUCKET=${1}
if [ -z "${BUCKET}" ]; then
  echo "Usage: $0 <bucket-name>"
  exit 1
fi
S3LOC=s3://${BUCKET}/gcp-test/1

aws s3 cp riff-raff.yaml \
  ${S3LOC}/riff-raff.yaml \
  --profile deployTools
aws s3 cp --recursive test \
  ${S3LOC}/test \
  --profile deployTools