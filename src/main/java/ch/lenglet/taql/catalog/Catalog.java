package ch.lenglet.taql.catalog;

import ch.lenglet.taql.SqlType;
import ch.lenglet.taql.TaqlType;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The semantic layer: logical field names -> physical columns, plus the joins
 * needed to reach them.
 *
 * The DSL says {@code date} and {@code amount} where the columns are
 * TransactionDate and TransactionValue, and it is the catalog that decides which
 * names exist at all -- which makes it the security boundary too: an identifier
 * that does not resolve to a {@link Field} here never reaches the SQL generator,
 * so no user-supplied text is ever emitted as an identifier.
 *
 * Nothing here names a SQL table alias. A field says which <em>join</em> it is
 * reached through, a join says which <em>columns</em> it matches on, and the
 * generator allocates the aliases when it emits the statement. That keeps the
 * catalog free of SQL text entirely -- there is no longer any string in this
 * file that ends up in a query verbatim.
 */
public record Catalog(Map<String, Entity> entities) {

    public Catalog {
        Map<String, Entity> normalized = new LinkedHashMap<>();
        entities.forEach((k, v) -> normalized.put(k.toLowerCase(Locale.ROOT), v));
        entities = Map.copyOf(normalized);
    }

    public Optional<Entity> entity(String name) {
        return Optional.ofNullable(entities.get(name.toLowerCase(Locale.ROOT)));
    }

    /** Used when a query omits 'from'. */
    public Entity defaultEntity() {
        return entities.values().iterator().next();
    }

    public record Entity(String name, Table table, List<Join> joins, List<Field> fields) {

        public Entity {
            joins = List.copyOf(joins);
            fields = List.copyOf(fields);

            Set<String> joinNames = new LinkedHashSet<>();
            for (Join j : joins) {
                if (!joinNames.add(j.name())) {
                    throw new IllegalArgumentException(name + ": duplicate join '" + j.name() + "'");
                }
            }
            // Fail where the catalog is written rather than where a query uses it.
            for (Field f : fields) {
                if (!f.isJoined()) continue;
                if (!joinNames.contains(f.source())) {
                    throw new IllegalArgumentException(
                            name + ": field '" + f.name() + "' is reached through unknown join '" + f.source() + "'");
                }
            }
        }

        /** Case-insensitive, but one spelling per field: there are no alternate names. */
        public Optional<Field> field(String name) {
            for (Field f : fields) {
                if (f.name().equalsIgnoreCase(name)) return Optional.of(f);
            }
            return Optional.empty();
        }

        public List<String> fieldNames() {
            return fields.stream().map(Field::name).sorted().toList();
        }
    }

    /** A physical table. No alias: the generator assigns one per statement. */
    public record Table(String schema, String name) {}

    /**
     * An equi-join from the entity's own table to another table.
     *
     * Expressed as column pairs rather than a SQL condition, so the aliases can
     * be chosen at generation time -- and so that the catalog holds no SQL text
     * to be trusted. Only joins to the entity's root table are modelled; a join
     * that depends on another join has no representation here.
     */
    public record Join(String name, Table table, boolean inner, List<On> on) {

        public Join {
            on = List.copyOf(on);
            if (on.isEmpty()) throw new IllegalArgumentException("join '" + name + "' has no join columns");
        }

        /** {@code <root>.[rootColumn] = <joined>.[joinedColumn]} */
        public record On(String rootColumn, String joinedColumn) {}

        public static Join inner(String name, Table table, String rootColumn, String joinedColumn) {
            return new Join(name, table, true, List.of(new On(rootColumn, joinedColumn)));
        }

        public static Join left(String name, Table table, String rootColumn, String joinedColumn) {
            return new Join(name, table, false, List.of(new On(rootColumn, joinedColumn)));
        }
    }

    /**
     * A field has exactly one name. Accepting several spellings for one column
     * would mean the same query could be written more than one way, and since
     * the plan cache is keyed on the query as written -- it has to be, the key
     * is computed before name resolution -- each spelling would compile its own
     * plan for identical SQL.
     *
     * @param name    the name the DSL uses. Free to differ from {@code column};
     *                that indirection is the point of a semantic layer.
     * @param source  empty for a column on the entity's own table, otherwise the
     *                name of the {@link Join} it is reached through. Resolving a
     *                field with a source is what pulls that join into the plan.
     * @param sqlType the physical type, so bound parameters are sent with a type
     *                that matches the column instead of forcing a conversion.
     */
    public record Field(String name, TaqlType type, String source, String column, SqlType sqlType) {

        /** A column on the entity's own table, exposed under the column's own name. */
        public static Field of(String name, TaqlType type, SqlType sqlType) {
            return of(name, type, name, sqlType);
        }

        /** A column on the entity's own table, exposed under a different name. */
        public static Field of(String name, TaqlType type, String column, SqlType sqlType) {
            return new Field(name, type, "", column, sqlType);
        }

        /** A column reached through {@code join}. */
        public static Field from(String join, String name, TaqlType type, String column, SqlType sqlType) {
            return new Field(name, type, join, column, sqlType);
        }

        public boolean isJoined() {
            return !source.isEmpty();
        }
    }
}
