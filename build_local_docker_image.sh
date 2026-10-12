#!/usr/bin/env bash
# Small helper script to build the orch-api image locally to use in testing.
set -euo pipefail
cd "$(dirname "$0")"

TAG=tmptag

# Build Quarkus application
bash mvnw -B -ntp package -DskipTests

docker build -t "orch-api:$TAG" -f src/main/docker/Dockerfile.jvm .
