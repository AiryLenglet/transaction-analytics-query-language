package ch.lenglet.taql;

import ch.lenglet.taql.ast.Ast;
import ch.lenglet.taql.catalog.DemoCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
        Optional<Set<Object>> asked = clientsOf(query);
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
     * The clients this query can possibly return, or empty when that cannot be
     * known -- which the policy above reads as "refuse".
     *
     * Only {@code and} is walked into. A term under an {@code or} restricts
     * nothing, because the other branch matches anyone; a negated one names what
     * is excluded rather than what is included. Stopping at both means the worst
     * this can do is refuse a query that would have been fine.
     */
    private static Optional<Set<Object>> clientsOf(Ast.Query parsed) {
        Set<Object> found = new LinkedHashSet<>();
        boolean pinned = collect(parsed.stmt().filter(), parsed.literals(), found);
        return pinned ? Optional.of(found) : Optional.empty();
    }

    /** True when this branch pins clientId to the values it added. */
    private static boolean collect(Ast.Pred pred, List<Object> literals, Set<Object> into) {
        return switch (pred) {
            case null -> false;
            case Ast.And a -> {
                boolean any = false;
                // Every conjunct holds at once, so one that pins is enough.
                for (Ast.Pred operand : a.operands()) any |= collect(operand, literals, into);
                yield any;
            }
            case Ast.Compare c when c.op().equals("=") ->
                    isClientId(c.left()) && add(c.right(), literals, into);
            case Ast.InList i when !i.negated() && isClientId(i.subject()) -> {
                for (Ast.Expr item : i.items()) {
                    if (!add(item, literals, into)) yield false;
                }
                yield !i.items().isEmpty();
            }
            // or, not, !=, <, like, between, is null: nothing enumerable
            default -> false;
        };
    }

    private static boolean isClientId(Ast.Expr e) {
        return e instanceof Ast.FieldRef f && f.name().toLowerCase(Locale.ROOT).equals("clientid");
    }

    private static boolean add(Ast.Expr value, List<Object> literals, Set<Object> into) {
        if (!(value instanceof Ast.Lit lit) || lit.slot() < 0) return false;
        into.add(literals.get(lit.slot()));
        return true;
    }

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
        return clientsOf(compiler.compile(query).parsed());
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
