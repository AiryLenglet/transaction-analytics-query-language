package ch.lenglet.taql;

/**
 * The DSL's type lattice. Deliberately small and independent of JDBC: the
 * catalog maps physical SQL types onto these, and the SQL generator maps them
 * back out. Nothing in between needs to know about SQL Server.
 */
public record TaqlType(Kind kind, TaqlType element) {

    public enum Kind { STRING, INTEGER, DECIMAL, DATE, TIMESTAMP, BOOLEAN, NULL, LIST }

    public static final TaqlType STRING    = new TaqlType(Kind.STRING, null);
    public static final TaqlType INTEGER   = new TaqlType(Kind.INTEGER, null);
    public static final TaqlType DECIMAL   = new TaqlType(Kind.DECIMAL, null);
    public static final TaqlType DATE      = new TaqlType(Kind.DATE, null);
    public static final TaqlType TIMESTAMP = new TaqlType(Kind.TIMESTAMP, null);
    public static final TaqlType BOOLEAN   = new TaqlType(Kind.BOOLEAN, null);
    public static final TaqlType NULL      = new TaqlType(Kind.NULL, null);

    public static TaqlType listOf(TaqlType element) {
        return new TaqlType(Kind.LIST, element);
    }

    public boolean isNumeric() {
        return kind == Kind.INTEGER || kind == Kind.DECIMAL;
    }

    public boolean isTemporal() {
        return kind == Kind.DATE || kind == Kind.TIMESTAMP;
    }

    public boolean isList() {
        return kind == Kind.LIST;
    }

    @Override
    public String toString() {
        return kind == Kind.LIST ? "list<" + element + ">" : kind.name().toLowerCase();
    }
}
