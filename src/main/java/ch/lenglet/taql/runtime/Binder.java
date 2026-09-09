package ch.lenglet.taql.runtime;

import ch.lenglet.taql.Diagnostic;
import ch.lenglet.taql.TaqlException;
import ch.lenglet.taql.TaqlType;
import ch.lenglet.taql.plan.Plan;

import java.math.BigDecimal;
import java.math.BigInteger;
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

/**
 * Turns a plan's parameter recipe plus a set of values into JDBC bindings.
 *
 * <h2>The plan's types are a contract, and this is where it is enforced</h2>
 * {@link Plan#variables()} publishes what each {@code $variable} must be, so a
 * REST layer can advertise it as the endpoint's schema. Whatever arrives is
 * whatever the caller's JSON deserialised to, so this is the boundary that has
 * to check it: a value that is not the advertised type is a
 * {@link TaqlException} naming the variable and what was expected, never a
 * coercion.
 *
 * That matters because the obvious implementation of "bind a string" --
 * {@code raw.toString()} -- accepts everything. A map, a list, an array, any
 * object at all would bind its rendering as the parameter: the query is still
 * parameterised and injection-proof, but it silently asks a question nobody
 * meant to ask, and {@code [I@6956de9} is not a value anyone intended to
 * compare a column against. The same trap sits in {@code Boolean.parseBoolean},
 * which answers {@code false} for {@code "yes"}, {@code "1"} and every other
 * string, and in {@code Number#longValue}, which turns 3.7 into 3.
 *
 * Text is still accepted for the non-text types, because JSON has no date and
 * one number type -- but it has to parse, exactly, or it is rejected.
 */
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
            out.add(switch (slot) {
                // Lifted by AstBuilder, so already the right shape; converted
                // anyway because the resolver may have retyped it to the column.
                case Plan.Auto a -> convert(literals.get(a.index()), a.type(), "this query's value");
                case Plan.Constant c -> c.value();
                case Plan.Variable v -> convert(require(variables, v.name()), v.type(), "$" + v.name());
                case Plan.VariableList v -> toJsonArray(require(variables, v.name()), v.elementType(), v.name());
            });
        }
        return out;
    }

    public static void apply(PreparedStatement statement, Plan plan, List<Object> values) throws SQLException {
        for (int i = 0; i < values.size(); i++) {
            Plan.ParamSlot slot = plan.parameters().get(i);
            Object value = values.get(i);
            int index = i + 1;
            if (value == null) {
                // The physical type, not the DSL type, is what the server expects.
                statement.setNull(index, slot.sqlType().jdbcType());
                continue;
            }
            switch (value) {
                // Sending a varchar column an NVARCHAR parameter makes SQL Server
                // convert the column rather than seek on it, so the decision is
                // made per parameter from its own type rather than by a
                // connection-wide sendStringParametersAsUnicode switch.
                case String s -> {
                    if (slot.sqlType().unicode()) statement.setNString(index, s);
                    else statement.setString(index, s);
                }
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
     *
     * @param what how to name this value if it is rejected -- a variable, an
     *             element of one, or a constant from the query text.
     */
    static Object convert(Object raw, TaqlType type, String what) {
        if (raw == null) return null;
        return switch (type.kind()) {
            case STRING -> text(raw, what);
            case INTEGER -> wholeNumber(raw, what);
            case DECIMAL -> number(raw, what, "a number");
            case DATE -> date(raw, what);
            case TIMESTAMP -> timestamp(raw, what);
            case BOOLEAN -> bool(raw, what);
            // Shredded element by element in toJsonArray, which types each one.
            case LIST -> raw;
            // Resolution rejects a variable it could not type, and a NULL
            // literal lowers to Tam.NullValue rather than a bindable slot.
            case NULL -> throw new IllegalStateException("no slot should ever bind as " + type);
        };
    }

    // ------------------------------------------------------------------
    // One converter per DSL type. Each accepts what a JSON body can carry for
    // it, and rejects everything else rather than rendering it.
    // ------------------------------------------------------------------

    private static String text(Object raw, String what) {
        if (raw instanceof CharSequence s) return s.toString();
        throw reject(what, raw, "text");
    }

    private static Long wholeNumber(Object raw, String what) {
        BigDecimal value = number(raw, what, "a whole number");
        try {
            return value.longValueExact();
        } catch (ArithmeticException notWhole) {
            // Either a fraction or wider than a long; both mean the caller did
            // not send the integer the plan asked for.
            throw reject(what, raw, "a whole number");
        }
    }

    private static BigDecimal number(Object raw, String what, String expected) {
        if (raw instanceof BigDecimal d) return d;
        if (raw instanceof BigInteger b) return new BigDecimal(b);
        // Via toString, so 0.1 stays 0.1 rather than becoming its binary expansion.
        if (raw instanceof Double || raw instanceof Float) return new BigDecimal(raw.toString());
        if (raw instanceof Number n) return BigDecimal.valueOf(n.longValue());
        if (raw instanceof CharSequence s) {
            try {
                return new BigDecimal(s.toString().trim());
            } catch (NumberFormatException notANumber) {
                throw reject(what, raw, expected);
            }
        }
        throw reject(what, raw, expected);
    }

    private static LocalDate date(Object raw, String what) {
        if (raw instanceof LocalDate d) return d;
        if (raw instanceof CharSequence s) {
            try {
                return LocalDate.parse(s.toString().trim());
            } catch (DateTimeParseException notADate) {
                throw reject(what, raw, "a date like 2019-12-31");
            }
        }
        throw reject(what, raw, "a date like 2019-12-31");
    }

    private static LocalDateTime timestamp(Object raw, String what) {
        if (raw instanceof LocalDateTime d) return d;
        if (raw instanceof CharSequence s) {
            try {
                return LocalDateTime.parse(s.toString().trim());
            } catch (DateTimeParseException notATimestamp) {
                throw reject(what, raw, "a timestamp like 2019-12-31T23:59:59");
            }
        }
        throw reject(what, raw, "a timestamp like 2019-12-31T23:59:59");
    }

    private static Boolean bool(Object raw, String what) {
        if (raw instanceof Boolean b) return b;
        if (raw instanceof CharSequence s) {
            String value = s.toString().trim();
            if (value.equalsIgnoreCase("true")) return Boolean.TRUE;
            if (value.equalsIgnoreCase("false")) return Boolean.FALSE;
        }
        throw reject(what, raw, "true or false");
    }

    private static TaqlException reject(String what, Object raw, String expected) {
        return new TaqlException(new Diagnostic(Diagnostic.Phase.TYPE, 0, 0,
                what + " expects " + expected + ", got " + describe(raw)));
    }

    /**
     * Names the offending value. Scalars are quoted back because that is what
     * makes the error actionable; anything else is named by type only -- echoing
     * a whole structure into a message that may be logged or returned is how
     * caller data ends up somewhere it was never meant to be.
     */
    private static String describe(Object raw) {
        String type = raw.getClass().getSimpleName();
        if (raw instanceof CharSequence || raw instanceof Number || raw instanceof Boolean) {
            String value = raw.toString();
            if (value.length() > 40) value = value.substring(0, 40) + "...";
            return type + " '" + value + "'";
        }
        return type;
    }

    /** A list variable travels as one JSON array parameter; see SqlServerGenerator.inVariable. */
    private static String toJsonArray(Object raw, TaqlType elementType, String name) {
        if (!(raw instanceof Collection<?> items)) {
            throw new TaqlException(new Diagnostic(Diagnostic.Phase.TYPE, 0, 0,
                    "$" + name + " must be a list, got " + describe(raw)));
        }
        if (items.isEmpty()) {
            throw new TaqlException(new Diagnostic(Diagnostic.Phase.TYPE, 0, 0,
                    "$" + name + " must not be empty"));
        }
        StringJoiner json = new StringJoiner(",", "[", "]");
        int index = 0;
        for (Object item : items) {
            json.add(jsonScalar(convert(item, elementType, "$" + name + "[" + index++ + "]")));
        }
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
}
