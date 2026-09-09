# TAQL

A query language for REST endpoints, compiled to parameterised T-SQL.

```
DSL text
  -> ANTLR lexer/parser        (Taql.g4)
  -> parse tree
  -> untyped syntax model      (ast/Ast, ast/AstBuilder)   << literals lifted out here
  -> name resolution + types   (sem/Resolver, catalog/)
  -> typed analytics model     (sem/Tam)
  -> T-SQL + parameter recipe  (sql/SqlServerGenerator, plan/Plan)
```

Run `mvn compile exec:java` to run every query in `src/main/resources/example.taql`
through a `TaqlTemplate`. Start `./runMsSqlServer.sh` first and the results come
back too; without it each query still compiles and fails with a classified
execution error. The generated SQL, plan ids and cache hits arrive as the
library's own debug logs rather than from the demo — `Main` holds nothing but
the template.

## Language

### Aggregation

```
analysis by <keys> { <measures> } [over { <filters> }] [top N by <measure>]
```

Clauses are written in that order, and any other order is a compile error
naming the clause and the shape. `from` and `over` are one concern — they say
which population is being analysed — so nothing comes between them, and what
follows describes what the query does to that population: `top 10 by revenue`
means nothing until the rows it ranks are known. One spelling per query also
means one literal numbering, which is what lets a cached plan bind another
query's values safely (see *Plan caching*).

A key is `alias = <expr>` or a bare field. A measure is
`alias = <agg>(<expr>) [when <predicate>]`; `when` compiles to a conditional
aggregate, so several differently-filtered measures still read the table once.
Aggregates: `count`, `sum`, `avg`, `min`, `max`, with `count(distinct x)`.

### Flat

```
list { <projections> } [over { <filters> }] [sort by <k> [desc], ...] [top N]
```

Same expression and filter language, no grouping. `sort by` may name an output
alias or any catalog field.

### Window functions

Computed over the grouped result, so they read the query's own outputs by name
rather than base columns:

```
analysis by Country, CounterpartyName {
    total = sum(TransactionValue)
    pct   = share(total) within Country
    rk    = rank(total) within Country
    prev  = lag(total) within Country ordered by y
}
top 2 by total within Country
```


- `share(m)` — `m` as a fraction of the partition total. Guarded with `NULLIF`,
  so a zero denominator yields NULL rather than SQL Server error 8134.
- `rank(m)` — position within the partition, 1 = largest. Orders by its own
  argument descending unless `ordered by` says otherwise.
- `lag(m)` / `lead(m)` — the neighbouring row's value. `ordered by` is required;
  there is no sensible default for "which row comes before".
- `within k, ...` is `PARTITION BY`; omitted means the whole result.
- `top N by m within k` caps rows *per group* rather than globally, so it becomes
  a `ROW_NUMBER` predicate instead of a `TOP` clause.

A window function reads a group key or an aggregate, never another window
function — chaining would need the measures ordered by dependency.

### Shared

- Filters on separate lines are implicitly `AND`ed; `and`, `or`, `not` and
  parentheses work inside one line.
- `in ['a','b']`, `in 'x'..'y'` (BETWEEN), `in $var`, `is [not] null`,
  `[not] like`, and the usual comparisons.
- `match x { ['a','b'] -> 'A'  _ -> 'other' }` and the condition form
  `match { amount > 1000 -> 'BIG'  _ -> 'small' }` both compile to `CASE`.
- Scalar functions: `year month day upper lower length abs round concat coalesce`.
- Keywords are case-insensitive; `` `backticks` `` escape an identifier that
  collides with one. `//` and `/* */` comments.
- Field names are case-insensitive but singular: each field has exactly one
  name, with no alternate spellings. Accepting several names for one column
  would let the same query be written more than one way, and since the plan
  cache is keyed on the query *as written* -- it must be, the key is computed
  before name resolution -- each spelling would compile a separate plan for
  identical SQL.

## Design notes

**Values are never text.** `AstBuilder` lifts every literal out of the tree into
a side table and leaves a slot index behind. By the time the SQL generator runs,
the characters the user typed are not reachable from the tree it is walking — so
there is no code path that could concatenate them into a statement, rather than
a discipline that has to be maintained. Identifiers get the same treatment from
the other side: a name that does not resolve to a catalog field is a compile
error, so no user-supplied text is ever emitted as an identifier either.

**The catalog is the semantic layer.** It decides which names exist at all, and
what each one maps to. Today that mapping is 1:1 -- the DSL vocabulary is the
column names -- but `Catalog.Field.of(name, type, column, sqlType)` exposes a
column under a different name when the business vocabulary and the schema
disagree (the join fixture in the tests does exactly that). The schema is a
single table, so no query
currently emits a join -- but `Catalog.Join` is wired through the resolver and
generator, and resolving a field that lives on a joined table is what pulls that
join into the plan. Since the demo schema no longer exercises it, that path is
covered by a test-only fixture (`TaqlCompilerTest.Joins`).

**The catalog holds no SQL text.** A field names the *join* it is reached
through, a join names the *column pairs* it matches on, and the generator
allocates table aliases when it emits the statement (derived from the table
name, de-duplicated, never colliding with the derived-table alias). So a table
can appear in a catalog without committing to an alias in every statement, and
there is no string in the catalog that ends up in a query verbatim. Allocation
is a pure function of the entity, so aliases neither drift with which joins a
query needs nor affect the plan cache. Fields pointing at a missing join fail in
`Catalog.Entity`'s constructor, where the catalog is written.

**Two caches, because one does not work.** Caching source text to plan only
helps if clients send byte-identical queries, and they will not — the constants
change every call.

| level | key | populated after | skips |
|---|---|---|---|
| L1 | exact source text | — | everything, parsing included |
| L2 | query *shape* | parsing (the cheap phase) | resolution, typing, SQL generation |

The shape key is the canonical rendering of the literal-free AST, so a thousand
queries differing only in their constants share one L2 entry. Each `Plan` holds
SQL plus a *recipe* for its parameters — `Auto(i)` reads `literals[i]` of the
query being run, `Variable(name)` reads a caller value, `Constant` is
compiler-supplied — which is what lets one immutable plan serve them all.
Clients that use `$variables` instead of inlined constants send identical text
every time and hit L1.

**Bind types match column types.** The resolver types a literal from the column
it is compared against, so `'2010-01-01'` binds as a `date` and `'C'` as
`varchar(1)`. Sending them as generic strings would make SQL Server convert the
column per row and lose the index seek.

Physical types are `SqlType`, a sealed hierarchy of records (`VarChar(50)`,
`Decimal(10,2)`, `DateTime2(7)`, ...), not strings. That buys three things: the
only route a type takes into generated SQL is `SqlType.sql()` rather than a
string spliced in from the catalog; `unicode()` lets `Binder` choose
`setNString` vs `setString` per parameter, so the varchar/nvarchar decision no
longer depends on a connection-wide `sendStringParametersAsUnicode` switch (the
demo still sets it, as a backstop); and constructors reject types the server
would reject — `VarChar(9000)`, `Decimal(10,11)`, `DateTime2(8)`.

**List variables bind as one parameter.** `clientId in $clients` has unknown
arity at plan time, so it lowers to
`IN (SELECT [value] FROM OPENJSON(?) WITH ([value] varchar(50) '$'))` — one
parameter whatever the list length, which keeps both this plan cache and SQL
Server's own stable. Inline lists keep `IN (?, ?)` since their arity is part of
the shape. (`OPENJSON` needs database compatibility level 130+, i.e. SQL Server
2016 or later; the 2019 image in `runMsSqlServer.sh` defaults to 150.)

**Window functions stack levels rather than fight T-SQL.** A window function
cannot see the aggregate it reads in the same `SELECT`, and `ROW_NUMBER` cannot
be filtered where it is defined — SQL Server rejects both. So the grouped query
becomes a subquery, the window level computes over its columns, and a per-group
`top ... within` adds one more level for the rank predicate:

```sql
SELECT w.[Country], w.[CounterpartyName], w.[total]
FROM ( SELECT g.[Country], g.[CounterpartyName], g.[total],
              ROW_NUMBER() OVER (PARTITION BY g.[Country] ORDER BY g.[total] DESC) AS [__rank]
       FROM ( SELECT ... SUM(...) AS [total] FROM ... GROUP BY ... ) AS g ) AS w
WHERE w.[__rank] <= ?
ORDER BY [Country] ASC, [total] DESC
```

A parameterised group key adds its own derived table underneath, giving three
levels; that combination is covered by a test.

**Computed group keys go through a derived table.** A key has to appear in both
SELECT and GROUP BY, and T-SQL matches those occurrences syntactically -- so once
the key's constants are parameterised the two copies bind different parameters
and SQL Server rejects the query. The generator emits such a key once inside a
derived table and groups by its alias. Keys with no parameters (a plain column,
or `year(date)`) repeat harmlessly and stay inline. This is the one place where
auto-parameterisation genuinely changes the SQL you would have written by hand.

**Filtered measures return NULL, not 0.** `sum(x) when p` compiles to
`SUM(CASE WHEN p THEN x END)`, so a group matching no rows yields NULL rather
than zero. `count(...) when p` yields 0. Left as-is because it is what SQL means;
wrap in `coalesce` if an API needs zeros.

**The statement mirrors the query.** `TOP` appears only when the TAQL says
`top`, so reading the generated SQL against the source is a one-to-one exercise
with nothing injected behind your back.

That does mean a query without `top` returns everything it matches.
`Resolver.Options.defaultRowLimit` applies a cap to such queries when set —
it defaults to 0, meaning no cap. A REST deployment probably wants it on, with
the caveat that on an `analysis` query with no `top ... by` a cap truncates an
unordered result: a guard rail, not a pagination story.

## Error handling at execution

Compilation already rules out malformed and mistyped queries, so a `SQLException`
from a compiled plan is almost never "bad query" — it is the database reporting
something about the world, and the right response differs per case.
`SqlFailure.classify` sorts them; the codes below were observed by provoking each
condition against SQL Server 2019 / mssql-jdbc 13.4, not read off a reference.

| condition | errorCode | sqlState | classification | retry? |
|---|---|---|---|---|
| deadlock victim | 1205 | 40001 | `RETRYABLE` | yes |
| connection lost | 0 | 08S01 | `RETRYABLE` | yes |
| log/memory exhausted | 9002, 701 | — | `RESOURCE` | after backoff |
| query timeout | 0 | HY008 | `TIMEOUT` | no |
| divide by zero, overflow, bad conversion | 8134, 8115, 241 | — | `INVALID_DATA` | no |
| invalid object / column name | 208, 207 | — | `SCHEMA_MISMATCH` | no |
| permission denied | 229, 230 | — | `PERMISSION` | no |

Three things that came out of measuring rather than assuming:

- **Exception type tells you nothing.** mssql-jdbc throws a plain
  `SQLServerException` for essentially every server error; the JDBC 4 subclasses
  (`SQLDataException`, `SQLSyntaxErrorException`, …) are not used. Only the
  client-side timeout arrived as `SQLTimeoutException`.
- **Neither does the message.** Server messages are localised — the same
  divide-by-zero came back in French on this server. Never match on message
  text, and never make it the API contract.
- **`getErrorCode()` is 0 for driver-side failures.** Timeouts and connection
  losses carry meaning only in `getSQLState()`, whose first two characters *are*
  standard (`08` connection, `40` rollback, `HY008` cancelled). Classification
  needs both.

What `TaqlTemplate` does with that:

- **Bounds every statement** with `queryTimeout`. An endpoint that can hold a
  pooled connection indefinitely eventually exhausts the pool and takes down
  every other endpoint sharing it.
- **Retries only what is worth retrying**, with exponential backoff. Every TAQL
  query is a `SELECT`, so re-running one is side-effect free — which is what
  makes retrying a deadlock victim safe here and not in general. A timeout is
  never retried: it will take just as long again.
- **Splits the response from the log.** `TaqlExecutionException.getMessage()` is
  a fixed, generic string per category; the server's message can quote table
  names, column names and row values — error 245 quotes the offending value
  back — so it reaches neither the response nor the log. `logDetail()` carries
  the category, error number, SQL state and attempt count, all of which are
  value-free; the raw message stays on `databaseMessage()` for a deployment
  that has decided where such a thing may go.

`SCHEMA_MISMATCH` is worth calling out: 207/208 mean the catalog claims something
the database does not have. That is a deployment fault, not a caller fault — a
500 and an alert, never a 400, and no retry will ever fix it.

## Limits

Bounds a deployment sets, none of them part of the language:

| bound | default | where |
|---|---|---|
| query length | 8192 characters | `TaqlParserFacade.Limits` |
| nesting depth | 256 levels | `TaqlParserFacade.Limits`, enforced as the AST is built |
| rows returned | 10 000 | `TaqlTemplate.Options` |
| statement timeout | 30 s | `TaqlTemplate.Options` |

Exceeding one is a `limit` diagnostic, positioned like any other. The nesting
bound is what protects the stack: the resolver, the printer and the SQL
generator all walk the tree recursively, so a tree they could not survive is
never built. The row ceiling fails the query rather than truncating it — an
analytical answer quietly missing rows is worse than an error saying so.

Parsing runs SLL-first with an LL fallback for diagnostics. The left-recursive
`expression` rule is quadratic under full LL — 3200 terms took 43 seconds — and
45 ms under SLL.

## Not done

Deliberate omissions for a POC, roughly in the order I would add them:

- **Pagination.** `TOP` only; no `OFFSET/FETCH` and no keyset cursor.
- **`having`.** Filtering on a measure after aggregation has no syntax yet
  (`top ... within` is the only post-aggregate filter).
- **Window functions in flat queries.** They are analysis-only: their arguments
  are measure and group-key names, so `rank` over ungrouped rows needs its own
  design for what to order by.
- **Authorisation.** The catalog decides which fields exist, but not which
  fields *this caller* may read. Row-level filters (e.g. force `ClientId` to the
  caller's own) belong as a mandatory predicate injected at lowering.
- **Circuit breaking.** Retries are bounded per request but nothing sheds load
  when the database is failing for everyone at once.
- **Cost control.** `TaqlTemplate` caps rows and every statement is bounded by
  a timeout, but nothing stops `count(distinct x)` over an unfiltered table
  before it runs; a required-filter rule per entity, and a cost estimate from
  the plan, would.
- **Multi-entity.** `from` is wired through and the catalog is a map, but only
  one entity is defined and there is no cross-entity join planning.
- **Caffeine** instead of the hand-rolled LRU, for per-entry stats and
  non-blocking reads. `PlanCache` computes outside the lock, so a race compiles
  the same plan twice and discards one — harmless, but not free.
