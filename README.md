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

Run `mvn compile exec:java` to compile every query in `src/main/resources/example.taql`
and print its SQL and bindings. Start `./runMsSqlServer.sh` first and it also executes them.

## Language

### Aggregation

```
analysis by <keys> { <measures> } [top N by <measure>] [over { <filters> }]
```

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

## Design notes

**Values are never text.** `AstBuilder` lifts every literal out of the tree into
a side table and leaves a slot index behind. By the time the SQL generator runs,
the characters the user typed are not reachable from the tree it is walking — so
there is no code path that could concatenate them into a statement, rather than
a discipline that has to be maintained. Identifiers get the same treatment from
the other side: a name that does not resolve to a catalog field is a compile
error, so no user-supplied text is ever emitted as an identifier either.

**The catalog is the semantic layer.** The DSL says `date` and `amount` where the
columns are `TransactionDate` and `TransactionValue`, and it is the catalog that
decides which names exist at all. The schema is a single table, so no query
currently emits a join -- but `Catalog.Join` is wired through the resolver and
generator, and resolving a field that lives on a joined table is what pulls that
join into the plan. Since the demo schema no longer exercises it, that path is
covered by a test-only fixture (`TaqlCompilerTest.Joins`).

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
column per row and lose the index seek. For the same reason the demo sets
`sendStringParametersAsUnicode=false`.

**List variables bind as one parameter.** `clientId in $clients` has unknown
arity at plan time, so it lowers to
`IN (SELECT [value] FROM OPENJSON(?) WITH ([value] varchar(50) '$'))` — one
parameter whatever the list length, which keeps both this plan cache and SQL
Server's own stable. Inline lists keep `IN (?, ?)` since their arity is part of
the shape. (`OPENJSON` needs database compatibility level 130+, i.e. SQL Server
2016 or later; the 2019 image in `runMsSqlServer.sh` defaults to 150.)

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

**Every query has a row cap.** An absent `top` becomes a compiler-supplied
constant (`Resolver.Options.defaultRowLimit`, 1000). Note this applies to
`analysis` too, where without a `top ... by` the cap truncates an unordered
result — a guard rail, not a pagination story.

## Not done

Deliberate omissions for a POC, roughly in the order I would add them:

- **Pagination.** `TOP` only; no `OFFSET/FETCH` and no keyset cursor.
- **`having`.** Filtering on a measure after aggregation has no syntax yet.
- **Authorisation.** The catalog decides which fields exist, but not which
  fields *this caller* may read. Row-level filters (e.g. force `ClientId` to the
  caller's own) belong as a mandatory predicate injected at lowering.
- **Cost control beyond the row cap.** Nothing stops `count(distinct x)` over an
  unfiltered table; a required-filter rule per entity would.
- **Multi-entity.** `from` is wired through and the catalog is a map, but only
  one entity is defined and there is no cross-entity join planning.
- **Caffeine** instead of the hand-rolled LRU, for per-entry stats and
  non-blocking reads. `PlanCache` computes outside the lock, so a race compiles
  the same plan twice and discards one — harmless, but not free.
