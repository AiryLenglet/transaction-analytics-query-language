package ch.lenglet.taql;

import ch.lenglet.taql.catalog.DemoCatalog;
import ch.lenglet.taql.plan.Plan;
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
                        date in '2010-01-01'..'2019-12-31'
                    }
                    """);

            assertAll(
                    () -> assertTrue(plan.sql().contains("CASE WHEN tt.[Name] IN (?, ?) THEN ?")),
                    () -> assertTrue(plan.sql().contains("COUNT(*)")),
                    () -> assertTrue(plan.sql().contains("t.[TransactionDate] BETWEEN ? AND ?")),
                    // The key carries parameters, so it is projected once by a
                    // derived table and the measures read it back from there.
                    () -> assertTrue(plan.sql().contains("END AS [category]")),
                    () -> assertTrue(plan.sql().contains("GROUP BY g.[category]")),
                    () -> assertTrue(plan.sql().matches("(?s).*SUM\\(CASE WHEN g\\.\\[c\\d\\] = \\? THEN g\\.\\[c\\d\\] END\\).*")),
                    () -> assertEquals(List.of("category", "incoming", "outgoing", "count"),
                            plan.columns().stream().map(Plan.Column::name).toList()));
        }

        @Test
        void joinsOnlyTheTablesTheFieldsNeed() {
            assertFalse(compiler.compileUncached("list { transactionId } over { clientId = '1' }")
                    .sql().contains("JOIN"));
            assertTrue(compiler.compileUncached("list { country } over { clientId = '1' }")
                    .sql().contains("INNER JOIN [dbo].[Counterparties]"));
        }

        @Test
        void topByOrdersOnTheNamedMeasure() {
            Plan plan = compiler.compileUncached("""
                    analysis by country { total = sum(amount) } top 10 by total
                    """);
            assertTrue(plan.sql().contains("ORDER BY [total] DESC"));
        }

        @Test
        void groupsThroughADerivedTableWhenTheKeyCarriesParameters() {
            // Repeating a parameterised key in GROUP BY would make T-SQL see two
            // different expressions (@P1.. vs @P13..) and reject the query.
            Plan plan = compiler.compileUncached("""
                    analysis by bucket = match currency { ['CHF'] -> 'local'  _ -> 'foreign' } {
                        total = sum(amount) when direction = 'C'
                    }
                    """);
            assertAll(
                    () -> assertTrue(plan.sql().contains("FROM (")),
                    () -> assertTrue(plan.sql().contains("END AS [bucket]")),
                    () -> assertTrue(plan.sql().contains("GROUP BY g.[bucket]")),
                    () -> assertFalse(plan.sql().contains("GROUP BY CASE"), "key must not be repeated"),
                    () -> assertEquals(1, plan.sql().split("CASE WHEN t.\\[Currency\\]", -1).length - 1,
                            "the key expression must be emitted exactly once"));
        }

        @Test
        void groupsInlineWhenTheKeyHasNoParameters() {
            // YEAR(date) repeats harmlessly, so no derived table is needed.
            Plan plan = compiler.compileUncached(
                    "analysis by y = year(date) { total = sum(amount) }");
            assertFalse(plan.sql().contains("FROM ("));
            assertTrue(plan.sql().contains("GROUP BY YEAR(t.[TransactionDate])"));
        }

        @Test
        void rejectsTopByAnUnknownMeasure() {
            TaqlException e = assertThrows(TaqlException.class, () -> compiler.compileUncached(
                    "analysis by country { total = sum(amount) } top 10 by nope"));
            assertTrue(e.getMessage().contains("must name one of the measures"));
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("flat queries")
    class Flat {

        @Test
        void compilesProjectionsFilterSortAndLimit() {
            Plan plan = compiler.compileUncached("""
                    list { transactionId, amount, country }
                    over { clientId in ['1','3'], amount > 100 }
                    sort by amount desc
                    top 5
                    """);
            assertAll(
                    () -> assertTrue(plan.sql().startsWith("SELECT TOP (?)")),
                    () -> assertTrue(plan.sql().contains("t.[ClientId] IN (?, ?)")),
                    () -> assertTrue(plan.sql().contains("ORDER BY [amount] DESC")),
                    () -> assertFalse(plan.sql().contains("GROUP BY")));
        }

        @Test
        void injectsARowCapWhenTopIsOmitted() {
            Plan plan = compiler.compileUncached("list { transactionId }");
            assertTrue(plan.sql().startsWith("SELECT TOP (?)"));
            assertEquals(1000L, ((Plan.Constant) plan.parameters().getFirst()).value());
        }

        @Test
        void rejectsAggregatesOutsideAnAnalysisBlock() {
            TaqlException e = assertThrows(TaqlException.class,
                    () -> compiler.compileUncached("list { x = sum(amount) }"));
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
                    over { clientId in $clients, date in $from..$to }
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
            assertTrue(plan.sql().contains("OPENJSON(?) WITH ([value] varchar(50) '$')"));

            List<Object> two = compiler.compile(source).bind(Map.of("clients", List.of("1", "3")));
            List<Object> five = compiler.compile(source).bind(
                    Map.of("clients", List.of("1", "2", "3", "4", "5")));
            assertEquals("[\"1\",\"3\"]", two.get(1));
            assertEquals("[\"1\",\"2\",\"3\",\"4\",\"5\"]", five.get(1));
            assertEquals(two.size(), five.size(), "arity must not change the parameter count");
        }

        @Test
        void convertsLiteralsToTheColumnType() {
            List<Object> values = compiler.compile(
                    "list { transactionId } over { date in '2010-01-01'..'2019-12-31' }").bind();
            assertEquals(java.time.LocalDate.of(2010, 1, 1), values.get(1));
            assertEquals(java.time.LocalDate.of(2019, 12, 31), values.get(2));
        }

        @Test
        void bindsAgainstTheColumnsPhysicalTypeNotAGenericOne() {
            Plan plan = compiler.compileUncached("list { transactionId } over { direction = 'C' }");
            Plan.Auto slot = (Plan.Auto) plan.parameters().get(1);
            assertEquals("varchar(1)", slot.sqlType());
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
            assertEquals("1", first.bind().get(1));
            assertEquals("999", second.bind().get(1));
        }

        @Test
        void listArityIsPartOfTheShapeBecauseItChangesTheSql() {
            Plan two = compiler.compile("list { transactionId } over { clientId in ['1','2'] }").plan();
            Plan three = compiler.compile("list { transactionId } over { clientId in ['1','2','3'] }").plan();
            assertFalse(two.shapeKey().equals(three.shapeKey()));
            assertTrue(two.sql().contains("IN (?, ?)"));
            assertTrue(three.sql().contains("IN (?, ?, ?)"));
        }

        @Test
        void literalOrderIsStableAcrossFormattingSoSlotIndicesStayValid() {
            // Plan.Auto slots index into the *calling* query's literal table, so
            // two texts sharing a plan must lift their literals in the same order.
            String a = "list { transactionId } over { clientId in ['1','2'], direction = 'C', amount > 10 }";
            String b = """
                    list {
                        transactionId   // same shape, different layout and values
                    }
                    over {
                        clientId in ['8','9']
                        direction = 'D'
                        amount > 99
                    }
                    """;
            var first = compiler.compile(a);
            var second = compiler.compile(b);
            assertSame(first.plan(), second.plan());
            assertEquals(List.of(1000L, "1", "2", "C", new java.math.BigDecimal("10")), first.bind());
            assertEquals(List.of(1000L, "8", "9", "D", new java.math.BigDecimal("99")), second.bind());
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
                    () -> assertFalse(compiled.plan().sql().contains("DROP")),
                    () -> assertFalse(compiled.plan().sql().contains("--")),
                    () -> assertEquals("t.[ClientId] = ?",
                            compiled.plan().sql().lines()
                                    .filter(l -> l.startsWith("WHERE"))
                                    .findFirst().orElseThrow().substring("WHERE ".length())),
                    () -> assertEquals(payload, compiled.bind().get(1)));
        }

        @Test
        void hostileVariableValuesAreAlsoJustValues() {
            var compiled = compiler.compile("list { transactionId } over { clientId in $ids }");
            List<Object> values = compiled.bind(Map.of("ids", List.of("a\"; DROP TABLE x; --")));
            assertFalse(compiled.plan().sql().contains("DROP"));
            assertEquals("[\"a\\\"; DROP TABLE x; --\"]", values.get(1));
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
        void typeErrorsExplainTheMismatch() {
            TaqlException e = assertThrows(TaqlException.class,
                    () -> compiler.compileUncached("analysis by country { bad = sum(currency) }"));
            assertTrue(e.getMessage().contains("sum() needs a numeric argument"));
        }

        @Test
        void conflictingVariableUsesAreReported() {
            TaqlException e = assertThrows(TaqlException.class, () -> compiler.compileUncached(
                    "list { transactionId } over { clientId = $x, amount > $x }"));
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
