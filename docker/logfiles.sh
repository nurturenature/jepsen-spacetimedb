#!/bin/bash
set -e

TARGET_DIR=../store/current
mkdir -p $TARGET_DIR

docker logs spacetimedb &> $TARGET_DIR/spacetimedb.log || \
  echo "no docker logs" > $TARGET_DIR/spacetimedb.log

docker cp jepsen-control:/jepsen/jepsen-spacetimedb/jepsen-spacetimedb/store/current/. $TARGET_DIR || \
  echo "no docker logs for jepsen-control" > $TARGET_DIR/jepsen-control.log
