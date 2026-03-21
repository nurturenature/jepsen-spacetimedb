#
# Custom SpacetimeDB node
#
ARG JEPSEN_REGISTRY

FROM ${JEPSEN_REGISTRY:-}jepsen-node

# keep Debian up to date
RUN apt-get -qy update && \
    apt-get -qy upgrade

# SpacetimeDB deps
RUN apt-get -qy update && \
    apt-get -qy install \
    extrepo git
RUN extrepo enable node_25.x
RUN apt-get -qy update && \
    apt-get -qy install \
    nodejs

# assume building in jepsen-spacetimedb repository directory

# caching layer for deps
WORKDIR /jepsen/jepsen-spacetimedb/jepsen-spacetimedb/stdb-client
COPY ./stdb-client/package*.json ./
RUN npm install
WORKDIR /jepsen/jepsen-spacetimedb
RUN rm -rf jepsen-spacetimedb

# can't use COPY --parents as GitHub actions don't support --parents
WORKDIR /jepsen/jepsen-spacetimedb
RUN git clone -b main --depth 1 --single-branch https://github.com/nurturenature/jepsen-spacetimedb.git

# deps
WORKDIR /jepsen/jepsen-spacetimedb/jepsen-spacetimedb/stdb-client
RUN npm install
