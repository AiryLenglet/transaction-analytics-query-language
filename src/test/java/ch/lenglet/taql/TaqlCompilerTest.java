package ch.lenglet.taql;

import ch.lenglet.taql.ast.TaqlParser;
import ch.lenglet.taql.cache.PlanCache;
import ch.lenglet.taql.catalog.Catalog;
import ch.lenglet.taql.catalog.DemoCatalog;
import ch.lenglet.taql.sql.SqlType;
import ch.lenglet.taql.plan.Plan;
import ch.lenglet.taql.sem.Resolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaqlCompilerTest {

    private final TaqlCompiler compiler = new TaqlCompiler(DemoCatalog.create());

    // ==================================================================
    @Nested
    @DisplayName("aggregation")
    class Aggregation {

        @Test
        void compilesTheMatchGroupedExample() {
            Plan plan = compiler.compileUncached("""
                    analysis by category = match transactionType {
                        ['Cash', 'Cheques'] -> 'CASH_FLOW'
                        ['Instrument'] -> 'INTERNAL_TRANSFER'
                        _ -> 'OTHER'
                    } {
                        incoming = sum(TransactionValue) when Direction = 'C'
                        outgoing = sum(TransactionValue) when Direction = 'D'
                        count()
                    }
                    over {
                        ClientId in ['1', '3']
                        TransactionDate in '2010-01-01'..'2019-12-31'
                    }
                    """);

            assertAll(
                    () -> assertTrue(plan.statement().contains("CASE WHEN t.[TransactionType] IN (?, ?) THEN ?")),
                    () -> assertTrue(plan.statement().contains("COUNT(*)")),
                    () -> assertTrue(plan.statement().contains("t.[TransactionDate] BETWEEN ? AND ?")),
                    // The key carries parameters, so it is projected once by a
                    // derived table and the measures read it back from there.
                    () -> assertTrue(plan.statement().contains("END AS [category]")),
                    () -> assertTrue(plan.statement().contains("GROUP BY g.[category]")),
                    () -> assertTrue(plan.statement().matches("(?s).*SUM\\(CASE WHEN g\\.\\[c\\d\\] = \\? THEN g\\.\\[c\\d\\] END\\).*")),
                    () -> assertEquals(List.of("category", "incoming", "outgoing", "count"),
                            plan.columns().stream().map(Plan.Column::name).toList()));
        }

        @Test
        void theFlatSchemaNeedsNoJoins() {
            assertFalse(compiler.compileUncached("list { transactionId, country, transactionType }")
                    .statement().contains("JOIN"));
        }

        @Test
        void topByOrdersOnTheNamedMeasure() {
            Plan plan = compiler.compileUncached("""
                    analysis by country { total = sum(TransactionValue) } top 10 by total
                    """);
            assertTrue(plan.statement().contains("ORDER BY [total] DESC"));
        }

        @Test
        void groupsThroughADerivedTableWhenTheKeyCarriesParameters() {
            // Repeating a parameterised key in GROUP BY would make T-SQL see two
            // different expressions (@P1.. vs @P13..) and reject the query.
            Plan plan = compiler.compileUncached("""
                    analysis by bucket = match currency { ['CHF'] -> 'local'  _ -> 'foreign' } {
                        total = sum(TransactionValue) when direction = 'C'
                    }
                    """);
            assertAll(
                    () -> assertTrue(plan.statement().contains("FROM (")),
                    () -> assertTrue(plan.statement().contains("END AS [bucket]")),
                    () -> assertTrue(plan.statement().contains("GROUP BY g.[bucket]")),
                    () -> assertFalse(plan.statement().contains("GROUP BY CASE"), "key must not be repeated"),
                    () -> assertEquals(1, plan.statement().split("CASE WHEN t.\\[Currency\\]", -1).length - 1,
                            "the key expression must be emitted exactly once"));
        }

        @Test
        void groupsInlineWhenTheKeyHasNoParameters() {
            // YEAR(TransactionDate) repeats harmlessly, so no derived table is needed.
            Plan plan = compiler.compileUncached(
                    "analysis by y = year(TransactionDate) { total = sum(TransactionValue) }");
            assertFalse(plan.statement().contains("FROM ("));
            assertTrue(plan.statement().contains("GROUP BY YEAR(t.[TransactionDate])"));
        }

        @Test
        void aMeasureHasToAggregate() {
            // The one thing a grouped query cannot select: a column that is
            // neither a group key nor inside an aggregate. The grammar lets it
            // through so this can name the choice the writer has to make.
            for (String query : List.of(
                    "analysis by Country { TransactionValue }",
                    "analysis by Country { total = TransactionValue }",
                    "analysis by Country { x = TransactionValue * 2 }",
                    "analysis by Country { total = sum(TransactionValue), Currency }")) {
                TaqlException e = assertThrows(TaqlException.class,
                        () -> compiler.compileUncached(query), query);
                Diagnostic first = e.diagnostics().getFirst();
                assertAll(query,
                        () -> assertTrue(first.message().contains("is not aggregated"), first.message()),
                        () -> assertTrue(first.message().contains("group on it"), first.message()),
                        // positioned at the offending measure, not at a stray token
                        () -> assertTrue(first.column() > 0, first.toString()));
            }
        }

        @Test
        void aFunctionThatIsNotAnAggregateIsStillNamedByTheResolver() {
            // The grammar cannot tell sum from upper, and should not try: letting
            // both through is what lets the resolver list what was expected.
            TaqlException e = assertThrows(TaqlException.class,
                    () -> compiler.compileUncached("analysis by Country { x = upper(Country) }"));
            assertEquals(Diagnostic.Phase.RESOLUTION, e.diagnostics().getFirst().phase());
            assertTrue(e.getMessage().contains("'upper' is not an aggregate"), e.getMessage());
            assertTrue(e.getMessage().contains("[avg, count, max, min, sum]"), e.getMessage());
        }

        @Test
        void anAggregateOutsideTheMeasureBlockIsRejected() {
            for (String query : List.of(
                    "analysis by t = sum(TransactionValue) { n = count() }",
                    "analysis by Country, n = count() { total = sum(TransactionValue) }",
                    "analysis by Country { x = sum(sum(TransactionValue)) }",
                    "list { x = sum(TransactionValue) }")) {
                TaqlException e = assertThrows(TaqlException.class,
                        () -> compiler.compileUncached(query), query);
                assertTrue(e.getMessage().contains("is an aggregate and can only appear"),
                        query + " -> " + e.getMessage());
            }
        }

        @Test
        void rejectsTopByAnUnknownMeasure() {
            TaqlException e = assertThrows(TaqlException.class, () -> compiler.compileUncached(
                    "analysis by country { total = sum(TransactionValue) } top 10 by nope"));
            assertTrue(e.getMessage().contains("must name a group key or measure"));
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("window functions")
    class Windows {

        @Test
        void shareDividesByThePartitionTotalAndGuardsAgainstZero() {
            // A zero denominator would raise SQL Server error 8134; NULLIF makes
            // it a NULL result instead of a failed request.
            Plan plan = compiler.compileUncached(
                    "analysis by Country { total = sum(TransactionValue)  pct = share(total) }");
            assertTrue(plan.statement().contains("q.[total] * 1.0 / NULLIF(SUM(q.[total]) OVER (), 0) AS [pct]"),
                    plan.statement());
            assertEquals(TaqlType.DECIMAL, plan.columns().getLast().type());
        }

        @Test
        void shareWithinAPartitionScopesTheDenominator() {
            assertTrue(compiler.compileUncached(
                    "analysis by Country, Currency { total = sum(TransactionValue) "
                            + " pct = share(total) within Country }")
                    .statement().contains("OVER (PARTITION BY q.[Country])"));
        }

        @Test
        void rankOrdersByItsArgumentDescendingSoRankOneIsTheLargest() {
            Plan plan = compiler.compileUncached(
                    "analysis by Country { total = sum(TransactionValue)  rk = rank(total) }");
            assertTrue(plan.statement().contains("RANK() OVER (ORDER BY q.[total] DESC) AS [rk]"), plan.statement());
            assertEquals(TaqlType.INTEGER, plan.columns().getLast().type());
        }

        @Test
        void lagKeepsTheTypeOfWhatItReads() {
            Plan plan = compiler.compileUncached(
                    "analysis by Country, y = year(TransactionDate) {"
                            + "  total = sum(TransactionValue)"
                            + "  prev = lag(total) within Country ordered by y }");
            assertTrue(plan.statement().contains(
                    "LAG(q.[total]) OVER (PARTITION BY q.[Country] ORDER BY q.[y] ASC) AS [prev]"), plan.statement());
            assertEquals(TaqlType.DECIMAL, plan.columns().getLast().type());
        }

        @Test
        void topWithinBecomesARowNumberPredicateNotATopClause() {
            Plan plan = compiler.compileUncached(
                    "analysis by Country, CounterpartyName { total = sum(TransactionValue) }"
                            + " top 2 by total within Country");
            assertAll(
                    () -> assertTrue(plan.statement().contains(
                            "ROW_NUMBER() OVER (PARTITION BY q.[Country] ORDER BY q.[total] DESC)"), plan.statement()),
                    () -> assertTrue(plan.statement().contains("WHERE w.[__rank] <= ?")),
                    // A per-group cap is not a global one.
                    () -> assertFalse(plan.statement().contains("TOP")),
                    () -> assertTrue(plan.statement().contains("ORDER BY [Country] ASC, [total] DESC")));
        }

        @Test
        void aWindowOverAParameterisedGroupKeyStacksAllThreeLevels() {
            // The computed key needs its own derived table; the window needs a
            // level above the GROUP BY. Both at once must nest, not collide.
            Plan plan = compiler.compileUncached(
                    "analysis by b = match Currency { ['CHF'] -> 'local'  _ -> 'foreign' } {"
                            + "  total = sum(TransactionValue)"
                            + "  pct = share(total) }");
            assertAll(
                    () -> assertEquals(2, plan.statement().split("FROM \\(", -1).length - 1, plan.statement()),
                    () -> assertTrue(plan.statement().contains("END AS [b]")),
                    () -> assertTrue(plan.statement().contains("GROUP BY g.[b]")),
                    () -> assertTrue(plan.statement().contains("NULLIF(SUM(q.[total])")));
        }

        @Test
        void aWindowFunctionMustNameAnOutputOfItsOwnQuery() {
            TaqlException e = assertThrows(TaqlException.class, () -> compiler.compileUncached(
                    "analysis by Country { total = sum(TransactionValue)  rk = rank(nope) }"));
            assertTrue(e.getMessage().contains("must name a group key or measure"), e.getMessage());
        }

        @Test
        void aWindowFunctionCannotReadAnotherWindowFunction() {
            // Chaining would need the measures ordered by dependency; one level is enough.
            TaqlException e = assertThrows(TaqlException.class, () -> compiler.compileUncached(
                    "analysis by Country { total = sum(TransactionValue)"
                            + "  pct = share(total)  rk = rank(pct) }"));
            assertTrue(e.getMessage().contains("is itself a window function"), e.getMessage());
        }

        @Test
        void lagWithoutAnOrderingIsRejected() {
            TaqlException e = assertThrows(TaqlException.class, () -> compiler.compileUncached(
                    "analysis by Country { total = sum(TransactionValue)  prev = lag(total) }"));
            assertTrue(e.getMessage().contains("needs an explicit 'ordered by"), e.getMessage());
        }

        @Test
        void orderingAShareIsMeaningless() {
            TaqlException e = assertThrows(TaqlException.class, () -> compiler.compileUncached(
                    "analysis by Country, Currency { total = sum(TransactionValue)"
                            + "  pct = share(total) ordered by Currency }"));
            assertTrue(e.getMessage().contains("has no ordering"), e.getMessage());
        }

        @Test
        void aWindowFunctionCannotTakeAWhenFilter() {
            TaqlException e = assertThrows(TaqlException.class, () -> compiler.compileUncached(
                    "analysis by Country { total = sum(TransactionValue)"
                            + "  rk = rank(total) when Direction = 'C' }"));
            assertTrue(e.getMessage().contains("cannot take a 'when' filter"), e.getMessage());
        }

        @Test
        void withinNeedsSomethingToRankOn() {
            TaqlException e = assertThrows(TaqlException.class, () -> compiler.compileUncached(
                    "analysis by Country { total = sum(TransactionValue) } top 2 within Country"));
            assertTrue(e.getMessage().contains("also needs 'by <measure>'"), e.getMessage());
        }

        @Test
        void withinIsNotValidOnAFlatQuery() {
            TaqlException e = assertThrows(TaqlException.class, () -> compiler.compileUncached(
                    "list { TransactionId } top 2 within Country"));
            assertTrue(e.getMessage().contains("only valid on an analysis query"), e.getMessage());
        }

        @Test
        void theWindowSpecIsPartOfTheShapeKey() {
            Plan global = compiler.compileUncached(
                    "analysis by Country, Currency { t = sum(TransactionValue)  p = share(t) }");
            Plan scoped = compiler.compileUncached(
                    "analysis by Country, Currency { t = sum(TransactionValue)  p = share(t) within Country }");
            assertFalse(global.shapeKey().equals(scoped.shapeKey()));
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("flat queries")
    class Flat {

        @Test
        void compilesProjectionsFilterSortAndLimit() {
            Plan plan = compiler.compileUncached("""
                    list { transactionId, TransactionValue, country }
                    over { clientId in ['1','3'], TransactionValue > 100 }
                    sort by TransactionValue desc
                    top 5
                    """);
            assertAll(
                    () -> assertTrue(plan.statement().startsWith("SELECT TOP (?)")),
                    () -> assertTrue(plan.statement().contains("t.[ClientId] IN (?, ?)")),
                    () -> assertTrue(plan.statement().contains("ORDER BY [TransactionValue] DESC")),
                    () -> assertFalse(plan.statement().contains("GROUP BY")));
        }

        @Test
        void omitsTopEntirelyWhenTheQueryDoesNotAskForOne() {
            Plan plan = compiler.compileUncached("list { transactionId }");
            assertAll(
                    () -> assertFalse(plan.statement().contains("TOP"), plan.statement()),
                    () -> assertTrue(plan.statement().startsWith("SELECT\n")),
                    () -> assertTrue(plan.parameters().isEmpty()));
        }

        @Test
        void appliesAConfiguredRowCapWhenOneIsSet() {
            // Off by default so the SQL mirrors the TAQL; a deployment that does
            // not want unbounded results opts in here.
            TaqlCompiler capped = new TaqlCompiler(DemoCatalog.create(),
                    new Resolver.Options(1000), 16);
            Plan plan = capped.compileUncached("list { transactionId }");
            assertTrue(plan.statement().startsWith("SELECT TOP (?)"));
            assertEquals(1000L, ((Plan.Constant) plan.parameters().getFirst()).value());
        }

        @Test
        void rejectsAggregatesOutsideAnAnalysisBlock() {
            TaqlException e = assertThrows(TaqlException.class,
                    () -> compiler.compileUncached("list { x = sum(TransactionValue) }"));
            assertTrue(e.getMessage().contains("only appear in an analysis measure block"));
        }
    }


    // ==================================================================
    @Nested
    @DisplayName("binding")
    class Binding {

        private List<Object> bind(String query) {
            return compiler.compile(query).bind();
        }

        private TaqlException rejected(String query) {
            return assertThrows(TaqlException.class, () -> bind(query));
        }

        @Test
        void aConstantIsBoundAsTheTypeOfWhatItIsComparedAgainst() {
            // '2010-01-01' next to a date column is a date, not text -- which is
            // the difference between seeking an index and converting every row.
            assertEquals(List.of(java.time.LocalDate.of(2010, 1, 1)),
                    bind("list { TransactionId } over { TransactionDate > '2010-01-01' }"));
            assertEquals(List.of(new java.math.BigDecimal("500")),
                    bind("list { TransactionId } over { TransactionValue > 500 }"));
            assertEquals(List.of("CH"),
                    bind("list { TransactionId } over { Country = 'CH' }"));
        }

        @Test
        void aConstantThatCannotBecomeTheColumnsTypeIsRejected() {
            // The resolver lets text stand where a date or a number is wanted --
            // that is how '2010-01-01' works at all -- so the text has to parse.
            assertTrue(rejected("list { TransactionId } over { TransactionDate > '31/12/2019' }")
                    .getMessage().contains("a date like"));
            assertTrue(rejected("list { TransactionId } over { TransactionValue > 'abc' }")
                    .getMessage().contains("a number"));
        }

        @Test
        void aDecimalKeepsThePrecisionItWasWrittenWith() {
            assertEquals(List.of(new java.math.BigDecimal("0.1")),
                    bind("list { TransactionId } over { TransactionValue > 0.1 }"));
        }

        @Test
        void aRejectionNamesTheValueButNotWhereItCameFrom() {
            // The message is returned to the caller, so it says what was wrong
            // with the constant and nothing about the row it would have matched.
            String message = rejected("list { TransactionId } over { TransactionDate > '31/12/2019' }")
                    .getMessage();
            assertTrue(message.contains("31/12/2019"), message);
            assertFalse(message.contains("TransactionDate"), message);
        }
    }


    // ==================================================================
    @Nested
    @DisplayName("limits")
    class Limits {

        @Test
        void rejectsAnOversizedQueryBeforeParsingIt() {
            String huge = "list { TransactionId } over { Country = '" + "a".repeat(9000) + "' }";
            var rejected = assertThrows(TaqlException.class, () -> compiler.compile(huge));
            assertEquals(Diagnostic.Phase.LIMIT, rejected.diagnostics().getFirst().phase());
            assertTrue(rejected.getMessage().contains("the limit is 8192"), rejected.getMessage());
        }

        @Test
        void rejectsAnExpressionThatNestsTooDeeply() {
            // Every later pass walks this tree recursively, so a tree they could
            // not survive must not be built. Before the bound, this was a
            // StackOverflowError -- an Error, straight past every handler.
            String deep = "list { x = " + "1+".repeat(4000) + "1 }";
            var rejected = assertThrows(TaqlException.class, () -> compiler.compile(deep));
            assertEquals(Diagnostic.Phase.LIMIT, rejected.diagnostics().getFirst().phase());
            assertTrue(rejected.getMessage().contains("nests more than 256"), rejected.getMessage());
        }

        @Test
        void rejectsDeeplyNestedPredicatesToo() {
            String deep = "list { TransactionId } over { " + "not ".repeat(400) + "Country = 'CH' }";
            var rejected = assertThrows(TaqlException.class, () -> compiler.compile(deep));
            assertEquals(Diagnostic.Phase.LIMIT, rejected.diagnostics().getFirst().phase());
        }

        @Test
        void rejectsAnIntegerLiteralTooLargeForALong() {
            // The lexer accepts [0-9]+, which is wider than a long. This used to
            // escape as NumberFormatException and become a 500.
            for (String query : List.of(
                    "list { TransactionId } over { TransactionValue > 99999999999999999999 }",
                    "list { TransactionId } top 99999999999999999999")) {
                var rejected = assertThrows(TaqlException.class, () -> compiler.compile(query), query);
                assertTrue(rejected.getMessage().contains("is too large"), rejected.getMessage());
            }
        }

        @Test
        void aDeploymentCanTightenTheLimits() {
            // The point of the parser being an object: these are a deployment's
            // call, and while parsing was static they were not reachable at all.
            TaqlCompiler strict = new TaqlCompiler(DemoCatalog.create(),
                    new ch.lenglet.taql.sql.SqlServerGenerator(),
                    new TaqlParser(new TaqlParser.Limits(64, 128, 4)),
                    Resolver.Options.DEFAULTS,
                    new ch.lenglet.taql.cache.LruPlanCache<>(16));

            // Comfortably legal by default, too long here.
            String longer = "list { TransactionId } over { Country = 'CH', Currency = 'CHF' }";
            assertEquals(64, longer.length());
            compiler.compile(longer);
            var tooLong = assertThrows(TaqlException.class, () -> strict.compile(longer + " "));
            assertTrue(tooLong.getMessage().contains("the limit is 64"), tooLong.getMessage());

            // Same for depth: five levels of nesting passes by default, not here.
            String nested = "list { x = ((((1)))) + 1 }";
            compiler.compile(nested);
            var tooDeep = assertThrows(TaqlException.class, () -> strict.compile(nested));
            assertTrue(tooDeep.getMessage().contains("nests more than 4"), tooDeep.getMessage());
        }

        @Test
        void anOutputNameLongerThanTheStoreAllowsIsRefusedHere() {
            // An alias is the only caller text that reaches the statement as an
            // identifier rather than as a parameter, so it is the only one whose
            // length the store cares about. Left to the database it comes back
            // as error 103, which no rule classifies, so it reaches the caller
            // as a 500 for a mistake that was theirs.
            String longest = "a".repeat(128);
            compiler.compileUncached("list { `" + longest + "` = TransactionId }");

            TaqlException e = assertThrows(TaqlException.class, () ->
                    compiler.compileUncached("list { `" + longest + "a` = TransactionId }"));
            assertTrue(e.getMessage().contains("is 129 characters"), e.getMessage());
            assertTrue(e.getMessage().contains("the limit is 128"), e.getMessage());
            assertTrue(e.diagnostics().getFirst().column() > 0, e.toString());
        }

        @Test
        void everyKindOfOutputNameIsChecked() {
            String tooLong = "a".repeat(200);
            for (String query : List.of(
                    "list { `" + tooLong + "` = TransactionId }",
                    "analysis by `" + tooLong + "` = Country { n = count() }",
                    "analysis by Country { `" + tooLong + "` = sum(TransactionValue) }")) {
                TaqlException e = assertThrows(TaqlException.class,
                        () -> compiler.compileUncached(query), query);
                assertTrue(e.getMessage().contains("the limit is 128"), query + " -> " + e.getMessage());
            }
        }

        @Test
        void breadthIsNotDepthSoAWideQueryStillCompiles() {
            // 1000 list items nest one level, however long the text: the bound is
            // on nesting, not on size of the query's answer.
            String wide = "list { TransactionId } over { Country in ["
                    + String.join(",", java.util.Collections.nCopies(1000, "'CH'")) + "] }";
            assertEquals(1000, compiler.compile(wide).plan().parameters().size());
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("plan cache")
    class Cache {

        @Test
        void queriesDifferingOnlyInConstantsShareOnePlan() {
            Plan a = compiler.compile("list { transactionId } over { clientId in ['1','3'] }").plan();
            Plan b = compiler.compile("list { transactionId } over { clientId in ['7','9'] }").plan();
            assertAll(
                    () -> assertSame(a, b, "same shape must reuse the same Plan instance"),
                    () -> assertEquals(a.shapeKey(), b.shapeKey()),
                    () -> assertEquals(1, compiler.plans().stats().size()));
        }

        @Test
        void literalValuesTravelWithTheQueryNotThePlan() {
            var first = compiler.compile("list { transactionId } over { clientId = '1' }");
            var second = compiler.compile("list { transactionId } over { clientId = '999' }");
            assertSame(first.plan(), second.plan());
            assertEquals("1", first.bind().getFirst());
            assertEquals("999", second.bind().getFirst());
        }

        @Test
        void listArityIsPartOfTheShapeBecauseItChangesTheSql() {
            Plan two = compiler.compile("list { transactionId } over { clientId in ['1','2'] }").plan();
            Plan three = compiler.compile("list { transactionId } over { clientId in ['1','2','3'] }").plan();
            assertFalse(two.shapeKey().equals(three.shapeKey()));
            assertTrue(two.statement().contains("IN (?, ?)"));
            assertTrue(three.statement().contains("IN (?, ?, ?)"));
        }

        @Test
        void literalOrderIsStableAcrossFormattingSoSlotIndicesStayValid() {
            // Plan.Auto slots index into the *calling* query's literal table, so
            // two texts sharing a plan must lift their literals in the same order.
            String a = "list { transactionId } over { clientId in ['1','2'], direction = 'C', TransactionValue > 10 }";
            String b = """
                    list {
                        transactionId   // same shape, different layout and values
                    }
                    over {
                        clientId in ['8','9']
                        direction = 'D'
                        TransactionValue > 99
                    }
                    """;
            var first = compiler.compile(a);
            var second = compiler.compile(b);
            assertSame(first.plan(), second.plan());
            assertEquals(List.of("1", "2", "C", new java.math.BigDecimal("10")), first.bind());
            assertEquals(List.of("8", "9", "D", new java.math.BigDecimal("99")), second.bind());
        }

        @Test
        void formattingAndCommentsDoNotCreateNewPlans() {
            compiler.compile("list { transactionId } over { clientId = '1' }");
            compiler.compile("""
                    // a comment
                    list {
                        transactionId
                    }
                    over {   clientId  =  '1'   }
                    """);
            assertEquals(1, compiler.plans().stats().size());
        }

        @Test
        void aRepeatedQueryStillParsesAndStillReusesItsPlan() {
            // There is no cache on the text: parsing is the cheap phase, and a
            // cache keyed on it would hold the caller's constants to almost no
            // purpose -- constants are what change between two calls.
            String source = "list { transactionId } over { clientId = '1' }";
            var first = compiler.compile(source);
            var second = compiler.compile(source);

            assertNotSame(first, second, "nothing caches the compiled query itself");
            assertSame(first.plan(), second.plan(), "but the plan is reused");
            assertEquals(1, compiler.plans().stats().hits());
        }

        @Test
        void clausesMustBeWrittenInTheCanonicalOrder() {
            // One way to write a query, so there is one slot numbering. 'from' and
            // 'over' name the population; 'sort by' and 'top' present it.
            var wrong = assertThrows(TaqlException.class, () -> compiler.compile(
                    "list { TransactionId } top 5 over { TransactionValue > 500 }"));
            assertTrue(wrong.getMessage().contains("'over' must come before 'top'"), wrong.getMessage());
            assertTrue(wrong.getMessage().contains("sort by"), wrong.getMessage());

            var analysis = assertThrows(TaqlException.class, () -> compiler.compile(
                    "analysis by Country { total = sum(TransactionValue) }"
                            + " top 3 by total over { TransactionValue > 500 }"));
            assertTrue(analysis.getMessage().contains("'over' must come before 'top'"), analysis.getMessage());

            var sorted = assertThrows(TaqlException.class, () -> compiler.compile(
                    "list { TransactionId } top 5 sort by TransactionId"));
            assertTrue(sorted.getMessage().contains("'sort by' must come before 'top'"), sorted.getMessage());
        }

        @Test
        void theCanonicalOrderNumbersLiteralsInTheOrderThePlanBindsThem() {
            // Plan.Auto indexes the *calling* query's literal table, so slot order
            // has to be canonical. It is, because only one order compiles -- but
            // AstBuilder still builds canonically rather than as-written, so
            // relaxing the rule cannot silently reintroduce a mis-binding.
            var compiled = compiler.compile(
                    "list { TransactionId } over { TransactionValue > 500 } sort by TransactionId top 5");
            // TOP is emitted before WHERE, so that is the parameter order.
            assertEquals(List.of(5L, new java.math.BigDecimal("500")), compiled.bind());

            var other = compiler.compile(
                    "list { TransactionId } over { TransactionValue > 20 } sort by TransactionId top 7");
            assertSame(compiled.plan(), other.plan());
            assertEquals(List.of(7L, new java.math.BigDecimal("20")), other.bind());
        }

        @Test
        void theShapeKeyCarriesTheSlotSoAnyNumberingDriftCostsAPlanNotCorrectness() {
            // Rendering the slot index makes the key self-checking: if AstBuilder
            // and AstPrinter ever disagreed on order, the keys would differ and
            // the queries would compile separate plans instead of one binding the
            // other's values.
            String key = compiler.compile(
                    "list { TransactionId } over { TransactionValue > 500 } top 5").plan().shapeKey();
            assertTrue(key.contains("#0"), key);
            assertTrue(key.contains("#1"), key);
        }

        @Test
        void aCompilerWorksWithACacheThatNeverCaches() {
            // Caching is an optimisation, not part of the semantics: swapping in
            // a cache that stores nothing must change speed and nothing else.
            // Worth pinning, because the one serious bug this codebase had was a
            // cache handing one query another query's values.
            TaqlCompiler uncached = new TaqlCompiler(DemoCatalog.create(),
                    new ch.lenglet.taql.sql.SqlServerGenerator(),
                    new ch.lenglet.taql.ast.TaqlParser(), Resolver.Options.DEFAULTS,
                    new NeverCaches<>());

            String query = "list { TransactionId } over { TransactionValue > 500 } top 5";
            var first = uncached.compile(query);
            var second = uncached.compile(query);

            assertAll(
                    () -> assertEquals(List.of(5L, new java.math.BigDecimal("500")), first.bind()),
                    () -> assertEquals(first.bind(), second.bind()),
                    () -> assertEquals(first.plan().statement(), second.plan().statement()),
                    // ...and it really did compile twice
                    () -> assertNotSame(first.plan(), second.plan()));
        }

        @Test
        void theDefaultCacheReportsWhatItDid() {
            compiler.compile("list { transactionId } over { clientId = '1' }");
            compiler.compile("list { transactionId } over { clientId = '1' }");
            PlanCache.Stats stats = compiler.plans().stats();
            assertAll(
                    () -> assertEquals(1, stats.hits()),
                    () -> assertEquals(1, stats.misses()),
                    () -> assertEquals(1, stats.size()),
                    () -> assertEquals(0.5, stats.hitRate()));
        }

        /** Correct, useless, and enough to prove the seam is real. */
        private static final class NeverCaches<K, V> implements PlanCache<K, V> {
            @Override public V get(K key, java.util.function.Function<K, V> compute) {
                return compute.apply(key);
            }
            @Override public void clear() { }
            @Override public Stats stats() { return new Stats(0, 0, 0); }
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("injection resistance")
    class Injection {

        @Test
        void hostileValuesNeverReachTheSqlText() {
            String payload = "1'; DROP TABLE dbo.Transactions; --";
            var compiled = compiler.compile(
                    "list { transactionId } over { clientId = '1''; DROP TABLE dbo.Transactions; --' }");
            assertAll(
                    () -> assertFalse(compiled.plan().statement().contains("DROP")),
                    () -> assertFalse(compiled.plan().statement().contains("--")),
                    () -> assertEquals("t.[ClientId] = ?",
                            compiled.plan().statement().lines()
                                    .filter(l -> l.startsWith("WHERE"))
                                    .findFirst().orElseThrow().substring("WHERE ".length())),
                    () -> assertEquals(payload, compiled.bind().getFirst()));
        }

        @Test
        void aHostileValueInsideAListIsStillJustAValue() {
            var compiled = compiler.compile(
                    "list { transactionId } over { clientId in ['ok', 'a''; DROP TABLE x; --'] }");
            assertFalse(compiled.plan().statement().contains("DROP"));
            assertTrue(compiled.plan().statement().contains("IN (?, ?)"), compiled.plan().statement());
            assertEquals(List.of("ok", "a'; DROP TABLE x; --"), compiled.bind());
        }

        @Test
        void identifiersMustResolveInTheCatalog() {
            TaqlException e = assertThrows(TaqlException.class,
                    () -> compiler.compileUncached("list { transactionId } over { Password = 'x' }"));
            assertEquals(Diagnostic.Phase.RESOLUTION, e.diagnostics().getFirst().phase());
            assertTrue(e.getMessage().contains("unknown field 'Password'"));
        }

        @Test
        void unknownFunctionsAreRejected() {
            assertThrows(TaqlException.class,
                    () -> compiler.compileUncached("list { x = xp_cmdshell(transactionId) }"));
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("SQL types")
    class SqlTypes {

        @Test
        void rendersTheTsqlSpelling() {
            assertAll(
                    () -> assertEquals("varchar(50)", new SqlType.VarChar(50).sql()),
                    () -> assertEquals("varchar(max)", new SqlType.VarChar(SqlType.MAX).sql()),
                    () -> assertEquals("nvarchar(200)", new SqlType.NVarChar(200).sql()),
                    () -> assertEquals("decimal(10,2)", new SqlType.Decimal(10, 2).sql()),
                    () -> assertEquals("datetime2(7)", new SqlType.DateTime2(7).sql()),
                    () -> assertEquals("date", new SqlType.Date().sql()),
                    () -> assertEquals("bit", new SqlType.Bit().sql()));
        }

        @Test
        void rejectsTypesTheServerWouldReject() {
            assertAll(
                    () -> assertThrows(IllegalArgumentException.class, () -> new SqlType.VarChar(0)),
                    () -> assertThrows(IllegalArgumentException.class, () -> new SqlType.VarChar(9000)),
                    // char has no (max) form
                    () -> assertThrows(IllegalArgumentException.class, () -> new SqlType.Char(SqlType.MAX)),
                    () -> assertThrows(IllegalArgumentException.class, () -> new SqlType.Decimal(10, 11)),
                    () -> assertThrows(IllegalArgumentException.class, () -> new SqlType.Decimal(39, 0)),
                    () -> assertThrows(IllegalArgumentException.class, () -> new SqlType.DateTime2(8)));
        }

        @Test
        void onlyTheNationalTypesAreUnicode() {
            assertAll(
                    () -> assertTrue(new SqlType.NVarChar(50).unicode()),
                    () -> assertTrue(new SqlType.NChar(50).unicode()),
                    () -> assertFalse(new SqlType.VarChar(50).unicode()),
                    () -> assertFalse(new SqlType.Date().unicode()));
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("catalog joins")
    class Joins {

        /**
         * The demo schema is a single table, so nothing there exercises the
         * catalog's join support. This fixture keeps that path honest: a field
         * living on a joined table must pull its join into the plan, and only
         * when something actually reads it.
         */
        private final TaqlCompiler joined = new TaqlCompiler(new Catalog(Map.of("orders",
                new Catalog.Entity(
                        "orders",
                        new Catalog.Table("dbo", "Orders"),
                        List.of(Catalog.Join.inner("customer",
                                new Catalog.Table("dbo", "Customers"), "CustomerId", "CustomerId")),
                        List.of(
                                Catalog.Field.of("orderId", TaqlType.STRING, "OrderId", new SqlType.VarChar(50)),
                                Catalog.Field.from("customer", "customerName", TaqlType.STRING, "Name",
                                        new SqlType.VarChar(200)))))));

        @Test
        void readingAJoinedFieldPullsInItsJoin() {
            assertTrue(joined.compileUncached("list { orderId, customerName } from orders")
                    .statement().contains("INNER JOIN [dbo].[Customers] AS c ON o.[CustomerId] = c.[CustomerId]"));
        }

        @Test
        void aQueryThatNeverReadsTheJoinDoesNotEmitIt() {
            assertFalse(joined.compileUncached("list { orderId } from orders").statement().contains("JOIN"));
        }

        @Test
        void aJoinedFieldUsedOnlyInAFilterStillPullsInItsJoin() {
            assertTrue(joined.compileUncached("list { orderId } from orders over { customerName = 'x' }")
                    .statement().contains("INNER JOIN [dbo].[Customers]"));
        }

        @Test
        void aTableKeepsTheSameAliasAcrossQueries() {
            // Aliases are allocated per statement, but from the entity alone --
            // so they do not drift with which joins a given query happens to need.
            assertTrue(joined.compileUncached("list { orderId } from orders").statement().contains("AS o"));
            assertTrue(joined.compileUncached("list { orderId, customerName } from orders")
                    .statement().contains("[dbo].[Orders] AS o"));
        }

        @Test
        void aliasesAreDisambiguatedAndAvoidTheGeneratorsOwnLevelNames() {
            // Customers and Contracts both want 'c'; Groups wants the name the
            // derived table uses.
            TaqlCompiler c = new TaqlCompiler(new Catalog(Map.of("orders", new Catalog.Entity(
                    "orders",
                    new Catalog.Table("dbo", "Groups"),
                    List.of(Catalog.Join.inner("customer", new Catalog.Table("dbo", "Customers"), "Id", "Id"),
                            Catalog.Join.left("contract", new Catalog.Table("dbo", "Contracts"), "Id", "Id")),
                    List.of(Catalog.Field.of("id", TaqlType.STRING, "Id", new SqlType.VarChar(50)),
                            Catalog.Field.from("customer", "customerName", TaqlType.STRING, "Name",
                                    new SqlType.VarChar(50)),
                            Catalog.Field.from("contract", "contractRef", TaqlType.STRING, "Ref",
                                    new SqlType.VarChar(50)))))));

            String sql = c.compileUncached("list { id, customerName, contractRef } from orders").statement();
            assertAll(
                    () -> assertTrue(sql.contains("[dbo].[Groups] AS g2"), sql),
                    () -> assertTrue(sql.contains("INNER JOIN [dbo].[Customers] AS c ON g2.[Id] = c.[Id]"), sql),
                    () -> assertTrue(sql.contains("LEFT JOIN [dbo].[Contracts] AS c2 ON g2.[Id] = c2.[Id]"), sql));
        }

        @Test
        void noTableTakesTheNameOfAGeneratorLevelEvenWhenAllThreeNest() {
            // The derived, window and rank levels are 'g', 'q' and 'w'. A Wires
            // table used to be handed 'w' and end up nested inside the rank
            // level of the same name -- legal, because the scopes never overlap,
            // but only by accident.
            TaqlCompiler c = new TaqlCompiler(new Catalog(Map.of("wires", new Catalog.Entity(
                    "wires", new Catalog.Table("dbo", "Wires"), List.of(),
                    List.of(Catalog.Field.of("Country", TaqlType.STRING, new SqlType.VarChar(2)),
                            Catalog.Field.of("Name", TaqlType.STRING, new SqlType.VarChar(50)),
                            Catalog.Field.of("Amount", TaqlType.DECIMAL, new SqlType.Decimal(10, 2)))))));

            String sql = c.compileUncached("analysis by Country, Name { total = sum(Amount) }"
                    + " from wires top 2 by total within Country").statement();

            assertAll(
                    // the table is pushed off 'w', which the rank level holds
                    () -> assertTrue(sql.contains("[dbo].[Wires] AS w2"), sql),
                    () -> assertTrue(sql.contains("w2.[Country]"), sql),
                    // no group key carries a parameter here, so there is no
                    // derived level -- just the grouped level and the rank one,
                    // each with its own name rather than nesting inside itself
                    () -> assertEquals(1, countOf(sql, ") AS q"), sql),
                    () -> assertEquals(1, countOf(sql, ") AS w"), sql),
                    () -> assertEquals(0, countOf(sql, ") AS g"), sql));
        }

        private static int countOf(String haystack, String needle) {
            int count = 0;
            for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) count++;
            return count;
        }

        @Test
        void aFieldPointingAtAMissingJoinFailsWhereTheCatalogIsWritten() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
                    new Catalog.Entity("orders", new Catalog.Table("dbo", "Orders"), List.of(),
                            List.of(Catalog.Field.from("nope", "x", TaqlType.STRING, "X",
                                    new SqlType.VarChar(10)))));
            assertTrue(e.getMessage().contains("unknown join 'nope'"));
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("diagnostics")
    class Diagnostics {

        @Test
        void syntaxErrorsCarryAPosition() {
            TaqlException e = assertThrows(TaqlException.class,
                    () -> compiler.compileUncached("list { transactionId } over { clientId = }"));
            Diagnostic first = e.diagnostics().getFirst();
            assertEquals(Diagnostic.Phase.SYNTAX, first.phase());
            assertEquals(1, first.line());
            assertTrue(first.column() > 0);
        }

        @Test
        void everySyntaxErrorIsReportedNotJustTheFirst() {
            // Parsing is SLL-first for speed and falls back to LL on failure;
            // the fallback is what collects diagnostics, so this guards the
            // fallback actually running rather than the bail escaping.
            TaqlException e = assertThrows(TaqlException.class, () -> compiler.compileUncached(
                    "list { TransactionId } over { Country = , Currency = }"));
            assertEquals(2, e.diagnostics().size(), e.getMessage());
            assertTrue(e.diagnostics().stream().allMatch(d -> d.phase() == Diagnostic.Phase.SYNTAX));
        }

        @Test
        void typeErrorsExplainTheMismatch() {
            TaqlException e = assertThrows(TaqlException.class,
                    () -> compiler.compileUncached("analysis by country { bad = sum(currency) }"));
            assertTrue(e.getMessage().contains("sum() needs a numeric argument"));
        }

        @Test
        void duplicateOutputNamesAreRejected() {
            TaqlException e = assertThrows(TaqlException.class,
                    () -> compiler.compileUncached("list { a = clientId, a = currency }"));
            assertTrue(e.getMessage().contains("duplicate output name 'a'"));
        }
    }
}
