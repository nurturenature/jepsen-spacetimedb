# Logbook

A place to log the development process.

Currently, only contains issues found while doing the proof of concept.

----

## LazyFS

Using LazyFS the tests were able to show unfsynced writes.

The issue, [un-fsync'd writes #4886](https://github.com/clockworklabs/SpacetimeDB/issues/4886).

And it's been addressed in SpacetimeDB 2.2.0 🎉

----

## False Errors

A no fault environment will still produce psychosomatic, the write actually does happen, error messages.

Likely a reintroduction of [TS Client: "ERROR: Negative reference count for row", and "ERROR: Updating a row that was not present in the cache"](https://github.com/clockworklabs/SpacetimeDB/issues/2894)

Test results:

```clj
:matches ({:node "n1",
           :line "❌ ERROR Updating a row that was not present in the cache. Table: lists, RowId: 11"}
          ...)
```

Client log showing that key 51 did have 3 appended to it as the transaction read its own writes:

```log
[endpoint] request: body: [{"f":"append","k":51,"v":3},{"f":"r","k":51,"v":null}]
❌ ERROR Updating a row that was not present in the cache. Table: lists, RowId: 51
[endpoint] response: "{"type":"ok","value":[["append",51,3],["r",51,[1,2,3]]]}"
```
