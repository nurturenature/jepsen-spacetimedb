#
# Jepsen control + SpacetimeDB
#
ARG JEPSEN_REGISTRY

FROM ${JEPSEN_REGISTRY:-}jepsen-control AS preamble

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

FROM preamble AS body

# install repo
WORKDIR /jepsen/jepsen-spacetimedb
RUN git clone -b main --depth 1 --single-branch https://github.com/nurturenature/jepsen-spacetimedb.git

# deps
WORKDIR /jepsen/jepsen-spacetimedb/jepsen-spacetimedb
RUN lein deps

# build tests
WORKDIR /jepsen/jepsen-spacetimedb/jepsen-spacetimedb
RUN lein compile
