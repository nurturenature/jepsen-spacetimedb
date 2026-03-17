# Jepsen Tests for SpacetimeDB

Why SpacetimeDB?

- interesting architecture
- distributed sync
- dynamic community
- developers are responsive to interactions

SpacetimeDB's release of its version 2 and benchmarking tests have attracted attention and generated some controversy around its and other databases' consistency models, isolation levels and durability.  Jepsen was purpose built to test these database properties.

Lets develop a suite of Jepsen tests for SpacetimeDB to see what we can learn, and hopefully contribute back to the project.

----

## Write/Read Register

Let's start with using a simple key/value int/int register for our data model.

It's a weaker data structure than an append only list, but it's easier to implement and explain.
We'll evolve our way into using Jepsen's list-append.

```typescript
const spacetimedb = schema({
  registers: table(
    { public: true },
    {
      k: t.i32().primaryKey(),
      v: t.i32(),
    }
  ),
});
```

----

## Transactions

SpacetimeDB is architected for all writes and reads to happen in a transaction and be executed on the server. Writes are serialized by the database using a single writer, no MVCC.

One can also subscribe to a query that is synced to the client and receive client events for insert/delete/update events.

You write client functions, `Procedure`s, `Reducer`s, and `View`s, and then publish them to the SpacetimeDB database. The client will call the functions over a websocket.

There is no builtin `upsert` functionality, so a `Reducer` is published to the database:

```typescript
export const upsertRegister = spacetimedb.reducer(
  { k: t.i32(), v: t.i32() },
  (ctx, { k, v }) => {
    const register = ctx.db.registers.k.find(k);
    if (register) {
      register.v = v;
      ctx.db.registers.k.update(register);
    } {
      ctx.db.registers.insert({ k: k, v: v });
    }
  }
);
```

Note the use of the find-test-branch-update/insert pattern.

----

## Consistency Model

We'll use Jepsen's [Consistency Models](https://jepsen.io/consistency/models).

SpacetimeDB claims to offer a [Strong Serializable](https://jepsen.io/consistency/models/strong-serializable) consistency model for transactions.

Note that Strong Serializable also includes [Monotonic Atomic View](https://jepsen.io/consistency/models/monotonic-atomic-view).

----

## Current Status

Working on building, publishing Docker images to use in GitHub actions.
IOW, automate the running of tests, allow others to reproduce the findings.

----

## Log Book

Tests can

- SpacetimeDB
  - install, teardown and manage the SpacetimeDB node
  - build and publish a `Module` for a `wr-register` into the database
  
- Client Nodes
  - install, teardown, and manage client nodes
  - call `Procedure`s to write/read from a `wr-register`

- Tests
  - communicate and drive clients using a REST API
  - check tests for consistency models and anomalies
