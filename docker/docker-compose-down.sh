#!/bin/bash
set -e

# use docker-build.sh built local images
# or GitHub Container Registry images 
#   - export JEPSEN_REGISTRY="ghcr.io/nurturenature/jepsen-spacetimedb/"

docker compose \
       -f jepsen-compose.yaml \
       down \
       -v
