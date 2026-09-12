package ch.lenglet.taql;

import ch.lenglet.taql.ast.Ast;

import java.time.LocalDate;
import ch.lenglet.taql.catalog.DemoCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A policy authorising the clients a query asks about, written the way one
 * would be: as a walk over the parsed query.
 *
 * The interesting cases are the ones that must be <em>refused</em>. A walk
 * asking "which clients does this query want" has to answer "I cannot tell" for
 * every shape it does not fully understand, because answering "none, so nothing
 * to check" is how a query that reads the whole table gets through.
 */
@DisplayName("query policy")
class QueryPolicyTest {

    private static final Set<String> MINE = Set.of("CH-1", "CH-2");

    private final TaqlCompiler compiler = new TaqlCompiler(DemoCatalog.create());

    // ==================================================================
    //  The policy. All of it -- the library pre-computes nothing.
    // ==================================================================

    private static final QueryPolicy MAY_READ_MY_CLIENTS = query -> {
        Optional<Set<Object>> asked = Filters.pinnedValues(query, "ClientId");
        if (asked.isEmpty()) {
            return refuse(query, "every query must name the clients it reads,"
                    + " as 'clientId = x' or 'clientId in [...]'");
        }
        if (!MINE.containsAll(asked.get())) {
            return refuse(query, "this query reads clients you are not permitted to read");
        }
        return List.of();
    };

    /** Positioned at the statement: the objection is to the query, not to a token in it. */
    private static List<Diagnostic> refuse(Ast.Query query, String why) {
        Ast.Pos at = query.stmt().pos();
        return List.of(new Diagnostic(Diagnostic.Phase.POLICY, at.line(), at.column(), why));
    }

    /**
     * A second rule, on a different field, to show what varies between them:
     * the field, and what counts as acceptable. The reasoning about what a
     * filter guarantees does not vary, and is not written in either.
     */
    private static final QueryPolicy WITHIN_ONE_YEAR = query -> {
        Optional<Filters.Range> window = Filters.range(query, "TransactionDate");
        if (window.isEmpty()) {
            return refuse(query, "state the dates you are reading, as 'TransactionDate in a..b'");
        }
        LocalDate from = LocalDate.parse((String) window.get().low());
        LocalDate to = LocalDate.parse((String) window.get().high());
        return from.plusYears(1).isBefore(to)
                ? refuse(query, "a query may cover at most a year")
                : List.of();
    };

    // ==================================================================

    private List<Diagnostic> check(String query) {
        return MAY_READ_MY_CLIENTS.check(compiler.compile(query).parsed());
    }

    private Diagnostic refused(String query) {
        List<Diagnostic> refusals = check(query);
        assertFalse(refusals.isEmpty(), () -> query + " should have been refused");
        return refusals.getFirst();
    }

    private void permitted(String query) {
        assertEquals(List.of(), check(query), query);
    }

    private Optional<Set<Object>> clients(String query) {
        return Filters.pinnedValues(compiler.compile(query).parsed(), "ClientId");
    }

    // ==================================================================
    @Nested
    @DisplayName("what the filter pins down")
    class Pinned {

        @Test
        void equalityAndMembershipPinTheField() {
            assertEquals(Set.of("CH-1"),
                    clients("list { TransactionId } over { ClientId = 'CH-1' }").orElseThrow());
            assertEquals(Set.of("CH-1", "CH-2"),
                    clients("list { TransactionId } over { clientId in ['CH-1','CH-2'] }").orElseThrow());
        }

        @Test
        void nestedConjunctionStillCounts() {
            assertEquals(Set.of("CH-1"), clients(
                    "list { TransactionId } over { Country = 'CH' and (ClientId = 'CH-1' and Currency = 'CHF') }")
                    .orElseThrow());
        }

        @Test
        void whatCannotBeEnumeratedIsNotPinnedAtAll() {
            assertAllEmpty(
                    "list { TransactionId } over { Country = 'CH' }",
                    "list { TransactionId } over { ClientId like 'CH%' }",
                    "list { TransactionId } over { ClientId != 'CH-9' }",
                    "list { TransactionId } over { ClientId in 'CH-1'..'CH-5' }",
                    "list { TransactionId } over { ClientId is not null }",
                    "list { TransactionId } over { clientId not in ['CH-9'] }",
                    "list { TransactionId }");
        }

        @Test
        void aMentionUnderOrPinsNothing() {
            // The whole point. The second branch matches every client, so this
            // query reads the table -- and it must not look like it reads CH-1.
            assertAllEmpty(
                    "list { TransactionId } over { ClientId = 'CH-1' or Country = 'CH' }",
                    "list { TransactionId } over { not (ClientId = 'CH-1') }",
                    // sound but conservative: equivalent to in ['CH-1','CH-2']
                    "list { TransactionId } over { ClientId = 'CH-1' or ClientId = 'CH-2' }");
        }

        private void assertAllEmpty(String... queries) {
            for (String query : queries) {
                assertTrue(clients(query).isEmpty(), () -> query + " must not appear to pin ClientId");
            }
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("the policy itself")
    class Enforcement {

        @Test
        void permitsQueriesWithinTheCallersClients() {
            permitted("list { TransactionId } over { ClientId = 'CH-1' }");
            permitted("list { TransactionId } over { clientId in ['CH-1','CH-2'] }");
            permitted("analysis by country { total = sum(TransactionValue) } over { ClientId = 'CH-2' }");
        }

        @Test
        void refusesAClientTheCallerMayNotRead() {
            assertTrue(refused("list { TransactionId } over { ClientId = 'CH-9' }")
                    .message().contains("not permitted to read"));
            assertTrue(refused("list { TransactionId } over { clientId in ['CH-1','CH-9'] }")
                    .message().contains("not permitted to read"));
        }

        @Test
        void refusesAQueryThatNamesNoClient() {
            Diagnostic d = refused("list { TransactionId } over { Country = 'CH' }");
            assertEquals(Diagnostic.Phase.POLICY, d.phase());
            assertTrue(d.message().contains("must name the clients"), d.message());
            // The AST carries positions, so a refusal can say where.
            assertEquals(1, d.line());
            assertTrue(d.column() > 0, d.toString());
        }

        @Test
        void refusesTheQueryThatWouldOtherwiseReadEverything() {
            refused("list { TransactionId } over { ClientId = 'CH-1' or Country = 'CH' }");
        }

        @Test
        void aRefusalNamesTheRuleAndNotTheDataBehindIt() {
            // The message goes back to the caller; "you may not read CH-9" would
            // confirm CH-9 exists.
            String message = refused("list { TransactionId } over { ClientId = 'CH-9' }").message();
            assertFalse(message.contains("CH-9"), message);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("several rules at once")
    class Composed {

        private final QueryPolicy both = QueryPolicy.all(MAY_READ_MY_CLIENTS, WITHIN_ONE_YEAR);

        private List<Diagnostic> check(String query) {
            return both.check(compiler.compile(query).parsed());
        }

        @Test
        void aQuerySatisfyingEveryRulePasses() {
            assertEquals(List.of(), check("""
                    list { TransactionId }
                    over { ClientId = 'CH-1', TransactionDate in '2019-01-01'..'2019-06-30' }
                    """));
        }

        @Test
        void eachRuleSpeaksForItself() {
            List<Diagnostic> refusals = check("""
                    list { TransactionId }
                    over { ClientId = 'CH-1', TransactionDate in '2010-01-01'..'2019-12-31' }
                    """);
            assertEquals(1, refusals.size());
            assertTrue(refusals.getFirst().message().contains("at most a year"), refusals.toString());
        }

        @Test
        void breakingTwoRulesReportsTwo() {
            // The reason these return rather than throw: a caller who fixed one
            // problem would otherwise be refused again for the next.
            List<Diagnostic> refusals = check("list { TransactionId } over { Country = 'CH' }");
            assertEquals(2, refusals.size(), refusals.toString());
            assertTrue(refusals.stream().anyMatch(d -> d.message().contains("name the clients")));
            assertTrue(refusals.stream().anyMatch(d -> d.message().contains("state the dates")));
            assertTrue(refusals.stream().allMatch(d -> d.phase() == Diagnostic.Phase.POLICY));
        }

        @Test
        void aRuleOnAnotherFieldIsTheSameRuleWithAnotherName() {
            // What varies between the two policies is the field and the test;
            // neither of them reasons about 'or' or negation, because Filters does.
            assertTrue(WITHIN_ONE_YEAR.check(compiler.compile(
                    "list { TransactionId } over { TransactionDate in '2019-01-01'..'2019-03-31' }")
                    .parsed()).isEmpty());
            assertFalse(WITHIN_ONE_YEAR.check(compiler.compile(
                    "list { TransactionId } over { Country = 'CH' or TransactionDate in '2019-01-01'..'2019-03-31' }")
                    .parsed()).isEmpty(), "a window under an or bounds nothing");
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("against the plan cache")
    class Caching {

        @Test
        void twoCallersShareOnePlanAndAreJudgedSeparately() {
            // The clients are not in the plan -- they are in the query text the
            // plan was compiled from -- so a permitted query cannot warm a plan
            // for a refused one.
            String mine = "list { TransactionId } over { ClientId = 'CH-1' }";
            String theirs = "list { TransactionId } over { ClientId = 'CH-9' }";

            assertSame(compiler.compile(mine).plan(), compiler.compile(theirs).plan());
            permitted(mine);
            refused(theirs);
        }
    }
}
