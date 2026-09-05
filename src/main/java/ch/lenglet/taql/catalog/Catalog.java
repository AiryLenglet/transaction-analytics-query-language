package ch.lenglet.taql.catalog;

import ch.lenglet.taql.SqlType;
import ch.lenglet.taql.TaqlType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The semantic layer: logical field names -> physical columns, plus the joins
 * needed to reach them.
 *
 * This is not decoration. Your own examples group by {@code country} and
 * {@code transactionType}, neither of which is a column on Transactions, and
 * filter on {@code date}, which is spelled TransactionDate in the database. The
 * DSL is written against a business vocabulary, so something has to own the
 * mapping -- and that something is also the security boundary: an identifier
 * that does not resolve to a {@link Field} here never reaches the SQL
 * generator, so no user-supplied text is ever emitted as an identifier.
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

        public Optional<Field> field(String name) {
            for (Field f : fields) {
                if (f.name().equalsIgnoreCase(name)) return Optional.of(f);
                for (String alias : f.aliases()) {
                    if (alias.equalsIgnoreCase(name)) return Optional.of(f);
                }
            }
            return Optional.empty();
        }

        public Join join(String name) {
            for (Join j : joins) if (j.name().equals(name)) return j;
            throw new IllegalStateException("catalog is inconsistent: no join named " + name);
        }

        public List<String> fieldNames() {
            return fields.stream().map(Field::name).sorted().toList();
        }
    }

    public record Table(String schema, String name, String alias) {}

    /**
     * {@code on} is SQL authored by whoever defines the catalog, never by the
     * query author. It is the only place in the pipeline where raw SQL text is
     * accepted, and it is trusted configuration, not input.
     */
    public record Join(String name, Table table, boolean inner, String on) {}

    /**
     * @param tableAlias which table this column lives on -- the entity's own
     *                   alias, or a join name, which is what tells the planner
     *                   that using this field requires that join.
     * @param sqlType    the physical type, needed so bound parameters are sent
     *                   with a type that matches the column and does not force
     *                   an implicit conversion (which would kill index seeks).
     */
    public record Field(String name, List<String> aliases, TaqlType type,
                        String tableAlias, String column, SqlType sqlType) {

        public static Field of(String name, TaqlType type, String tableAlias, String column, SqlType sqlType) {
            return new Field(name, List.of(), type, tableAlias, column, sqlType);
        }

        public Field withAliases(String... aliases) {
            return new Field(name, List.of(aliases), type, tableAlias, column, sqlType);
        }
    }
}
