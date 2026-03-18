#!/bin/bash

# run args as a command in the Jepsen control Docker container

docker exec \
       -t \
       -w /jepsen/jepsen-spacetimedb/jepsen-spacetimedb \
       jepsen-control \
       bash -c "source /root/.bashrc && cd /jepsen/jepsen-spacetimedb/jepsen-spacetimedb && $*"

jepsen_exit=$?

echo
echo "The test is complete"
echo "Run the webserver to view test results, ./jepsen-web.sh"

exit ${jepsen_exit}
