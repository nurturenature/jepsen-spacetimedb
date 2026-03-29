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

# can't use COPY --parents as GitHub Actions do not support --parents
WORKDIR /jepsen/jepsen-spacetimedb
RUN git clone -b main --depth 1 --single-branch https://github.com/nurturenature/jepsen-spacetimedb.git

# install SpacetimeDB
WORKDIR /jepsen/jepsen-spacetimedb

# download and install binary
RUN curl -sSf --output install-spacetimedb.sh https://install.spacetimedb.com
RUN chmod a+x install-spacetimedb.sh
RUN ./install-spacetimedb.sh --yes

# explicit version
RUN /root/.local/bin/spacetime version install 2.1.0
RUN /root/.local/bin/spacetime version use 2.1.0

# configuring should also create config ~/.config/spacetime/cli.toml
RUN /root/.local/bin/spacetime server set-default local

# deps
WORKDIR /jepsen/jepsen-spacetimedb/jepsen-spacetimedb/stdb-client/spacetimedb
RUN npm install
