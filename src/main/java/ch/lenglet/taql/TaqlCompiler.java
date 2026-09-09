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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    /**
     * What is safe to log here, and what is not.
     *
     * The shape key and the generated SQL are value-free by construction --
     * literals were lifted out before either was built, and the SQL carries
     * {@code ?} where they used to be. Both can go in a log. The source text and
     * the bound values cannot: they are the client ids, amounts and names the
     * query was asked about, and a log is not where those belong.
     */
    private static final Logger log = LoggerFactory.getLogger(TaqlCompiler.class);

    private final Catalog catalog;
    private final Backend backend;
    private final Resolver.Options options;
    private final PlanCache<String, Compiled> textCache;
    private final PlanCache<String, Plan> shapeCache;

    public TaqlCompiler(Catalog catalog) {
        this(catalog, new SqlServerGenerator(), Resolver.Options.DEFAULTS, 512, 512);
    }

    public TaqlCompiler(Catalog catalog, Resolver.Options options, int textCacheSize, int shapeCacheSize) {
        this(catalog, new SqlServerGenerator(), options, textCacheSize, shapeCacheSize);
    }

    /**
     * @param backend where these queries will run. One compiler serves one
     *                backend: the shape key describes the query, not the target,
     *                so two backends sharing a cache would collide on it.
     */
    public TaqlCompiler(Catalog catalog, Backend backend, Resolver.Options options,
                        int textCacheSize, int shapeCacheSize) {
        this.catalog = catalog;
        this.backend = backend;
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

        /** Carries no literal values; see {@link TaqlQuery#toString()}. */
        @Override
        public String toString() {
            return "Compiled[plan=" + plan.id() + ", literals=" + literals.size() + "]";
        }
    }

    public Compiled compile(String source) {
        return textCache.get(source, text -> {
            Ast.Query parsed = TaqlParserFacade.parse(text);
            Plan plan = shapeCache.get(parsed.shapeKey(), shape -> {
                Resolver.Result resolved = Resolver.resolve(catalog, parsed, options, backend);
                Plan generated = backend.generate(resolved.query(), resolved.variables(), shape);
                log.debug("plan {} compiled, {} parameters\n{}",
                        generated.id(), generated.parameters().size(), generated.statement().stripTrailing());
                log.trace("plan {} has shape {}", generated.id(), shape);
                return generated;
            });
            return new Compiled(plan, parsed.literals());
        });
    }

    /** Parses and type-checks without consulting either cache -- for validation endpoints and tests. */
    public Plan compileUncached(String source) {
        Ast.Query parsed = TaqlParserFacade.parse(source);
        Resolver.Result resolved = Resolver.resolve(catalog, parsed, options, backend);
        return backend.generate(resolved.query(), resolved.variables(), parsed.shapeKey());
    }

    public Tam.Query analyse(String source) {
        return Resolver.resolve(catalog, TaqlParserFacade.parse(source), options, backend).query();
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

    public Backend backend() {
        return backend;
    }
}
