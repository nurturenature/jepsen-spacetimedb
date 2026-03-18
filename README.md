# Jepsen Tests for SpacetimeDB

Why SpacetimeDB?

- interesting architecture
- distributed sync
- dynamic community
- developers are responsive to interactions

SpacetimeDB's recent release of its version 2 has attracted attention and generated some questions around its and other databases' consistency models, isolation levels and durability.

Jepsen was purpose built to test these database properties. Lets develop a suite of Jepsen tests for SpacetimeDB to see what we can learn, and hopefully contribute back to the project.

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

Jepsen will generate transactions consisting of a random number of writes and/or reads against random keys with monotonically increasing values per key.
We use an exponential key distribution to emphasize potential conflicts against the same row.

```clj
;; sample of clients 6, 7, and 8 invoking transactions and receiving an ok response

;; [:r k v] -> [read  key value] (read values are nil on invocations)
;; [:w k v] -> [write key value]

6 :invoke :txn  [[:w 8 21] [:w 7 12] [:w 7 13] [:w 9 31]]
6 :ok     :txn  [[:w 8 21] [:w 7 12] [:w 7 13] [:w 9 31]]
7 :invoke :txn  [[:r 9 nil] [:r 5 nil]]
7 :ok     :txn  [[:r 9 31] [:r 5 3]]
8 :invoke :txn  [[:w 9 32] [:w 7 14] [:w 8 22]]
8 :ok     :txn  [[:w 9 32] [:w 7 14] [:w 8 22]]
```

----

## Consistency Model

We'll use Jepsen's [Consistency Models](https://jepsen.io/consistency/models).

SpacetimeDB claims to offer a [Strong Serializable](https://jepsen.io/consistency/models/strong-serializable) consistency model for transactions.

Note that Strong Serializable also includes [Monotonic Atomic View](https://jepsen.io/consistency/models/monotonic-atomic-view).

When determining version orders for `wr-register`, the following Jepsen checker flags are set to `true`:

- linearizable-keys?  Uses realtime order
- sequential-keys?    Uses process order
- wfr-keys?           Assumes writes follow reads in a txn

----

## Nemeses

Jepsen runs the real database with real clients in a real environment and introduces real faults.

If your database is successful, e.g. adoption, lifetime, etc., it will experience environment faults.

Are they really Faults or just Real Life? 🤔

### Pause/Resume

Randomly pause/resume a random set of nodes for a random duration.
Act on the SpacetimeDB or client's process with:

```clj
(cu/grepkill! :stop spacetimedb-ps-name)

(cu/grepkill! :cont spacetimedb-ps-name)
```

----

## GitHub Actions

- `wr-register-procedure` - wr-register data model with all writes/reads in a transaction in a `Procedure`

- `wr-register-procedure-pause-resume` - like `wr-register-procedure` action with the addition of a pause nemesis
