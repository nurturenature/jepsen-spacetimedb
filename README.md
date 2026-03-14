# jepsen-spacetimedb

## Jepsen Tests for SpacetimeDB

Why SpacetimeDB?

- interesting architecture
- distributed sync
- dynamic community
- developers are responsive to interactions

SpacetimeDB's release of its version 2 and benchmarking tests have attracted attention and generated some controversy, maybe just a misunderstanding, around its and other databases' consistency models, isolation levels and durability.  Jepsen was purpose built to test these database properties.

Lets develop a suite of Jepsen tests for SpacetimeDB to see what we can learn, and hopefully contribute back to the project.

----

## Current Status

Working on schema and simple reducers for a `wr-register`.

----

## Log Book

Tests can

- SpacetimeDB
  - install, teardown and manage the SpacetimeDB node
  - build and publish a `Module` for a `wr-register` into the database

- Client Nodes
  - install, teardown, and manage client nodes