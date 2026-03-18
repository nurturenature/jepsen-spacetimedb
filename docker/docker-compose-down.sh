#!/bin/bash
set -e

# use docker-build.sh built local images
# export JEPSEN_REGISTRY="ghcr.io/nurturenature/jepsen-docker/"

docker compose \
       -f jepsen-compose.yaml \
       down \
       -v
