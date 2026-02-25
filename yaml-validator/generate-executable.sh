#!/bin/bash
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$DIR/.." && pwd)"

cd "$PROJECT_ROOT"

echo "Building fat JAR..."
./sbt yamlValidator/assembly

SCALA_VERSION="scala-2.13"
JAR_PATH="yaml-validator/target/$SCALA_VERSION/validate-yaml.jar"

if [ ! -f "$JAR_PATH" ]; then
    echo "ERROR: JAR not found at $JAR_PATH"
    exit 1
fi

echo "Creating self-executing binary..."
cat "$DIR/executable-prefix" "$JAR_PATH" > "yaml-validator/target/$SCALA_VERSION/validate-yaml"
chmod +x "yaml-validator/target/$SCALA_VERSION/validate-yaml"

echo ""
echo "Executable available at: yaml-validator/target/$SCALA_VERSION/validate-yaml"
echo ""
echo "Usage: ./yaml-validator/target/$SCALA_VERSION/validate-yaml <path-to-riff-raff.yaml>"
