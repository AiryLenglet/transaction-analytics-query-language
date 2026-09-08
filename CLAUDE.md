# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

TAQL — a query DSL for REST endpoints that compiles to **parameterised T-SQL**. Maven, Java 25, ANTLR 4.13. There is no REST layer in the repo: `openapi.yaml` is a *draft contract* for a service that does not exist yet, and `Main` is the demo driver. `README.md` holds the full language reference and the reasoning behind each design decision; this file covers what you need to change code safely.

## Commands

`mvn` is **not on PATH** in this environment and there is no wrapper committed. The only Maven present is IntelliJ's bundled copy:

```
alias mvn='/Applications/IntelliJ IDEA CE.app/Contents/plugins/maven/lib/maven3/bin/mvn'
```

```bash
mvn test                     # ANTLR codegen + compile + all tests
mvn compile                  # regenerates the parser into target/generated-sources
mvn compile exec:java        # compiles every query in example.taql, prints SQL + bindings

# single test class / nested group / method ($ must be quoted in zsh)
mvn test -Dtest=SqlFailureTest
mvn test -Dtest='TaqlCompilerTest$Windows'
mvn test -Dtest='TaqlCompilerTest$Windows#lagKeepsTheTypeOfWhatItReads'
```

The parser is generated at build time from `src/main/antlr4/.../Taql.g4` — after a grammar edit you must run `mvn compile` before the Java sources referencing new tokens/contexts will resolve.

Database is optional. `./runMsSqlServer.sh` starts SQL Server 2019 in Docker (sa / `Password22`, port 1433); `Main` then also *executes* the examples and applies `src/main/resources/init.sql`. Without it, `Main` prints SQL and bindings only, and the whole test suite still passes — the tests never touch a database.

## Pipeline

```
text → TaqlParserFacade (ANTLR)  → parse tree
     → AstBuilder                → Ast          untyped syntax model, LITERALS LIFTED OUT
     → Resolver (+ Catalog)      → Tam          typed, resolved, physical
     → SqlServerGenerator        → Plan         SQL text + parameter recipe
     → Binder                    → JDBC values
     → TaqlExecutor              → Rows
```

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

`SqlFailure.classify` maps `SQLException` to a category (RETRYABLE / RESOURCE / TIMEOUT / INVALID_DATA / SCHEMA_MISMATCH / PERMISSION). Three rules that the codes in `README.md` were derived from empirically:

- **Never branch on exception subclass** — mssql-jdbc throws plain `SQLServerException` for essentially everything.
- **Never match on message text** — server messages are localised.
- **Use both `getErrorCode()` and `getSQLState()`** — the code is 0 for driver-side failures (timeout, connection loss), which carry meaning only in the SQL state.

`TaqlExecutor` retries only what is worth retrying (safe because every TAQL query is a `SELECT`), bounds every statement with `queryTimeout`, and keeps the server's message off the response: `TaqlExecutionException.getMessage()` is a fixed generic string per category, raw detail lives on `logDetail()` / `databaseMessage()`. `SCHEMA_MISMATCH` (208/207) is a **server** fault — 500 and an alert, never a 400.

## Deliberate omissions

`README.md` ends with a "Not done" list (pagination, `having`, window functions in flat queries, authorisation, circuit breaking, cost control, multi-entity, Caffeine). These are choices, not oversights — check that list before "fixing" a gap.

Two defaults worth knowing: `Resolver.Options.defaultRowLimit` is **0 (no cap)**, so a query without `top` returns everything it matches; and `PlanCache` computes outside its lock, so a race can compile the same plan twice and discard one (harmless, intentional).
