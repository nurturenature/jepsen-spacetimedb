#
# Jepsen setup image
#
ARG JEPSEN_REGISTRY

FROM ${JEPSEN_REGISTRY:-}jepsen-setup AS preamble

# keep Debian up to date
RUN apt-get -qy update && \
    apt-get -qy upgrade

FROM preamble AS body

# no op
