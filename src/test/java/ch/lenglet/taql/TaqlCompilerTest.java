package ch.lenglet.taql;

import ch.lenglet.taql.catalog.Catalog;
import ch.lenglet.taql.catalog.DemoCatalog;
import ch.lenglet.taql.SqlType;
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
                    () -> assertTrue(plan.sql().contains("CASE WHEN t.[TransactionType] IN (?, ?) THEN ?")),
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
        void theFlatSchemaNeedsNoJoins() {
            assertFalse(compiler.compileUncached("list { transactionId, country, transactionType }")
                    .sql().contains("JOIN"));
        }

        @Test
        void topByOrdersOnTheNamedMeasure() {
            Plan plan = compiler.compileUncached("""
                    analysis by country { total = sum(TransactionValue) } top 10 by total
                    """);
            assertTrue(plan.sql().contains("ORDER BY [total] DESC"));
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
                    () -> assertTrue(plan.sql().contains("FROM (")),
                    () -> assertTrue(plan.sql().contains("END AS [bucket]")),
                    () -> assertTrue(plan.sql().contains("GROUP BY g.[bucket]")),
                    () -> assertFalse(plan.sql().contains("GROUP BY CASE"), "key must not be repeated"),
                    () -> assertEquals(1, plan.sql().split("CASE WHEN t.\\[Currency\\]", -1).length - 1,
                            "the key expression must be emitted exactly once"));
        }

        @Test
        void groupsInlineWhenTheKeyHasNoParameters() {
            // YEAR(TransactionDate) repeats harmlessly, so no derived table is needed.
            Plan plan = compiler.compileUncached(
                    "analysis by y = year(TransactionDate) { total = sum(TransactionValue) }");
            assertFalse(plan.sql().contains("FROM ("));
            assertTrue(plan.sql().contains("GROUP BY YEAR(t.[TransactionDate])"));
        }

        @Test
        void rejectsTopByAnUnknownMeasure() {
            TaqlException e = assertThrows(TaqlException.class, () -> compiler.compileUncached(
                    "analysis by country { total = sum(TransactionValue) } top 10 by nope"));
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
                    list { transactionId, TransactionValue, country }
                    over { clientId in ['1','3'], TransactionValue > 100 }
                    sort by TransactionValue desc
                    top 5
                    """);
            assertAll(
                    () -> assertTrue(plan.sql().startsWith("SELECT TOP (?)")),
                    () -> assertTrue(plan.sql().contains("t.[ClientId] IN (?, ?)")),
                    () -> assertTrue(plan.sql().contains("ORDER BY [TransactionValue] DESC")),
                    () -> assertFalse(plan.sql().contains("GROUP BY")));
        }

        @Test
        void omitsTopEntirelyWhenTheQueryDoesNotAskForOne() {
            Plan plan = compiler.compileUncached("list { transactionId }");
            assertAll(
                    () -> assertFalse(plan.sql().contains("TOP"), plan.sql()),
                    () -> assertTrue(plan.sql().startsWith("SELECT\n")),
                    () -> assertTrue(plan.parameters().isEmpty()));
        }

        @Test
        void appliesAConfiguredRowCapWhenOneIsSet() {
            // Off by default so the SQL mirrors the TAQL; a deployment that does
            // not want unbounded results opts in here.
            TaqlCompiler capped = new TaqlCompiler(DemoCatalog.create(),
                    new Resolver.Options(1000), 16, 16);
            Plan plan = capped.compileUncached("list { transactionId }");
            assertTrue(plan.sql().startsWith("SELECT TOP (?)"));
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
            assertTrue(plan.sql().contains("OPENJSON(?) WITH ([value] varchar(50) '$')"));

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
            assertEquals(new SqlType.VarChar(1), slot.sqlType());
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
            assertEquals("1", first.bind().getFirst());
            assertEquals("999", second.bind().getFirst());
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
                    () -> assertEquals(payload, compiled.bind().getFirst()));
        }

        @Test
        void hostileVariableValuesAreAlsoJustValues() {
            var compiled = compiler.compile("list { transactionId } over { clientId in $ids }");
            List<Object> values = compiled.bind(Map.of("ids", List.of("a\"; DROP TABLE x; --")));
            assertFalse(compiled.plan().sql().contains("DROP"));
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
            // The type reaches the SQL text through SqlType.sql(), not as a
            // string carried around from the catalog.
            assertTrue(compiler.compileUncached("list { transactionId } over { currency in $c }")
                    .sql().contains("WITH ([value] varchar(3) '$')"));
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
                    .sql().contains("INNER JOIN [dbo].[Customers] AS c ON o.[CustomerId] = c.[CustomerId]"));
        }

        @Test
        void aQueryThatNeverReadsTheJoinDoesNotEmitIt() {
            assertFalse(joined.compileUncached("list { orderId } from orders").sql().contains("JOIN"));
        }

        @Test
        void aJoinedFieldUsedOnlyInAFilterStillPullsInItsJoin() {
            assertTrue(joined.compileUncached("list { orderId } from orders over { customerName = 'x' }")
                    .sql().contains("INNER JOIN [dbo].[Customers]"));
        }

        @Test
        void aTableKeepsTheSameAliasAcrossQueries() {
            // Aliases are allocated per statement, but from the entity alone --
            // so they do not drift with which joins a given query happens to need.
            assertTrue(joined.compileUncached("list { orderId } from orders").sql().contains("AS o"));
            assertTrue(joined.compileUncached("list { orderId, customerName } from orders")
                    .sql().contains("[dbo].[Orders] AS o"));
        }

        @Test
        void aliasesAreDisambiguatedAndAvoidTheDerivedTableName() {
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

            String sql = c.compileUncached("list { id, customerName, contractRef } from orders").sql();
            assertAll(
                    () -> assertTrue(sql.contains("[dbo].[Groups] AS g2"), sql),
                    () -> assertTrue(sql.contains("INNER JOIN [dbo].[Customers] AS c ON g2.[Id] = c.[Id]"), sql),
                    () -> assertTrue(sql.contains("LEFT JOIN [dbo].[Contracts] AS c2 ON g2.[Id] = c2.[Id]"), sql));
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
