# Running SpacetimeDB Tests in Docker

## Jepsen Test Environment

```shell
cd jepsen-spacetimedb/docker

# build Jepsen control, node, and setup images
./docker-build.sh

# bring up cluster
./docker-compose-up.sh

# run a test
./docker-run.sh lein run test --workload list-append --nodes spacetimedb,n1,n2,n3,n4,n5,n6,n7,n8,n9,n10 --concurrency 20 --rate 1000 --time-limit 100

# bring up a web server for test results
./jepsen-web.sh

# visit http://localhost:8088

# bring cluster down and cleanup containers, networks, and volumes
./docker-compose-down.sh
```

Images are largish:

- Debian systemd base images
- system tools used to inject real faults
- full Jepsen libraries
