package ch.lenglet.taql;

import java.sql.Types;

/**
 * A T-SQL column type.
 *
 * This is the physical counterpart to {@link TaqlType}: the DSL reasons in
 * {@code string} and {@code date}, the database in {@code varchar(50)} and
 * {@code date}, and parameters have to be bound in terms of the latter. Modelled
 * as a closed hierarchy rather than a string for three reasons:
 *
 *   1. {@link #sql()} is the only way a type reaches generated SQL. The
 *      {@code OPENJSON ... WITH} clause has to name a type, and rendering one
 *      from a record beats splicing in whatever string the catalog held.
 *   2. Whether a type is Unicode is a property of the type, so {@link #unicode()}
 *      can drive {@code setNString} vs {@code setString} per parameter instead
 *      of relying on a connection-wide switch.
 *   3. A catalog entry with a typo'd type no longer compiles.
 *
 * Not exhaustive T-SQL -- just the types this POC can bind.
 */
public sealed interface SqlType {

    /** T-SQL rendering, e.g. {@code varchar(50)}. */
    String sql();

    /** The {@link Types} constant to use when binding a NULL in this position. */
    int jdbcType();

    /** True for the national character types, which must be bound as NVARCHAR. */
    default boolean unicode() {
        return false;
    }

    /** Length sentinel for {@code varchar(max)} and friends. */
    int MAX = -1;

    // ---------------- character ----------------

    record Char(int length) implements SqlType {
        public Char {
            requireLength(length, false);
        }

        @Override public String sql() { return "char(" + length + ")"; }
        @Override public int jdbcType() { return Types.CHAR; }
    }

    record VarChar(int length) implements SqlType {
        public VarChar {
            requireLength(length, true);
        }

        @Override public String sql() { return "varchar(" + renderLength(length) + ")"; }
        @Override public int jdbcType() { return Types.VARCHAR; }
    }

    record NChar(int length) implements SqlType {
        public NChar {
            requireLength(length, false);
        }

        @Override public String sql() { return "nchar(" + length + ")"; }
        @Override public int jdbcType() { return Types.NCHAR; }
        @Override public boolean unicode() { return true; }
    }

    record NVarChar(int length) implements SqlType {
        public NVarChar {
            requireLength(length, true);
        }

        @Override public String sql() { return "nvarchar(" + renderLength(length) + ")"; }
        @Override public int jdbcType() { return Types.NVARCHAR; }
        @Override public boolean unicode() { return true; }
    }

    // ---------------- numeric ----------------

    record TinyInt() implements SqlType {
        @Override public String sql() { return "tinyint"; }
        @Override public int jdbcType() { return Types.TINYINT; }
    }

    record SmallInt() implements SqlType {
        @Override public String sql() { return "smallint"; }
        @Override public int jdbcType() { return Types.SMALLINT; }
    }

    record Int() implements SqlType {
        @Override public String sql() { return "int"; }
        @Override public int jdbcType() { return Types.INTEGER; }
    }

    record BigInt() implements SqlType {
        @Override public String sql() { return "bigint"; }
        @Override public int jdbcType() { return Types.BIGINT; }
    }

    record Decimal(int precision, int scale) implements SqlType {
        public Decimal {
            if (precision < 1 || precision > 38) {
                throw new IllegalArgumentException("decimal precision must be 1..38, got " + precision);
            }
            if (scale < 0 || scale > precision) {
                throw new IllegalArgumentException("decimal scale must be 0.." + precision + ", got " + scale);
            }
        }

        @Override public String sql() { return "decimal(" + precision + "," + scale + ")"; }
        @Override public int jdbcType() { return Types.DECIMAL; }
    }

    record Float() implements SqlType {
        @Override public String sql() { return "float"; }
        @Override public int jdbcType() { return Types.DOUBLE; }
    }

    record Real() implements SqlType {
        @Override public String sql() { return "real"; }
        @Override public int jdbcType() { return Types.REAL; }
    }

    record Bit() implements SqlType {
        @Override public String sql() { return "bit"; }
        @Override public int jdbcType() { return Types.BIT; }
    }

    // ---------------- temporal ----------------

    record Date() implements SqlType {
        @Override public String sql() { return "date"; }
        @Override public int jdbcType() { return Types.DATE; }
    }

    record Time(int scale) implements SqlType {
        public Time {
            requireScale(scale);
        }

        @Override public String sql() { return "time(" + scale + ")"; }
        @Override public int jdbcType() { return Types.TIME; }
    }

    record DateTime2(int scale) implements SqlType {
        public DateTime2 {
            requireScale(scale);
        }

        @Override public String sql() { return "datetime2(" + scale + ")"; }
        @Override public int jdbcType() { return Types.TIMESTAMP; }
    }

    // ---------------- other ----------------

    record UniqueIdentifier() implements SqlType {
        @Override public String sql() { return "uniqueidentifier"; }
        @Override public int jdbcType() { return Types.CHAR; }
    }

    // ---------------- shared validation ----------------

    private static void requireLength(int length, boolean maxAllowed) {
        if (length == MAX && maxAllowed) return;
        if (length < 1 || length > 8000) {
            throw new IllegalArgumentException(
                    "length must be 1..8000" + (maxAllowed ? " or SqlType.MAX" : "") + ", got " + length);
        }
    }

    private static void requireScale(int scale) {
        if (scale < 0 || scale > 7) {
            throw new IllegalArgumentException("fractional seconds scale must be 0..7, got " + scale);
        }
    }

    private static String renderLength(int length) {
        return length == MAX ? "max" : String.valueOf(length);
    }
}
