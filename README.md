# jepsen-spacetimedb

## Testing [SpacetimeDB](https://github.com/clockworklabs/SpacetimeDB) with [Jepsen](https://github.com/jepsen-io/jepsen) for Correctness, Durability, and General Resilience

### SpacetimeDB [describes](https://github.com/clockworklabs/SpacetimeDB#what-is-spacetimedb) itself as

> ... a relational database that is also a server
>
> ... your schema and business logic ... SpacetimeDB compiles it, runs it inside the database, and automatically synchronizes state to connected clients in real-time
>
> ... provides all the ACID guarantees of a traditional RDBMS
>
> ... all application state is held in memory for fast access, while a commit log on disk provides durability and crash recovery

### Jepsen was purpose built to test these assertions and database properties

So...

> "We should do a Jepsen test!"
>
> -- Every Developer ever... 🙂

What consistency [models](https://jepsen.io/consistency/) does SpacetimeDB exhibit?

- [Strong Serializable](https://jepsen.io/consistency/models/strong-serializable) on the server?
- [Serializable](https://jepsen.io/consistency/models/serializable) for sync views?
- always [Monotonic Atomic View](https://jepsen.io/consistency/models/monotonic-atomic-view)?
- any isolation anomalies? [Elle: Inferring Isolation Anomalies from Experimental
Observations](https://raw.githubusercontent.com/jepsen-io/elle/master/paper/elle.pdf)

Can a test cause data corruption or loss?

- fuzz all the SDK API calls, timings, and orderings while checking each transaction for correctness
- can environmental faults cause correctness/liveness issues that can be persisted across restarts? synced to clients?
- can unique patterns of user behavior impact data correctness and/or server availability?

In general, how does SpacetimeDB behave operating under

- normal user behavior and environmental conditions
- diverse user behaviors
- environmental failures
- random property based behaviors and environmental conditions

----

## Transparent Development

Public GitHub repository

- docs
- SpacetimeDB client and modules
- Jepsen tests
- [Docker](./docker/README.md) environment to run tests
- [GitHub Actions](https://github.com/nurturenature/jepsen-spacetimedb/actions) to run tests, scenarios, reproduce bugs
- development [logbook](./docs/logbook.md)

Progressive development and deliveries leading to a final analysis and report.

### Out of Scope

We are *not* testing:

- raw performance
- security
- HTTP API
  - `/v1/identity`
  - `/v1/database`

----

## Progressive Test Development

> "Always be suspicious of success."
>
> -- Leslie Lamport

- single server, single client
  - tests the tests
  - demonstrates test fidelity

- single server, multiple clients

- multiple servers, multiple clients

- property based user behavior

- property based environmental faults

----

## Safety First

> “Know the rules well, so you can break them effectively.”
>
> -- Dalai Lama XIV

The initial implementations of SpacetimeDB deployments, clients, and database modules will all have a safety first bias

- configurations, flags
  - client connection, SpacetimeDB, Omni-Paxos
- favor consistency and client syncing over immediate performance

----

## Clients

The client will be a thin wrapper around the TypeScript APIs that expose an http endpoint for Jepsen to post transactions, commands, and behaviors and receive the results.  The client must not introduce any of its own effects. Its purpose is to serve Jepsen's requests with fidelity to SpacetimeDB's APIs.

Clients are expected to have total sticky availability

- each client is sticky
  - uses its own, the same, `DbConnection`
  - unless intentionally disconnected, or disconnected by an injected fault
- clients are expected to be available, liveness, unless explicitly killed or paused

Observe and interact with the database using all of the APIs

- writes: `Reducer`s on server (implicit transaction)
- writes/reads: `Procedure`s on server (explicit transaction)
- reads: `Subscription`s and `View`s on client (TypeScript's `iter()` implies transactional access?)

```typescript
// sample Procedure, needs explicit transaction
ctx.withTx(ctx => {
  // writes(appends)
  const append_list = ctx.db.lists.key.find(k);
  if (append_list == null) {
    const new_list = { key: k, list: [v!] };
    ctx.db.lists.insert(new_list);
  } else {
    append_list.list.push(v!);
    ctx.db.lists.key.update(append_list);
  }
  result.push(['append', k, v!]);
  
  // reads
  const read_list = ctx.db.lists.key.find(k);
  if (read_list == null) {
    result.push(['r', k, null]);
  } else {
    result.push(['r', k, read_list.list]);
  }
});
```

----

## User Behaviors

Even in the realm of documented proscribed behavior, people find ways to do the darndest things! 🙃

```text
So let's do random things!
In random orders!
At random times!
With other random people!
```

IOW, property test the range of *"normal"*, scare quotes intentional, behaviors.

In addition to the SDK API, this includes use of the `spacetime` CLI.

----

## Faults

Jepsen runs the real database with real clients in a real environment and introduces real faults.

If your database is successful, e.g. adoption, lifetime, etc., it will experience environment faults.

Are they really *Faults*? or just **Real** Life? 🤔

Faults are applied

- to random clients and/or servers
- at random intervals
- for random durations
- in random combinations
- and in particular fuzz around
  - state transformations
  - timeout and heartbeat values
  - error recovery

We still expect

- consistency models to remain valid
- total sticky availability unless the client has been intentionally paused/killed or faulted

----

### Offline / Online

Randomly disconnect and then reconnect

- intentionally with SDK APIs
- disconnect due to faults, and reconnect due to watchdog monitoring

```typescript
DbContext.disconnect();

DbConnectionBuilder.build();
```

----

### Network

Use `tc-netem(8)` to progressively degrade the network up to and including total partitioning

- packet delay, loss, corruption, duplication, re-ordering, rate limiting
- degradation may be unidirectional or bidirectional
- client <-> SpacetimeDB server
- SpacetimeDB server <-> SpacetimeDB server (when clustered)
- partition random nodes into
  - majority/minority
  - several minorities
  - majorities ring, both perfect and stochastic

A partially failing, stuttering, or fapping network can be worse than a clean failure.

```clj
;; sample use of iptables to drop traffic 
;; from nodes on the other side of the partition
(su
  (exec :iptables :-A :INPUT :-s (control.net/ip src) :-j :DROP :-w))

;; heal
(su
  (exec :iptables :-F :-w)
  (exec :iptables :-X :-w))
```

----

### Process Kill/Start, Pause/Continue

Randomly impact one to all server and/or client processes.

```bash
# kill/start

# SpacetimeDB server
grepkill SIGKILL spacetime
spacetime start ...

#client
grepkill SIGKILL node
npm run dev
```

```bash
# pause/continue

# SpacetimeDB server
grepkill SIGSTOP spacetime
grepkill SIGCONT spacetime

#client
grepkill SIGSTOP node
grepkill SIGCONT node
```

----

### Clock Bump/Strobe/Reset

Local clocks across a distributed system can disagree, and at times, can be like synchronizing cats.

Randomly

- bump    (adjust the time by delta milliseconds)
- strobe  (strobe the time back and forth by delta milliseconds)
- reset   (reset the time)

the clocks on a random set of nodes for a random duration.

Jepsen uses its own `strobe-time.c` and `bump-time.c`.

----

### fsync, Durability, and LazyFS

Test SpacetimeDB's durability and use of `fsync`.

Think power glitches and similar hiccups.
Does SpacetimeDB durably persist the database, or is it vulnerable to data loss?

We'll use the `LazyFS` tool

- [LazyFS GitHub repository](https://github.com/dsrhaslab/lazyfs):
  A FUSE file system with an internal dedicated page cache that only flushes data if explicitly requested by the application. This is useful for simulating power failures and losing unsynced data.
- paper: [When Amnesia Strikes: Understanding and Reproducing Data Loss Bugs with Fault Injection](https://dsr-haslab.github.io/assets/files/2024/lazyfs-vldb24-mariaramos.pdf)

to simulated a power glitch

- let db do some work, i.e. writes
- `checkpoint` the `lazyfs` filesystem
  - maybe triggered by application, os, etc.
  - write un-fsync'd writes to the underlying filesystem
- let db do some work, i.e. writes
- 🌩️ power glitch
  - kill the db's process
  - `lose-unfsynced-writes`
- ☀️ power normal
  - attempt to restart the db

----

### File Corruption

Fault injection involving files on disk

- induce bitflips
- truncate files
- copy chunks randomly within a file
- snapshot and restore chunks

Can we randomly corrupt files in SpacetimeDB's data directory and get it to accept and/or sync invalid data?

----

### Cluster Membership

> "I don’t want to belong to any club that would accept me as one of its members."
>
> -- Groucho Marx’s letter of resignation to the Friars’ Club

Fuzz adding and removing SpacetimeDB nodes to and from the cluster both intentionally and through introducing faults.

Explicitly test Omni-Paxos's major claim, [Omni-Paxos is the only protocol that can recover from all the described partial connectivity scenarios (constrained, quorum loss, and chained scenario)](https://github.com/haraldng/omnipaxos-artifacts#major-claims).

SpacetimeDB's Jepsen tests will more vigorously test Omni-Paxos than the paper's artifacts.

----

## Data Design

### Keyed Append Only List

Using a keyed append only list as a data model will give Jepsen's checker, [Elle](https://github.com/jepsen-io/elle), the best chance to find the most anomalies.

----

### Schema

```typescript
const lists = table(
  {
    name: 'lists',
    public: true
  },
  {
    key: t.i32().primaryKey(),
    list: t.array(t.i32())
  }
);
```

----

### Transactions

Jepsen will generate transactions consisting of random writes/reads of random keys. Typically we use an exponential key distribution to emphasis potential conflicts between concurrent transactions.

Sample of a transaction from a Jepsen Test generator showing:

```clj
;; list with a key of 1
[[:r 1 nil]    ;; starts out empty, nil
 [:append 1 1] ;; append 1 to the end
 [:append 1 2] ;; append 2 to the end
 [:r 1 [1 2]]] ;; reading our writes :)
```

Note that by using an append only list, every read will/should reflect a complete ordered history of all writes to that object, in our case the table row with the indexed key.

----

## Misc

### No AI

Using Jepsen meaningfully, like all dist-sys endeavors, is a meaningful commitment.

There are an increasing number of AI generated Jepsen tests being promoted as proof of a projects rigor and correctness.  Unfortunately, closer examination has so far shown a no-op test, invalid test, or an excessive amount of un-understandable code.

### Stand on the Shoulders of Giants

We'll examine the archive of existing Jepsen tests for consensus systems and insure that SpacetimeDB's tests incorporate all of the established techniques.

### Giving Back

Contribute back to Jepsen any found improvements to Jepsen's docs, libraries, etc.

Contribute back to Omni-Paxos any bugs, successful findings, etc.

It appears that Harald Ng, the lead author of [Omni-Paxos: Breaking the Barriers of Partial Connectivity](https://dl.acm.org/doi/pdf/10.1145/3552326.3587441), from the KTH Royal Institute of Technology, has given their class an assignment to use Maelstrom, an educational testbed developed by Jepsen, to test Omni-Paxos.

SpacetimeDB's Jepsen tests will be qualitatively different in their scope and abilities to detect anomalies than those using Maelstrom.

### Jepsen Tests Are Living Tests

Fuzzing distributed systems productively, i.e. actually improving documentation/code, increasing understanding/confidence, in a sustainable way is both an art and a science.

The Jepsen community has lead the way in showing us how to do it effectively, ethically, and in a way that's truly fun.

Many thanks to [@Aphyr](https://github.com/aphyr) and [jepsen.io](https://jepsen.io) for [Jepsen](https://github.com/jepsen-io/jepsen)!
