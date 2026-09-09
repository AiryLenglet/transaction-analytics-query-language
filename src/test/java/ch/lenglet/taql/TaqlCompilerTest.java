package ch.lenglet.taql;

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
                    new Resolver.Options(1000), 16, 16);
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
    @DisplayName("variables and binding")
    class Variables {

        @Test
        void infersVariableTypesFromTheirUseSite() {
            Plan plan = compiler.compileUncached("""
                    list { transactionId }
                    over { clientId in $clients, TransactionDate in $from..$to }
                    top $limit
                    """);
            assertAll(
                    () -> assertEquals(TaqlType.listOf(TaqlType.STRING), plan.variables().get("clients")),
                    () -> assertEquals(TaqlType.DATE, plan.variables().get("from")),
                    () -> assertEquals(TaqlType.DATE, plan.variables().get("to")),
                    () -> assertEquals(TaqlType.INTEGER, plan.variables().get("limit")));
        }

        @Test
        void bindsAListVariableAsOneJsonParameterSoTheSqlStaysStable() {
            String source = "list { transactionId } over { clientId in $clients }";
            Plan plan = compiler.compile(source).plan();
            assertTrue(plan.statement().contains("OPENJSON(?) WITH ([value] varchar(50) '$')"));

            List<Object> two = compiler.compile(source).bind(Map.of("clients", List.of("1", "3")));
            List<Object> five = compiler.compile(source).bind(
                    Map.of("clients", List.of("1", "2", "3", "4", "5")));
            assertEquals("[\"1\",\"3\"]", two.getFirst());
            assertEquals("[\"1\",\"2\",\"3\",\"4\",\"5\"]", five.getFirst());
            assertEquals(two.size(), five.size(), "arity must not change the parameter count");
        }

        @Test
        void convertsLiteralsToTheColumnType() {
            List<Object> values = compiler.compile(
                    "list { transactionId } over { TransactionDate in '2010-01-01'..'2019-12-31' }").bind();
            assertEquals(java.time.LocalDate.of(2010, 1, 1), values.get(0));
            assertEquals(java.time.LocalDate.of(2019, 12, 31), values.get(1));
        }

        @Test
        void bindsAgainstTheColumnsPhysicalTypeNotAGenericOne() {
            Plan plan = compiler.compileUncached("list { transactionId } over { direction = 'C' }");
            Plan.Auto slot = (Plan.Auto) plan.parameters().getFirst();
            assertEquals(new SqlType.VarChar(1), slot.physicalType());
        }

        @Test
        void theVariableContractKeepsAStableOrder() {
            // plan.variables() is published as the endpoint's schema, so a
            // generated document must not reshuffle its own properties between
            // restarts. Map.copyOf randomises iteration order per JVM, so this
            // asserts the order itself -- checking only that two calls agree
            // would pass in a single JVM even when it is randomised.
            Plan plan = compiler.compileUncached("""
                    analysis by country { total = sum(TransactionValue) }
                    over { clientId in $clients, TransactionDate in $from..$to }
                    top $limit by total
                    """);
            assertEquals(List.of("clients", "from", "to", "limit"),
                    List.copyOf(plan.variables().keySet()),
                    "variables should be listed in order of first use");
        }

        @Test
        void reportsAMissingVariable() {
            TaqlException e = assertThrows(TaqlException.class,
                    () -> compiler.compile("list { transactionId } over { clientId = $who }").bind());
            assertTrue(e.getMessage().contains("missing value for query variable $who"));
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("binding")
    class Binding {

        private List<Object> bind(String query, String name, Object value) {
            return compiler.compile(query).bind(java.util.Collections.singletonMap(name, value));
        }

        private TaqlException rejected(String query, String name, Object value) {
            return assertThrows(TaqlException.class, () -> bind(query, name, value));
        }

        private static final String STRING_VAR = "list { TransactionId } over { Country = $c }";
        private static final String INT_VAR = "list { TransactionId } top $n";
        private static final String DATE_VAR = "list { TransactionId } over { TransactionDate > $d }";

        @Test
        void aValueThatIsNotTheAdvertisedTypeIsRejectedNotRendered() {
            // Plan.variables() publishes $c as a string. toString() would accept
            // all of these and bind their rendering -- parameterised, so not
            // injectable, but silently asking a question nobody meant to ask.
            for (Object wrong : List.of(42, List.of("a", "b"), Map.of("k", "v"), new int[]{1, 2}, true)) {
                TaqlException e = rejected(STRING_VAR, "c", wrong);
                assertTrue(e.getMessage().contains("$c expects text"), e.getMessage());
            }
            assertEquals(List.of("CH"), bind(STRING_VAR, "c", "CH"));
        }

        @Test
        void structuresAreNamedByTypeAndNeverEchoedIntoTheMessage() {
            // The message may be logged or returned, so caller data stays out of it.
            String message = rejected(STRING_VAR, "c", Map.of("secret", "hunter2")).getMessage();
            assertFalse(message.contains("hunter2"), message);
            assertFalse(message.contains("secret"), message);
        }

        @Test
        void textIsStillAcceptedForTypesJsonCannotCarry() {
            // JSON has no date type and one number type, so text has to work --
            // but it has to parse exactly.
            assertEquals(List.of(java.time.LocalDate.of(2019, 12, 31)), bind(DATE_VAR, "d", "2019-12-31"));
            assertEquals(List.of(7L), bind(INT_VAR, "n", "7"));

            assertTrue(rejected(DATE_VAR, "d", "31/12/2019").getMessage().contains("a date like"));
            assertTrue(rejected(INT_VAR, "n", "abc").getMessage().contains("a whole number"));
        }

        @Test
        void aFractionIsNotAWholeNumberButAnIntegralDoubleIs() {
            // JSON numbers arrive as Double, so 4.0 has to mean 4 -- while
            // Number#longValue would have quietly turned 3.7 into 3.
            assertEquals(List.of(4L), bind(INT_VAR, "n", 4.0));
            assertTrue(rejected(INT_VAR, "n", 3.7).getMessage().contains("a whole number"));
            // Wider than a long, rather than wrapping.
            assertTrue(rejected(INT_VAR, "n", 1.0e20).getMessage().contains("a whole number"));
        }

        @Test
        void aDecimalKeepsThePrecisionItWasWrittenWith() {
            // new BigDecimal(0.1d) would bind 0.1000000000000000055511151231257827.
            assertEquals(List.of(new java.math.BigDecimal("0.1")),
                    bind("list { TransactionId } over { TransactionValue > $v }", "v", 0.1));
        }

        @Test
        void anUnparseableBooleanIsRejectedRatherThanReadAsFalse() {
            // Boolean.parseBoolean answers false for "yes", "1" and everything
            // else, which is a wrong answer dressed as a valid one.
            TaqlCompiler flags = new TaqlCompiler(new Catalog(Map.of("t", new Catalog.Entity(
                    "t", new Catalog.Table("dbo", "T"), List.of(),
                    List.of(Catalog.Field.of("id", TaqlType.STRING, new SqlType.VarChar(10)),
                            Catalog.Field.of("active", TaqlType.BOOLEAN, new SqlType.Bit()))))));
            String query = "list { id } from t over { active = $on }";

            assertEquals(List.of(true), flags.compile(query).bind(Map.of("on", true)));
            assertEquals(List.of(false), flags.compile(query).bind(Map.of("on", "false")));

            TaqlException e = assertThrows(TaqlException.class,
                    () -> flags.compile(query).bind(Map.of("on", "yes")));
            assertTrue(e.getMessage().contains("$on expects true or false"), e.getMessage());
        }

        @Test
        void aBadListElementIsNamedByItsPosition() {
            String query = "list { TransactionId } over { Country in $cs }";
            TaqlException e = assertThrows(TaqlException.class,
                    () -> compiler.compile(query).bind(Map.of("cs", List.of("CH", 42))));
            assertTrue(e.getMessage().contains("$cs[1] expects text"), e.getMessage());

            TaqlException notAList = assertThrows(TaqlException.class,
                    () -> compiler.compile(query).bind(Map.of("cs", "CH")));
            assertTrue(notAList.getMessage().contains("$cs must be a list"), notAList.getMessage());
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
                    () -> assertEquals(1, compiler.shapeCache().size()));
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
            assertEquals(1, compiler.shapeCache().size());
            assertEquals(2, compiler.textCache().size());
        }

        @Test
        void identicalTextSkipsParsingEntirely() {
            String source = "list { transactionId } over { clientId = '1' }";
            compiler.compile(source);
            long missesBefore = compiler.textCache().misses();
            compiler.compile(source);
            assertEquals(missesBefore, compiler.textCache().misses());
            assertEquals(1, compiler.textCache().hits());
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
        void hostileVariableValuesAreAlsoJustValues() {
            var compiled = compiler.compile("list { transactionId } over { clientId in $ids }");
            List<Object> values = compiled.bind(Map.of("ids", List.of("a\"; DROP TABLE x; --")));
            assertFalse(compiled.plan().statement().contains("DROP"));
            assertEquals("[\"a\\\"; DROP TABLE x; --\"]", values.getFirst());
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

        @Test
        void aListVariableRendersItsElementTypeIntoTheOpenjsonClause() {
            // The type reaches the SQL text through SqlType.statement(), not as a
            // string carried around from the catalog.
            assertTrue(compiler.compileUncached("list { transactionId } over { currency in $c }")
                    .statement().contains("WITH ([value] varchar(3) '$')"));
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
        void conflictingVariableUsesAreReported() {
            TaqlException e = assertThrows(TaqlException.class, () -> compiler.compileUncached(
                    "list { transactionId } over { clientId = $x, TransactionValue > $x }"));
            assertTrue(e.getMessage().contains("is used as"));
        }

        @Test
        void duplicateOutputNamesAreRejected() {
            TaqlException e = assertThrows(TaqlException.class,
                    () -> compiler.compileUncached("list { a = clientId, a = currency }"));
            assertTrue(e.getMessage().contains("duplicate output name 'a'"));
        }
    }
}
