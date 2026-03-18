#
# Jepsen control + SpacetimeDB
#
ARG JEPSEN_REGISTRY

FROM ${JEPSEN_REGISTRY:-}jepsen-control

# keep Debian up to date
RUN apt-get -qy update && \
    apt-get -qy upgrade

# SpacetimeDB test repository
WORKDIR /jepsen/jepsen-spacetimedb
RUN git clone -b main --depth 1 --single-branch https://github.com/nurturenature/jepsen-spacetimedb.git

# caching layer for deps
WORKDIR /jepsen/jepsen-spacetimedb/jepsen-spacetimedb
RUN lein deps

# build tests
WORKDIR /jepsen/jepsen-spacetimedb/jepsen-spacetimedb
RUN lein compile
