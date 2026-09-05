package ch.lenglet.taql.runtime;

import ch.lenglet.taql.Diagnostic;
import ch.lenglet.taql.TaqlException;
import ch.lenglet.taql.TaqlType;
import ch.lenglet.taql.plan.Plan;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/** Turns a plan's parameter recipe plus a set of values into JDBC bindings. */
public final class Binder {

    private Binder() {}

    /**
     * Resolves every slot to a Java value, in JDBC order.
     *
     * @param literals  the literal table of the query text being executed
     * @param variables caller-supplied $variables
     */
    public static List<Object> resolve(Plan plan, List<Object> literals, Map<String, Object> variables) {
        List<Object> out = new ArrayList<>(plan.parameters().size());
        for (Plan.ParamSlot slot : plan.parameters()) {
            switch (slot) {
                case Plan.Auto a -> out.add(convert(literals.get(a.index()), a.type()));
                case Plan.Constant c -> out.add(c.value());
                case Plan.Variable v -> out.add(convert(require(variables, v.name()), v.type()));
                case Plan.VariableList v -> out.add(toJsonArray(require(variables, v.name()), v.elementType(), v.name()));
            }
        }
        return out;
    }

    public static void apply(PreparedStatement statement, Plan plan, List<Object> values) throws SQLException {
        for (int i = 0; i < values.size(); i++) {
            Plan.ParamSlot slot = plan.parameters().get(i);
            Object value = values.get(i);
            int index = i + 1;
            if (value == null) {
                statement.setNull(index, jdbcType(slot.type()));
                continue;
            }
            switch (value) {
                case String s -> statement.setString(index, s);
                case Long l -> statement.setLong(index, l);
                case Integer n -> statement.setInt(index, n);
                case BigDecimal d -> statement.setBigDecimal(index, d);
                case Boolean b -> statement.setBoolean(index, b);
                case LocalDate d -> statement.setObject(index, d, Types.DATE);
                case LocalDateTime d -> statement.setObject(index, d, Types.TIMESTAMP);
                default -> statement.setObject(index, value);
            }
        }
    }

    private static Object require(Map<String, Object> variables, String name) {
        if (!variables.containsKey(name)) {
            throw new TaqlException(new Diagnostic(Diagnostic.Phase.TYPE, 0, 0,
                    "missing value for query variable $" + name));
        }
        return variables.get(name);
    }

    /**
     * Coercion happens here, not in the database. Sending '2010-01-01' as text
     * into a date comparison would make SQL Server convert per row; sending a
     * real date lets it seek.
     */
    static Object convert(Object raw, TaqlType type) {
        if (raw == null) return null;
        try {
            return switch (type.kind()) {
                case STRING -> raw.toString();
                case INTEGER -> raw instanceof Number n ? n.longValue() : Long.parseLong(raw.toString().trim());
                case DECIMAL -> raw instanceof BigDecimal d ? d : new BigDecimal(raw.toString().trim());
                case DATE -> raw instanceof LocalDate d ? d : LocalDate.parse(raw.toString().trim());
                case TIMESTAMP -> raw instanceof LocalDateTime d ? d : LocalDateTime.parse(raw.toString().trim());
                case BOOLEAN -> raw instanceof Boolean b ? b : Boolean.parseBoolean(raw.toString().trim());
                case NULL -> raw.toString();
                case LIST -> raw;
            };
        } catch (NumberFormatException | DateTimeParseException e) {
            throw new TaqlException(new Diagnostic(Diagnostic.Phase.TYPE, 0, 0,
                    "'" + raw + "' is not a valid " + type));
        }
    }

    /** A list variable travels as one JSON array parameter; see SqlServerGenerator.inVariable. */
    private static String toJsonArray(Object raw, TaqlType elementType, String name) {
        if (!(raw instanceof Collection<?> items)) {
            throw new TaqlException(new Diagnostic(Diagnostic.Phase.TYPE, 0, 0,
                    "$" + name + " must be a list"));
        }
        if (items.isEmpty()) {
            throw new TaqlException(new Diagnostic(Diagnostic.Phase.TYPE, 0, 0,
                    "$" + name + " must not be empty"));
        }
        StringJoiner json = new StringJoiner(",", "[", "]");
        for (Object item : items) json.add(jsonScalar(convert(item, elementType)));
        return json.toString();
    }

    private static String jsonScalar(Object value) {
        if (value == null) return "null";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        String s = value.toString();
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    private static int jdbcType(TaqlType type) {
        return switch (type.kind()) {
            case STRING, NULL, LIST -> Types.VARCHAR;
            case INTEGER -> Types.BIGINT;
            case DECIMAL -> Types.DECIMAL;
            case DATE -> Types.DATE;
            case TIMESTAMP -> Types.TIMESTAMP;
            case BOOLEAN -> Types.BIT;
        };
    }
}
