# TAQL — uses beyond a query endpoint

Notes on what the current design makes possible that isn't the obvious "REST
endpoint that runs a query". Everything below follows from things the pipeline
already computes; nothing here needs a new concept.

Three of the claims are checkable today, so they were checked first — this is
real output from the compiler in this repo, against the saved query:

```
analysis by Country { total = sum(TransactionValue) }
top $limit by total
over { ClientId in $clients, TransactionDate in $from..$to }
```

```
INPUTS  {clients=list<string>, from=date, limit=integer, to=date}
OUTPUTS [Column[Country, string], Column[total, decimal]]
READS   [ClientId, Country, TransactionDate, TransactionValue]
CI      [resolution 1:13] unknown field 'Country' on transactions; available: [...]
```

A plan knows its own inputs, its outputs, and every field it touches — all
without a database connection. That enables more than serving queries.

## 1. The same query text could run against a stream, not just history

The whole front half — grammar, AST, resolver, `Tam` — has nothing to do with
SQL Server. Only `SqlServerGenerator` does. `Tam` is a typed tree, so instead of
rendering it to SQL you can *interpret* it against a single in-flight record.

That means one language for "query the past" and "alert on the present". In a
transactions domain that's the AML case: the analyst writes

```
over { TransactionValue > 10000, Country in ['XX','YY'], Direction = 'C' }
```

as a report today, and the **identical text** is compiled into a live filter on
the payment stream tomorrow. Today those are two artefacts in two languages that
drift apart, and the drift is the compliance risk.

Caveat: filters port directly, aggregation needs windowing (`over last 24
hours`) that the grammar doesn't have. Filters alone are most of the value.

## 2. A saved query is a typed endpoint, with no DTO written by hand

`plan.variables()` and `plan.columns()` *are* an interface definition. Point a
generator at them and a saved `.taql` file becomes an OpenAPI path with typed
parameters, a JSON Schema for the response, and a TypeScript client type —
regenerated whenever the query changes, so they cannot drift.

The usual failure mode here is a hand-maintained DTO that silently disagrees
with the query. That class of bug stops existing.

## 3. CI can break the build instead of your service

This ties directly to the execution error handling. `SCHEMA_MISMATCH` (SQL
Server error 207/208) is a runtime 500 caused by the catalog claiming something
the database lacks. But `TaqlCompiler.compileUncached` needs no connection — so
a CI job that compiles every saved query against the catalog turns that 500 into
a build failure, with a line and column, as the `CI` line above shows.

Add a catalog generated from `INFORMATION_SCHEMA` and dropping a column fails
the PR that drops it, naming every query that breaks.

## 4. The shape key is a cost identity, which makes admission control possible

APM tools group SQL by normalising text, badly. The shape key is an exact,
stable identity for a *class* of query, known **before execution**. So you can
accumulate real statistics per shape (p99 duration, rows scanned) and then
refuse or deprioritise a known-expensive shape at admission time, rather than
discovering it via the query timeout.

The same key gives result caching and ETags for free: shape + literals +
variables is a complete cache key.

## 5. Safe query access for untrusted parties

Field-level authorisation is the half you'd expect: `READS` is computable before
execution, so you can reject on grants rather than on results.

The twist is what it unlocks. A **mandatory predicate injected during lowering**
(force `ClientId` to the caller's own) is not expressible in the DSL and cannot
be written around, because the user's text never becomes SQL — only catalog
objects do. That makes TAQL safe to expose to *untrusted* parties: customers
querying their own data directly, instead of you building an endpoint per
question they ask.

## Cost to prove each

| idea | rough size | notes |
|---|---|---|
| CI validator | ~30 lines around `compileUncached` | cheapest, immediately useful |
| OpenAPI / TypeScript generator | ~80 lines walking `Plan` | no new concepts needed |
| Streaming interpreter | ~150 lines: a `Tam.Pred` evaluator over `Map<String,Object>` | biggest and most interesting; would demo one TAQL filter running against both the database and a synthetic event stream |
| Shape-keyed cost stats | small, but needs a metrics sink | the governor on top is the real work |
| Injected tenant predicate | small in the resolver | design work is in the grant model, not the code |
