#!/bin/sh
# small helper script to build the orch api locally and use e.g. in the
# testing bundle for end to end testing
set -eu

cd "$(dirname "$0")/.."

# The testing bundle always pulls new images on startup but ignores this tag,
# so keep the tag if you wanna use it in the testing bundle
TAG="${1:-testingtag}"

./mvnw -B -ntp package -DskipTests
docker build -t "orch-api:$TAG" -f src/main/docker/Dockerfile.jvm .
