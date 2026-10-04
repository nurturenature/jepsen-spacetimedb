#!/bin/bash
set -e

# pull SpacetimeDB control, node, spacetimedb, and setup images

STDB_REGISTRY=${STDB_REGISTRY:-}

echo
echo "Pulling images with STDB_REGISTRY='${STDB_REGISTRY:-}'"
echo

docker pull --quiet "${STDB_REGISTRY}"jepsen-control
docker pull --quiet "${STDB_REGISTRY}"jepsen-node
docker pull --quiet "${STDB_REGISTRY}"jepsen-spacetimedb
docker pull --quiet "${STDB_REGISTRY}"jepsen-setup

echo
echo "Jepsen control, node, spacetimedb, and setup images have been pulled."
