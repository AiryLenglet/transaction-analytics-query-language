package ch.lenglet.taql.policy;

import ch.lenglet.taql.Diagnostic;
import ch.lenglet.taql.ast.Ast;

import java.util.List;

/**
 * A rule every query must satisfy before it runs.
 *
 * The rule walks the parsed query, which holds everything it needs: the
 * statement's shape, and -- because TAQL has no placeholders -- the values too.
 * {@link Ast.Query#literals()} holds the constants that were lifted out of the
 * tree during parsing, and an {@link Ast.Lit} names its slot in that table.
 *
 * <h2>The logic belongs to the policy</h2>
 * Nothing is pre-computed for it. A rule about which clients a query may read,
 * one about which columns may appear in a filter, one that caps how many values
 * an {@code in} list may hold -- these ask different questions of the same tree,
 * and a library guessing which answer to prepare would serve none of them well.
 * The plan stays what it is: statement text and a parameter recipe.
 *
 * <h2>Walking a filter soundly</h2>
 * The trap, for any rule about what a query reads, is that a condition only
 * constrains the result if nothing can escape it. A term under an {@code or}
 * constrains nothing -- {@code ClientId = '1' or Country = 'CH'} returns every
 * client's rows -- and so does a negated one. A rule that walks into either and
 * counts what it finds permits exactly the queries it meant to stop. Walk
 * {@link Ast.And} and stop at anything else, and the worst case is refusing a
 * query that would have been fine.
 *
 * The source text is deliberately not passed. {@link Ast.Query} describes the
 * query completely, and the raw text is the one form that carries the caller's
 * constants verbatim -- which is why {@link TaqlQuery#toString()} refuses to
 * render it. A rule has no use for it that reading the tree does not serve
 * better.
 *
 * <h2>Refusing</h2>
 * Return the reasons rather than throwing them. A refusal is an ordinary
 * outcome, not an exception, and returning lets several be reported at once --
 * the same reason {@code Resolver} accumulates through {@code error()} instead
 * of failing on the first problem. {@link ch.lenglet.taql.TaqlTemplate}
 * turns a non-empty result into one {@link TaqlException}, so a refusal reaches
 * the caller as a 400 carrying diagnostics, like every other rejection.
 *
 * Use {@link Diagnostic.Phase#POLICY}, and point at the offending term: every
 * {@link Ast.Pred} and {@link Ast.Expr} carries a {@link Ast.Pos}, so a rule can
 * say <em>where</em> as well as what. Say what rule was broken, not what the
 * caller would have needed to satisfy it -- "you may not read client CH-9" tells
 * someone that CH-9 exists.
 *
 * <h2>Where the caller comes from</h2>
 * Not from here. This gets the query and nothing else; an implementation gets
 * the principal from wherever it already lives. That keeps the library out of the business of
 * modelling identity -- and puts the burden on the implementation to refuse when
 * no principal is established, rather than sail past the check.
 */
@FunctionalInterface
public interface QueryPolicy {

    /** Permits every query. The default, so adding a policy is a deliberate act. */
    QueryPolicy PERMIT_ALL = query -> List.of();

    /**
     * Every rule, and every objection.
     *
     * A caller who broke two rules hears about both, which is the reason these
     * return their objections instead of throwing them: the first refusal would
     * otherwise hide the rest, and the caller would fix one thing and be
     * refused again.
     */
    static QueryPolicy all(QueryPolicy... policies) {
        List<QueryPolicy> chain = List.of(policies);
        return query -> chain.stream().flatMap(policy -> policy.check(query).stream()).toList();
    }

    /**
     * @param query the query as written: {@code stmt()} to walk,
     *              {@code literals()} to read an {@link Ast.Lit}'s value
     * @return why this query may not run, or empty to permit it
     */
    List<Diagnostic> check(Ast.Query query);
}
