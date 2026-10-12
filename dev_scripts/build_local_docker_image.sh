#!/usr/bin/env bash
# Small helper script to build the orch-api image locally to use in testing.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"

cd "$REPO_ROOT"

TAG=testingtag

# Build Quarkus application
bash mvnw -B -ntp package -DskipTests

docker build -t "orch-api:$TAG" -f src/main/docker/Dockerfile.jvm .
