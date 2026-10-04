#!/bin/bash
set -e

JEPSEN_REGISTRY=${JEPSEN_REGISTRY:-}

echo
echo "Docker compose up with JEPSEN_REGISTRY=${JEPSEN_REGISTRY}"
echo

docker compose \
       -f jepsen-compose.yaml \
       up \
       --detach \
       --wait

docker ps --format="table {{.Names}}\t{{.Image}}\t{{.Status}}"

echo
echo "A full Jepsen control + SpacetimeDB server and client nodes cluster is up and available."
echo "Run a Jepsen test with ./docker-run.sh lein run test."
