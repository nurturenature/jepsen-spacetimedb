#
# Custom SpacetimeDB node
#
ARG JEPSEN_REGISTRY

FROM ${JEPSEN_REGISTRY:-}jepsen-node AS preamble

ARG STDB_VERSION

# keep Debian up to date
RUN apt-get -qy update && \
    apt-get -qy upgrade

# SpacetimeDB deps
RUN apt-get -qy update && \
    apt-get -qy install \
    curl extrepo git
RUN extrepo enable node_25.x
RUN apt-get -qy update && \
    apt-get -qy install \
    nodejs

# assume building in jepsen-spacetimedb repository directory

# caching layer for deps
WORKDIR /jepsen/jepsen-spacetimedb/jepsen-spacetimedb/stdb-client/spacetimedb
COPY ./stdb-client/spacetimedb/package*.json ./
RUN npm install
WORKDIR /jepsen/jepsen-spacetimedb
RUN rm -rf jepsen-spacetimedb

# cache invalidation on change in STDB_VERSION
RUN echo installing SpacetimeDB ${STDB_VERSION}...

# install SpacetimeDB
WORKDIR /jepsen/jepsen-spacetimedb

# download and install binary
RUN curl -sSf --output install-spacetimedb.sh https://install.spacetimedb.com
RUN chmod a+x install-spacetimedb.sh
RUN ./install-spacetimedb.sh --yes

# explicit version
RUN /root/.local/bin/spacetime version use ${STDB_VERSION}
RUN /root/.local/bin/spacetime version list

# configuring should also create config ~/.config/spacetime/cli.toml
RUN /root/.local/bin/spacetime server set-default local

FROM preamble AS body

# install repo
WORKDIR /jepsen/jepsen-spacetimedb
RUN git clone -b main --depth 1 --single-branch https://github.com/nurturenature/jepsen-spacetimedb.git

# deps
WORKDIR /jepsen/jepsen-spacetimedb/jepsen-spacetimedb/stdb-client/spacetimedb
RUN npm install
