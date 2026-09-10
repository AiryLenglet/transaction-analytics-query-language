package ch.lenglet.taql;

import ch.lenglet.taql.catalog.DemoCatalog;
import ch.lenglet.taql.plan.Restrictions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Authorising the clients a query asks about.
 *
 * The interesting cases are the ones that must be <em>refused</em>: a check that
 * reads "which clients does this query want" has to answer "I cannot tell" for
 * every filter shape it does not fully understand, because answering "none, so
 * nothing to check" is how a query that reads the whole table gets through.
 */
@DisplayName("query policy")
class QueryPolicyTest {

    private final TaqlCompiler compiler = new TaqlCompiler(DemoCatalog.create());

    /** What a deployment would write: this caller may read these two clients. */
    private static final QueryPolicy MAY_READ_CH1_AND_CH2 = (query, restrictions) -> {
        Set<Object> asked = restrictions.on("ClientId").orElseThrow(() -> new TaqlException(
                new Diagnostic(Diagnostic.Phase.POLICY, 0, 0,
                        "every query must name the clients it reads, as 'clientId = x' or 'clientId in [...]'")));
        if (!Set.of("CH-1", "CH-2").containsAll(asked)) {
            throw new TaqlException(new Diagnostic(Diagnostic.Phase.POLICY, 0, 0,
                    "this query reads clients you are not permitted to read"));
        }
    };

    private Restrictions restrictionsOf(String query) {
        return compiler.compile(query).bind().restrictions();
    }

    private void check(String query) {
        MAY_READ_CH1_AND_CH2.check(TaqlQuery.of(query), restrictionsOf(query));
    }

    private TaqlException refused(String query) {
        return assertThrows(TaqlException.class, () -> check(query));
    }

    // ==================================================================
    @Nested
    @DisplayName("what the filter pins down")
    class Pinned {

        @Test
        void equalityAndMembershipPinTheField() {
            assertEquals(Set.of("CH-1"),
                    restrictionsOf("list { TransactionId } over { ClientId = 'CH-1' }").on("ClientId").orElseThrow());
            assertEquals(Set.of("CH-1", "CH-2"),
                    restrictionsOf("list { TransactionId } over { clientId in ['CH-1','CH-2'] }").on("clientid").orElseThrow());
        }

        @Test
        void nestedConjunctionStillCounts() {
            assertEquals(Set.of("CH-1"), restrictionsOf(
                    "list { TransactionId } over { Country = 'CH' and (ClientId = 'CH-1' and Currency = 'CHF') }")
                    .on("ClientId").orElseThrow());
        }

        @Test
        void severalConjunctsOnOneFieldIntersect() {
            assertEquals(Set.of("CH-1"), restrictionsOf(
                    "list { TransactionId } over { clientId in ['CH-1','CH-2'], ClientId = 'CH-1' }")
                    .on("ClientId").orElseThrow());
        }

        @Test
        void anUnenumerableConjunctNarrowsAndIsSafelyIgnored() {
            // 'like' cannot be enumerated, but it can only remove rows, so the
            // recorded set stays an upper bound on what the query can return.
            assertEquals(Set.of("CH-1"), restrictionsOf(
                    "list { TransactionId } over { ClientId = 'CH-1', ClientId like 'CH%' }")
                    .on("ClientId").orElseThrow());
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
                assertTrue(restrictionsOf(query).on("ClientId").isEmpty(),
                        () -> query + " must not appear to pin ClientId");
            }
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("the policy itself")
    class Enforcement {

        @Test
        void permitsQueriesWithinTheCallersClients() {
            check("list { TransactionId } over { ClientId = 'CH-1' }");
            check("list { TransactionId } over { clientId in ['CH-1','CH-2'] }");
            check("analysis by country { total = sum(TransactionValue) } over { ClientId = 'CH-2' }");
        }

        @Test
        void refusesAClientTheCallerMayNotRead() {
            assertTrue(refused("list { TransactionId } over { ClientId = 'CH-9' }")
                    .getMessage().contains("not permitted to read"));
            assertTrue(refused("list { TransactionId } over { clientId in ['CH-1','CH-9'] }")
                    .getMessage().contains("not permitted to read"));
        }

        @Test
        void refusesAQueryThatNamesNoClient() {
            TaqlException e = refused("list { TransactionId } over { Country = 'CH' }");
            assertEquals(Diagnostic.Phase.POLICY, e.diagnostics().getFirst().phase());
            assertTrue(e.getMessage().contains("must name the clients"), e.getMessage());
        }

        @Test
        void refusesTheQueryThatWouldOtherwiseReadEverything() {
            refused("list { TransactionId } over { ClientId = 'CH-1' or Country = 'CH' }");
        }

        @Test
        void aRefusalNamesTheRuleAndNotTheDataBehindIt() {
            // The message goes back to the caller; "you may not read CH-9" would
            // confirm CH-9 exists.
            String message = refused("list { TransactionId } over { ClientId = 'CH-9' }").getMessage();
            assertFalse(message.contains("CH-9"), message);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("against the plan cache")
    class Caching {

        @Test
        void twoCallersShareOnePlanAndAreJudgedSeparately() {
            // The whole reason this runs after binding: the clients are not in
            // the plan, so a permitted query cannot warm a plan for a refused one.
            String mine = "list { TransactionId } over { ClientId = 'CH-1' }";
            String theirs = "list { TransactionId } over { ClientId = 'CH-9' }";

            assertSame(compiler.compile(mine).plan(), compiler.compile(theirs).plan());
            check(mine);
            refused(theirs);
        }

    }
}
