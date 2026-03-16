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

## Write/Read Register

Let's start with using a simple key/value int/int register for our data model.

It's a weaker data structure than an append only list, but it's easier to implement and explain.
We'll evolve our way into using Jepsen's list-append.

```typescript
const spacetimedb = schema({
  registers: table(
    { public: true },
    {
      k: t.i64().primaryKey(),
      v: t.i64(),
    }
  ),
});
```

----

## Transactions

SpacetimeDB is architected for all writes and many read to happen in a transaction and be executed on the server.

You write client functions, `Procedure`s, `Reducer`s, and `View`s, and then publish them to the SpacetimeDB database. The client will call the functions over a websocket.

There is no builtin `upsert` functionality, so a `Reducer` is published to the database:

```typescript
export const upsertRegister = spacetimedb.reducer(
  { k: t.i64(), v: t.i64() },
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

## Current Status

Working on the REST API between Jepsen and the Node client.
The less JSON the better. 🙃

----

## Log Book

Tests can

- SpacetimeDB
  - install, teardown and manage the SpacetimeDB node
  - build and publish a `Module` for a `wr-register` into the database

- Client Nodes
  - install, teardown, and manage client nodes
