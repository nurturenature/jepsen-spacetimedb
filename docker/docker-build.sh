#!/bin/bash
set -e

# build control, node, spacetimedb, and setup images

JEPSEN_REGISTRY=${JEPSEN_REGISTRY:-}

echo
echo "Building images with JEPSEN_REGISTRY='${JEPSEN_REGISTRY}'"
echo

docker build \
       -t jepsen-control \
       --build-arg JEPSEN_REGISTRY="${JEPSEN_REGISTRY}" \
       -f jepsen-control.Dockerfile \
       --no-cache-filter body \
       ..

docker build \
       -t jepsen-node \
       --build-arg JEPSEN_REGISTRY="${JEPSEN_REGISTRY}" \
       -f jepsen-node.Dockerfile \
       --no-cache-filter body \
       ..

docker build \
       -t jepsen-spacetimedb \
       --build-arg JEPSEN_REGISTRY="${JEPSEN_REGISTRY}" \
       --build-arg STDB_VERSION="2.11.0" \
       -f jepsen-spacetimedb.Dockerfile \
       --no-cache-filter body \
       ..

docker build \
       -t jepsen-setup \
       --build-arg JEPSEN_REGISTRY="${JEPSEN_REGISTRY}" \
       -f jepsen-setup.Dockerfile \
       --no-cache-filter body \
       ..

echo
echo "pruning docker images..."
docker image prune --force > /dev/null

echo
echo "Jepsen control, node, spacetimedb, and setup images have been built."
echo "Bring up a Jepsen + SpacetimeDB cluster with ./docker-compose-up.sh"
