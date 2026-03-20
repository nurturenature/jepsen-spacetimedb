#
# Jepsen control + SpacetimeDB
#
ARG JEPSEN_REGISTRY

FROM ${JEPSEN_REGISTRY:-}jepsen-control

# keep Debian up to date
RUN apt-get -qy update && \
    apt-get -qy upgrade

# assume building in jepsen-spacetimedb repository directory

# caching layer for deps
WORKDIR /jepsen/jepsen-spacetimedb/jepsen-spacetimedb
COPY ./project.clj ./
RUN lein deps

# cp repository
WORKDIR /jepsen/jepsen-spacetimedb/jepsen-spacetimedb
COPY --parents ./.* ./
COPY --parents ./*  ./

# deps
WORKDIR /jepsen/jepsen-spacetimedb/jepsen-spacetimedb
RUN lein deps

# build tests
WORKDIR /jepsen/jepsen-spacetimedb/jepsen-spacetimedb
RUN lein compile
