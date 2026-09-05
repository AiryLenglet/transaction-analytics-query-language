package ch.lenglet.taql;

import ch.lenglet.taql.ast.Ast;
import ch.lenglet.taql.ast.TaqlParserFacade;
import ch.lenglet.taql.cache.PlanCache;
import ch.lenglet.taql.catalog.Catalog;
import ch.lenglet.taql.plan.Plan;
import ch.lenglet.taql.runtime.Binder;
import ch.lenglet.taql.sem.Resolver;
import ch.lenglet.taql.sem.Tam;
import ch.lenglet.taql.sql.SqlServerGenerator;

import java.util.List;
import java.util.Map;

/**
 * The full pipeline, plus the two caches that keep it off the hot path.
 *
 * <h2>Why two levels</h2>
 * The obvious cache -- source text to plan -- only helps when clients send
 * byte-identical queries, which they will not: the values change on every call.
 * So:
 *
 * <ul>
 *   <li><b>L1, keyed on exact source text.</b> Holds the finished
 *       {@link Compiled} (plan + that text's literal values). A repeated
 *       identical request skips everything, parsing included.</li>
 *   <li><b>L2, keyed on the query's <em>shape</em>.</b> Reached after parsing,
 *       which is the cheap phase. Everything expensive -- resolution, type
 *       checking, lowering, SQL generation -- happens only on an L2 miss.
 *       Because {@link ch.lenglet.taql.ast.AstBuilder} lifts literals out of
 *       the tree, a thousand queries differing only in their constants share
 *       one L2 entry.</li>
 * </ul>
 *
 * Clients that use explicit {@code $variables} land in L1 every time, which is
 * the reason to prefer them; clients that inline constants still land in L2.
 */
public final class TaqlCompiler {

    private final Catalog catalog;
    private final Resolver.Options options;
    private final PlanCache<String, Compiled> textCache;
    private final PlanCache<String, Plan> shapeCache;

    public TaqlCompiler(Catalog catalog) {
        this(catalog, Resolver.Options.DEFAULTS, 512, 512);
    }

    public TaqlCompiler(Catalog catalog, Resolver.Options options, int textCacheSize, int shapeCacheSize) {
        this.catalog = catalog;
        this.options = options;
        this.textCache = new PlanCache<>(textCacheSize);
        this.shapeCache = new PlanCache<>(shapeCacheSize);
    }

    /** A plan together with the literal values of the specific query text it came from. */
    public record Compiled(Plan plan, List<Object> literals) {

        public List<Object> bind(Map<String, Object> variables) {
            return Binder.resolve(plan, literals, variables);
        }

        public List<Object> bind() {
            return bind(Map.of());
        }
    }

    public Compiled compile(String source) {
        return textCache.get(source, text -> {
            Ast.Query parsed = TaqlParserFacade.parse(text);
            Plan plan = shapeCache.get(parsed.shapeKey(), shape -> {
                Resolver.Result resolved = Resolver.resolve(catalog, parsed, options);
                return SqlServerGenerator.generate(resolved.query(), resolved.variables(), shape);
            });
            return new Compiled(plan, parsed.literals());
        });
    }

    /** Parses and type-checks without consulting either cache -- for validation endpoints and tests. */
    public Plan compileUncached(String source) {
        Ast.Query parsed = TaqlParserFacade.parse(source);
        Resolver.Result resolved = Resolver.resolve(catalog, parsed, options);
        return SqlServerGenerator.generate(resolved.query(), resolved.variables(), parsed.shapeKey());
    }

    public Tam.Query analyse(String source) {
        return Resolver.resolve(catalog, TaqlParserFacade.parse(source), options).query();
    }

    public PlanCache<String, Compiled> textCache() {
        return textCache;
    }

    public PlanCache<String, Plan> shapeCache() {
        return shapeCache;
    }

    public Catalog catalog() {
        return catalog;
    }
}
