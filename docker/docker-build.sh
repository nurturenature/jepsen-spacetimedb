#!/bin/bash
set -e

# build control, node and setup images

docker build \
       -t jepsen-control \
       --build-arg JEPSEN_REGISTRY="ghcr.io/nurturenature/jepsen-docker/" \
       -f jepsen-control.Dockerfile \
       ..

docker build \
       -t jepsen-node \
       --build-arg JEPSEN_REGISTRY="ghcr.io/nurturenature/jepsen-docker/" \
       -f jepsen-node.Dockerfile \
       ..

docker build \
       -t jepsen-spacetimedb \
       --build-arg JEPSEN_REGISTRY="ghcr.io/nurturenature/jepsen-docker/" \
       -f jepsen-spacetimedb.Dockerfile \
       ..

docker build \
       -t jepsen-setup \
       --build-arg JEPSEN_REGISTRY="ghcr.io/nurturenature/jepsen-docker/" \
       -f jepsen-setup.Dockerfile \
       ..

echo
echo "pruning docker images..."
docker image prune --force > /dev/null

echo
echo "Jepsen control, node, spacetimedb, and setup images have been built."
echo "Bring up a Jepsen + SpacetimeDB cluster with ./docker-compose-up.sh"
