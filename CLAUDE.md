# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

TAQL — a query DSL for REST endpoints that compiles to **parameterised T-SQL**. Maven, Java 25, ANTLR 4.13. There is no REST layer in the repo: `openapi.yaml` is a *draft contract* for a service that does not exist yet, and `Main` is the demo driver. `README.md` holds the full language reference and the reasoning behind each design decision; this file covers what you need to change code safely.

## Commands

`mvn` is **not on PATH** in this environment and there is no wrapper committed. The only Maven present is IntelliJ's bundled copy:

```
alias mvn='/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn'
```

```bash
mvn test                     # ANTLR codegen + compile + all tests
mvn compile                  # regenerates the parser into target/generated-sources
mvn compile exec:java        # runs every query in example.taql through TaqlTemplate

# single test class / nested group / method ($ must be quoted in zsh)
mvn test -Dtest=SqlFailureTest
mvn test -Dtest='TaqlCompilerTest$Windows'
mvn test -Dtest='TaqlCompilerTest$Windows#lagKeepsTheTypeOfWhatItReads'
```

The parser is generated at build time from `src/main/antlr4/.../Taql.g4` — after a grammar edit you must run `mvn compile` before the Java sources referencing new tokens/contexts will resolve.

Database is optional. `./runMsSqlServer.sh` starts SQL Server 2019 in Docker (sa / `Password22`, port 1433); `Main` then also *executes* the examples and applies `src/main/resources/init.sql`. Without it every query still compiles — and its SQL is still logged — then fails with a classified execution error. The whole test suite passes either way; the tests never touch a database.

**Logging.** `Main` holds only a `TaqlTemplate`, so the generated SQL, plan ids and cache behaviour come from the library's own debug logs rather than from the driver — which is what an operator sees in production. `slf4j-api` is a normal dependency; `slf4j-simple` is `runtime` scope for the demo only and an embedder should exclude it. `src/main/resources/simplelogger.properties` sets debug on `ch.lenglet`; the copy in `src/test/resources` wins on the test classpath and keeps the suite quiet.

What is safe to log is a deliberate line: the shape key and the generated SQL are value-free by construction, so they can go in a log; the query source and the bound values cannot — they are the client ids and amounts the query asked about. `Plan.id()` is a short hash of the shape key that correlates the compile line with every execution of it.

## Pipeline

```
text → TaqlParserFacade (ANTLR)  → parse tree
     → AstBuilder                → Ast          untyped syntax model, LITERALS LIFTED OUT
     → Resolver (+ Catalog)      → Tam          typed, resolved, store-agnostic
     → Backend.generate          → Plan         statement text + parameter recipe
     → PlanRunner.run            → List<Map<String,Object>>
```

`TaqlTemplate` is the API and names no store: it compiles, binds, and retries what a `FailureCategory` says is worth retrying. The two seams either side of it are `Backend` (query → statement, pure and shareable) and `PlanRunner` (statement → rows, holds the connection). They are separate because a compiler must not need a live connection — a validation endpoint has no database.

The packages say which is which. `ch.lenglet.taql.runtime` is store-neutral; `runtime.jdbc` holds `JdbcPlanRunner`, `Binder` and `SqlFailure`; `ch.lenglet.taql.sql` holds `SqlServerGenerator` and `SqlType`. `grep -rl 'java.sql\|javax.sql\|SqlType' src/main/java` should return only those two packages, `DemoCatalog` and `Main`.

Everything above `Backend` is store-agnostic and must stay that way: `Tam` and `Plan` carry a `PhysicalType`, never a `SqlType`. `Backend` is the single seam where a query becomes T-SQL — it supplies both the statement and the default physical type for a value no column has typed, which is why the resolver takes one. `SqlServerGenerator` is the only implementation; `grep -l SqlType src/main/java` shows exactly which files are dialect-specific, and that list should not grow.

Two things a second backend needs that are deliberately **not** designed yet, because they cannot be designed well from one implementation: an execution seam (`runtime` is JDBC to its bones — connection, error taxonomy, driver), and a capability model so a backend that cannot express a construct — window functions have no analogue outside SQL — yields a positioned diagnostic instead of the generator throwing `IllegalStateException`. `Catalog` is the third: its types are neutral now, but `Table`/`Join`/`Field.column` describe rows matched on key columns, which is not how a graph is addressed.

`TaqlCompiler` is the façade over the whole thing plus both caches. `compileUncached` bypasses the caches and is what most tests call.

## The invariant everything else rests on

`AstBuilder` lifts **every literal** out of the tree into `Ast.Query.literals()` and leaves an `Ast.Lit(slot)` hole behind. By the time the generator runs, user-typed characters are not reachable from the tree it walks — injection resistance is structural, not a discipline. Identifiers get the same treatment from the other side: a name that does not resolve to a `Catalog.Field` is a compile error, so no user text is ever emitted as an identifier either.

**Do not break this.** Any new node that could carry a user value must carry a slot index (`Tam.LiteralRef`) or a variable name (`Tam.Variable`), never the value.

## The two caches, and the shape key

| level | key | populated after | skips |
|---|---|---|---|
| L1 `textCache` | exact source text | — | everything, parsing included |
| L2 `shapeCache` | `Ast.Query.shapeKey()` | parsing | resolution, typing, SQL generation |

The shape key is `AstPrinter.canonical(stmt)` — a value-independent rendering of the literal-free AST, computed **before** name resolution.

**Consequence for any change you make:** if a syntactic feature changes the generated SQL, `AstPrinter` must render it, or two queries needing different SQL will collide on one cached plan. Inline list *arity* is in the key for exactly this reason (`IN (?, ?)` vs `IN (?, ?, ?)`); a `$variable` list is not, because it lowers to a single `OPENJSON` parameter of unknown arity. `TaqlCompilerTest$Cache` and `theWindowSpecIsPartOfTheShapeKey` guard this — add a case there when you add syntax.

**Second consequence, and the one that bites hardest:** `Plan.Auto` slots index the literal table of the query *currently running*, so two texts sharing a key must number their literals identically. Before this was fixed, `list {...} top 5 over { v > 500 }` silently executed as `TOP 500 ... > 5`.

Two things now prevent it. **Clause order is fixed** — `from → over → sort by → top`, enforced in `AstBuilder.clauses()` rather than the grammar so the error names the clause and the shape instead of being an opaque parse failure; the order is analytical (`from`/`over` are one concern, the population; the rest acts on it). And `AstBuilder` still *builds* clauses in that canonical order rather than as-written, since building is what allocates slots — so relaxing the rule later cannot silently reintroduce the bug.

Because that is an agreement between two files, the key renders the slot index too (`#0S`, not `#S`). If the walks ever drift, the keys differ and the queries compile separate plans — a redundant plan, never a query bound to another query's values. `clausesMustBeWrittenInTheCanonicalOrder` and `theCanonicalOrderNumbersLiteralsInTheOrderThePlanBindsThem` cover both halves: give a new clause a `rank()` and keep them passing.

The key is computed before resolution, which is also why `Catalog.Field` allows exactly **one spelling per field** (case-insensitive, no aliases): alternate names would compile separate plans for identical SQL.

## Where a change lands

Adding language surface usually means touching this whole chain, in order:

1. `Taql.g4` — grammar rule (keywords are case-insensitive via the `fragment A..Z` letter rules at the bottom)
2. `AstBuilder` — visitor method; literals go through the slot-allocating helper
3. `AstPrinter` — render the new construct into the canonical key
4. `Functions` — registry for scalars / aggregates / window functions; anything not listed is a compile error
5. `Resolver` — resolution, typing, diagnostics (`Diagnostic.Phase` SYNTAX/RESOLUTION/TYPE with line:column)
6. `Tam` — typed node
7. `SqlServerGenerator` — rendering

The resolver **collects** diagnostics rather than failing on the first, then throws one `TaqlException` carrying all of them; `error()` accumulates, `fail()` throws immediately. Prefer `error()`.

## Type system: two of them, deliberately

- `TaqlType` — the DSL lattice (string, integer, decimal, date, …). Knows nothing about SQL Server.
- `SqlType` — a sealed hierarchy of records for the *physical* type (`VarChar(50)`, `Decimal(10,2)`, `DateTime2(7)`). Constructors reject what the server would reject.

`SqlType.sql()` is the only route a type takes into generated SQL, and `unicode()` drives `setNString` vs `setString` per parameter in `Binder`. The resolver types a literal from the **column it is compared against**, so `'2010-01-01'` binds as a `date`, not a string — sending generic strings would force a per-row conversion and lose the index seek.

## Generator invariants

- Single sequential pass: text is appended in final statement order, and every `?` emitted appends its matching `Plan.ParamSlot`. That keeps slot order and JDBC index in lockstep **including** when an expression is rendered twice. Never reorder emission without reordering slots.
- Table aliases are allocated by the generator, not stored in the catalog — a pure function of the entity, so they neither drift with which joins a query needs nor perturb the shape key.
- **Level stacking.** A computed group key carrying parameters goes through a derived table (T-SQL matches SELECT/GROUP BY occurrences syntactically, and parameterised copies no longer match). Window functions add a level above the GROUP BY; `top N by m within k` adds another for the `ROW_NUMBER` predicate. All three can stack — there is a test for that combination.
- Window functions read the query's **own outputs** (`Tam.OutputRef`), never base columns, and never another window function.

## Catalog

`Catalog` is the semantic layer *and* the security boundary. It holds **no SQL text**: a field names the join it is reached through, a join names column pairs, and the generator emits the rest. Invalid catalogs fail in `Catalog.Entity`'s constructor — where the catalog is written, not where a query uses it.

`DemoCatalog` is a single table with no joins, so **the join path is only covered by the test-only fixture in `TaqlCompilerTest$Joins`**. If you touch join handling in `Resolver` or `SqlServerGenerator`, that nested class is the only thing watching you.

## Runtime error handling

`FailureCategory` is the neutral vocabulary (RETRYABLE / RESOURCE / TIMEOUT / INVALID_DATA / SCHEMA_MISMATCH / PERMISSION); `jdbc.SqlFailure.classify` maps `SQLException` onto it. A deadlock and a permission refusal are facts about running a query anywhere — only the codes that identify them are dialect-specific, which is why retrying lives in `TaqlTemplate` and classifying lives in the runner. Three rules that the codes in `README.md` were derived from empirically:

- **Never branch on exception subclass** — mssql-jdbc throws plain `SQLServerException` for essentially everything.
- **Never match on message text** — server messages are localised.
- **Use both `getErrorCode()` and `getSQLState()`** — the code is 0 for driver-side failures (timeout, connection loss), which carry meaning only in the SQL state.

`TaqlTemplate` retries only what is worth retrying (safe because every TAQL query is a `SELECT`), bounds every statement with `queryTimeout`, and keeps the server's message off both the response *and* the log. `TaqlExecutionException.getMessage()` is a fixed generic string per category; `logDetail()` is the failure, error number, SQL state and attempt count, all value-free. `SCHEMA_MISMATCH` (208/207) is a **server** fault — 500 and an alert, never a 400.

**CID.** A query's constants and a caller's variable values are client-identifying data, and nothing in the library may log them. Error 245 is *"Conversion failed when converting the varchar value '…'"*, so `databaseMessage()` can quote a client id — it is reachable but never logged here, and since server messages are localised it was never the diagnosis anyway. The types that hold values (`TaqlQuery`, `TaqlCompiler.Compiled`, `Ast.Query`) override `toString()` so a record's generated one cannot disclose them; `CidTest` pins all of it. Safe to log, by construction: the generated SQL (it carries `?`), the shape key, `Plan.id()`, and counts.

## Deliberate omissions

`README.md` ends with a "Not done" list (pagination, `having`, window functions in flat queries, authorisation, circuit breaking, cost control, multi-entity, Caffeine). These are choices, not oversights — check that list before "fixing" a gap.

Two defaults worth knowing: `Resolver.Options.defaultRowLimit` is **0 (no cap)**, so a query without `top` returns everything it matches; and `PlanCache` computes outside its lock, so a race can compile the same plan twice and discard one (harmless, intentional).
