#!/bin/bash
set -e

docker exec -t \
       -w /jepsen/jepsen-spacetimedb/jepsen-spacetimedb \
       jepsen-control \
       lein run serve
 