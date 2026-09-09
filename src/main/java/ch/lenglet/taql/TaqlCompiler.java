package ch.lenglet.taql;

import ch.lenglet.taql.ast.Ast;
import ch.lenglet.taql.ast.TaqlParser;
import ch.lenglet.taql.cache.LruPlanCache;
import ch.lenglet.taql.cache.PlanCache;
import ch.lenglet.taql.catalog.Catalog;
import ch.lenglet.taql.plan.FilterRestrictions;
import ch.lenglet.taql.plan.Plan;
import ch.lenglet.taql.plan.Restrictions;
import ch.lenglet.taql.runtime.jdbc.Binder;
import ch.lenglet.taql.sem.Resolver;
import ch.lenglet.taql.sem.Tam;
import ch.lenglet.taql.sql.SqlServerGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final QueryTranslator translator;
    private final TaqlParser parser;
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
     * @param translator the language these queries are written into. One compiler
     *                   serves one translator: the shape key describes the query,
     *                   not the target, so two sharing a cache would collide.
     */
    public TaqlCompiler(Catalog catalog, QueryTranslator translator, Resolver.Options options,
                        int textCacheSize, int shapeCacheSize) {
        this(catalog, translator, new TaqlParser(), options,
                new LruPlanCache<>(textCacheSize), new LruPlanCache<>(shapeCacheSize));
    }

    /**
     * Takes the caches themselves, so a deployment can supply Caffeine-backed
     * ones -- or a no-op pair, when compiling every time is preferable to
     * holding query text in memory.
     *
     * @param parser     carries the parse limits -- query length and nesting
     *                   depth -- which are a deployment's call and were not
     *                   reachable while parsing was static.
     * @param textCache  keyed on exact source, so it holds the literal values
     *                   that came with the query. That is client data; see
     *                   {@link TaqlQuery#toString()}.
     * @param shapeCache keyed on the shape, which is value-free by construction.
     */
    public TaqlCompiler(Catalog catalog, QueryTranslator translator, TaqlParser parser, Resolver.Options options,
                        PlanCache<String, Compiled> textCache, PlanCache<String, Plan> shapeCache) {
        this.catalog = catalog;
        this.translator = translator;
        this.parser = parser;
        this.options = options;
        this.textCache = textCache;
        this.shapeCache = shapeCache;
    }

    /** A plan together with the literal values of the specific query text it came from. */
    public record Compiled(Plan plan, List<Object> literals) {

        public List<Object> bind(Map<String, Object> variables) {
            return Binder.resolve(plan, literals, variables);
        }

        public List<Object> bind() {
            return bind(Map.of());
        }

        /**
         * What this query's filter pins each field down to, for the values it is
         * about to run with. The plan records where those values live; this is
         * where they are read.
         *
         * Values come back as the caller wrote or supplied them, not as
         * {@code Binder} will send them -- the two differ only where a physical
         * type forces a conversion, and an identifier compared for equality is a
         * string either way.
         */
        public Restrictions restrictions(Map<String, Object> variables) {
            Map<String, Set<Object>> resolved = new LinkedHashMap<>();
            plan.restrictions().forEach((field, conjuncts) -> {
                Set<Object> values = null;
                for (Plan.Restriction conjunct : conjuncts) {
                    Set<Object> pinned = valuesOf(conjunct, variables);
                    if (pinned == null) continue;           // this one is unknowable
                    // Several conjuncts on one field all hold at once.
                    if (values == null) values = pinned;
                    else values.retainAll(pinned);
                }
                if (values != null) resolved.put(field, values);
            });
            return new Restrictions(resolved);
        }

        /** Null when any reference cannot be read, which makes the conjunct unusable. */
        private Set<Object> valuesOf(Plan.Restriction conjunct, Map<String, Object> variables) {
            Set<Object> values = new LinkedHashSet<>();
            for (Plan.ValueRef ref : conjunct.values()) {
                switch (ref) {
                    case Plan.ValueRef.Lit l -> values.add(literals.get(l.slot()));
                    case Plan.ValueRef.Var v -> {
                        if (!variables.containsKey(v.name())) return null;
                        Object supplied = variables.get(v.name());
                        // 'in $list' contributes every element, '= $x' just itself.
                        if (supplied instanceof Collection<?> many) values.addAll(many);
                        else values.add(supplied);
                    }
                }
            }
            return values;
        }

        /** Carries no literal values; see {@link TaqlQuery#toString()}. */
        @Override
        public String toString() {
            return "Compiled[plan=" + plan.id() + ", literals=" + literals.size() + "]";
        }
    }

    public Compiled compile(String source) {
        return textCache.get(source, text -> {
            Ast.Query parsed = parser.parse(text);
            Plan plan = shapeCache.get(parsed.shapeKey(), shape -> {
                Resolver.Result resolved = Resolver.resolve(catalog, parsed, options, translator);
                Plan generated = translator.translate(resolved.query(), resolved.variables(), shape)
                        .restrictedBy(FilterRestrictions.of(resolved.query()));
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
        Ast.Query parsed = parser.parse(source);
        Resolver.Result resolved = Resolver.resolve(catalog, parsed, options, translator);
        return translator.translate(resolved.query(), resolved.variables(), parsed.shapeKey())
                .restrictedBy(FilterRestrictions.of(resolved.query()));
    }

    public Tam.Query analyse(String source) {
        return Resolver.resolve(catalog, parser.parse(source), options, translator).query();
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

    public QueryTranslator translator() {
        return translator;
    }
}
