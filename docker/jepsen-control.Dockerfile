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
WORKDIR /jepsen/jepsen-spacetimedb
RUN rm -rf jepsen-spacetimedb

# can't use COPY --parents as GitHub actions don't support --parents
WORKDIR /jepsen/jepsen-spacetimedb
RUN git clone -b main --depth 1 --single-branch https://github.com/nurturenature/jepsen-spacetimedb.git

# deps
WORKDIR /jepsen/jepsen-spacetimedb/jepsen-spacetimedb
RUN lein deps

# build tests
WORKDIR /jepsen/jepsen-spacetimedb/jepsen-spacetimedb
RUN lein compile
