package ch.lenglet.taql;

import ch.lenglet.taql.catalog.DemoCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a filter guarantees.
 *
 * Every rule about what a query may read rests on these answers, and a wrong
 * one fails <em>open</em> -- so the cases that matter are the ones that must
 * come back empty. Saying "I cannot tell" too often costs a refused query;
 * saying it too rarely costs the table.
 */
@DisplayName("filters")
class FiltersTest {

    private final TaqlCompiler compiler = new TaqlCompiler(DemoCatalog.create());

    private Set<Object> pinned(String query) {
        return Filters.pinnedValues(compiler.compile(query).parsed(), "ClientId").orElseThrow();
    }

    private boolean tellsUsNothing(String query) {
        return Filters.pinnedValues(compiler.compile(query).parsed(), "ClientId").isEmpty();
    }

    // ==================================================================
    @Nested
    @DisplayName("pinned values")
    class Pinned {

        @Test
        void equalityAndMembershipPinTheField() {
            assertEquals(Set.of("CH-1"), pinned("list { TransactionId } over { ClientId = 'CH-1' }"));
            assertEquals(Set.of("CH-1", "CH-2"),
                    pinned("list { TransactionId } over { clientId in ['CH-1','CH-2'] }"));
        }

        @Test
        void theFieldNameIsMatchedTheWayTheCatalogMatchesIt() {
            assertEquals(Set.of("CH-1"), pinned("list { TransactionId } over { CLIENTID = 'CH-1' }"));
        }

        @Test
        void eitherSideOfTheEqualsMayBeTheField() {
            assertEquals(Set.of("CH-1"), pinned("list { TransactionId } over { 'CH-1' = ClientId }"));
        }

        @Test
        void andIsWalkedThroughAtAnyDepth() {
            assertEquals(Set.of("CH-1"), pinned(
                    "list { TransactionId } over { Country = 'CH' and (Currency = 'CHF' and ClientId = 'CH-1') }"));
        }

        @Test
        void conditionsThatAllHoldAreIntersected() {
            // Both conjuncts are true of every row, so the query can return no
            // more than what they agree on.
            assertEquals(Set.of("CH-1"), pinned(
                    "list { TransactionId } over { clientId in ['CH-1','CH-2'], ClientId = 'CH-1' }"));
        }

        @Test
        void aConjunctThatCannotBeEnumeratedOnlyEverNarrows() {
            // 'like' removes rows, so ignoring it leaves the answer no narrower
            // than the truth -- which is the safe direction.
            assertEquals(Set.of("CH-1"), pinned(
                    "list { TransactionId } over { ClientId = 'CH-1', ClientId like 'CH%' }"));
        }

        @Test
        void nothingUnderAnOrCounts() {
            // The case the whole design exists for: the second branch matches
            // every client, so this query reads the table.
            assertTrue(tellsUsNothing("list { TransactionId } over { ClientId = 'CH-1' or Country = 'CH' }"));
            // Sound but conservative -- the same as in ['CH-1','CH-2'].
            assertTrue(tellsUsNothing("list { TransactionId } over { ClientId = 'CH-1' or ClientId = 'CH-2' }"));
        }

        @Test
        void nothingUnderANotCounts() {
            assertTrue(tellsUsNothing("list { TransactionId } over { not (ClientId = 'CH-1') }"));
            assertTrue(tellsUsNothing("list { TransactionId } over { clientId not in ['CH-9'] }"));
        }

        @Test
        void whatCannotBeEnumeratedTellsUsNothing() {
            for (String query : List.of(
                    "list { TransactionId }",
                    "list { TransactionId } over { Country = 'CH' }",
                    "list { TransactionId } over { ClientId like 'CH%' }",
                    "list { TransactionId } over { ClientId != 'CH-9' }",
                    "list { TransactionId } over { ClientId > 'CH-1' }",
                    "list { TransactionId } over { ClientId in 'CH-1'..'CH-5' }",
                    "list { TransactionId } over { ClientId is not null }",
                    "list { TransactionId } over { ClientId = upper(Country) }")) {
                assertTrue(tellsUsNothing(query), query + " must not appear to pin ClientId");
            }
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("ranges")
    class Ranges {

        private java.util.Optional<Filters.Range> dates(String query) {
            return Filters.range(compiler.compile(query).parsed(), "TransactionDate");
        }

        @Test
        void aClosedIntervalBoundsTheFieldOnBothSides() {
            Filters.Range window = dates(
                    "list { TransactionId } over { TransactionDate in '2019-01-01'..'2019-12-31' }").orElseThrow();
            assertEquals("2019-01-01", window.low());
            assertEquals("2019-12-31", window.high());
        }

        @Test
        void oneSidedAndUnboundedFormsDoNot() {
            // '> 2010' is everything since, which for a date is usually the
            // whole table.
            for (String query : List.of(
                    "list { TransactionId }",
                    "list { TransactionId } over { TransactionDate > '2010-01-01' }",
                    "list { TransactionId } over { TransactionDate = '2010-01-01' }",
                    "list { TransactionId } over { TransactionDate not in '2010-01-01'..'2019-12-31' }",
                    "list { TransactionId } over { Country = 'CH' or TransactionDate in '2019-01-01'..'2019-12-31' }")) {
                assertTrue(dates(query).isEmpty(), query + " must not appear to bound the date");
            }
        }
    }
}
