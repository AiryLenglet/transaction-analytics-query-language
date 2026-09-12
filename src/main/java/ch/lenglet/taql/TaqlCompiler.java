package ch.lenglet.taql;

import ch.lenglet.taql.ast.Ast;
import ch.lenglet.taql.ast.TaqlParser;
import ch.lenglet.taql.cache.LruPlanCache;
import ch.lenglet.taql.cache.PlanCache;
import ch.lenglet.taql.catalog.Catalog;
import ch.lenglet.taql.plan.Plan;
import ch.lenglet.taql.runtime.jdbc.Binder;
import ch.lenglet.taql.sem.Resolver;
import ch.lenglet.taql.sem.Resolved;
import ch.lenglet.taql.sql.SqlServerGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;

/**
 * The full pipeline, plus the cache that keeps most of it off the hot path.
 *
 * <h2>One cache, keyed on the query's shape</h2>
 * The obvious cache -- source text to plan -- only helps when clients send
 * byte-identical queries, which they will not: a query states its own constants
 * and those change on every call. Measured against inlined values it hit 0.03%
 * of the time while holding hundreds of queries' worth of client data in memory,
 * so it is not here.
 *
 * What is cached is reached after parsing, which is the cheap phase, and keyed
 * on {@link Ast.Query#shapeKey()} -- a rendering of the tree with the literals
 * lifted out. Everything expensive is behind it: resolution, type checking,
 * lowering, SQL generation. A thousand queries differing only in their constants
 * share one entry, and because the key is value-free by construction, nothing a
 * caller asked about is retained between requests.
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
    private final PlanCache<String, Plan> plans;

    /**
     * What this compiler resolves against, folded into every cache key.
     *
     * A shape key describes the query and nothing else -- it is computed before
     * resolution, so it cannot know which column a name refers to. Two compilers
     * handed the same cache and different catalogs would therefore agree on the
     * key and disagree on the answer, and the second would be served the first's
     * plan: the same query text reading a different column.
     */
    private final String catalogKey;

    public TaqlCompiler(Catalog catalog) {
        this(catalog, new SqlServerGenerator(), Resolver.Options.DEFAULTS, 512);
    }

    public TaqlCompiler(Catalog catalog, Resolver.Options options, int cacheSize) {
        this(catalog, new SqlServerGenerator(), options, cacheSize);
    }

    /**
     * @param translator the language these queries are written into. One compiler
     *                   serves one translator: the shape key describes the query,
     *                   not the target, so two sharing a cache would collide.
     */
    public TaqlCompiler(Catalog catalog, QueryTranslator translator, Resolver.Options options, int cacheSize) {
        this(catalog, translator, new TaqlParser(), options, new LruPlanCache<>(cacheSize));
    }

    /**
     * Takes the caches themselves, so a deployment can supply Caffeine-backed
     * ones -- or a no-op pair, when compiling every time is preferable to
     * holding query text in memory.
     *
     * @param parser     carries the parse limits -- query length and nesting
     *                   depth -- which are a deployment's call and were not
     *                   reachable while parsing was static.
     * @param plans keyed on the query's shape, which is value-free by
     *              construction -- so nothing a caller asked about is retained
     *              between requests.
     */
    public TaqlCompiler(Catalog catalog, QueryTranslator translator, TaqlParser parser,
                        Resolver.Options options, PlanCache<String, Plan> plans) {
        this.catalog = catalog;
        this.translator = translator;
        this.parser = parser;
        this.options = options;
        this.plans = plans;
        this.catalogKey = fingerprintOf(catalog);
    }

    /** A plan together with the literal values of the specific query text it came from. */
    /**
     * A plan together with the query it came from.
     *
     * The parsed form is kept because it is what a {@link QueryPolicy} walks --
     * it holds the statement's shape and, since TAQL has no placeholders, the
     * constants too. Keeping it costs nothing: it is a function of the query
     * text, which is exactly what this record is cached under.
     */
    public record Compiled(Plan plan, Ast.Query parsed) {

        public List<Object> bind() {
            return Binder.resolve(plan, parsed.literals());
        }

        /** Carries no literal values; see {@link TaqlQuery#toString()}. */
        @Override
        public String toString() {
            return "Compiled[plan=" + plan.id() + ", literals=" + parsed.literals().size() + "]";
        }
    }

    public Compiled compile(String source) {
        Ast.Query parsed = parser.parse(source);
        Plan plan = plans.get(catalogKey + parsed.shapeKey(), shape -> {
            Resolver.Result resolved = Resolver.resolve(catalog, parsed, options, translator);
            Plan generated = translator.translate(resolved.query(), shape);
            log.debug("plan {} compiled, {} parameters\n{}",
                    generated.id(), generated.parameters().size(), generated.statement().stripTrailing());
            log.trace("plan {} has shape {}", generated.id(), shape);
            return generated;
        });
        return new Compiled(plan, parsed);
    }

    /** Parses and type-checks without consulting either cache -- for validation endpoints and tests. */
    public Plan compileUncached(String source) {
        Ast.Query parsed = parser.parse(source);
        Resolver.Result resolved = Resolver.resolve(catalog, parsed, options, translator);
        return translator.translate(resolved.query(), catalogKey + parsed.shapeKey());
    }

    public Resolved.Query analyse(String source) {
        return Resolver.resolve(catalog, parser.parse(source), options, translator).query();
    }

    /**
     * A short digest of everything in the catalog that can change generated SQL:
     * which entities exist, the tables behind them, the joins, and every field's
     * name, type and column.
     *
     * A digest rather than the catalog itself because this prefixes every cache
     * key; SHA-256 truncated to 64 bits, where a collision would mean serving
     * the wrong plan and is therefore not a place to economise further.
     */
    private static String fingerprintOf(Catalog catalog) {
        StringBuilder described = new StringBuilder();
        catalog.entities().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())      // Map.copyOf does not order
                .forEach(e -> described.append(e.getKey()).append('=').append(e.getValue()).append(';'));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(described.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(17);
            for (int i = 0; i < 8; i++) hex.append("%02x".formatted(digest[i]));
            return hex.append('|').toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("every JVM has SHA-256", impossible);
        }
    }

    public PlanCache<String, Plan> plans() {
        return plans;
    }

    public Catalog catalog() {
        return catalog;
    }

    public QueryTranslator translator() {
        return translator;
    }
}
