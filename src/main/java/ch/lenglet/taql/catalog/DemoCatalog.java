package ch.lenglet.taql.catalog;

import ch.lenglet.taql.TaqlType;
import ch.lenglet.taql.sqlserver.SqlType;

import java.util.List;
import java.util.Map;

/**
 * The catalog for init.sql: one table, no joins.
 *
 * The DSL vocabulary is the column names, so every field here is exposed under
 * the name it already has. Lookup is case-insensitive, so a query may write
 * {@code clientId} or {@code ClientId}; it is still one field with one name.
 *
 * Nothing forces this 1:1 mapping -- {@link Catalog.Field#of(String, TaqlType,
 * String, SqlType)} exposes a column under a different name when the business
 * vocabulary and the schema disagree. In a real service this would be
 * configuration rather than Java.
 */
public final class DemoCatalog {

    private DemoCatalog() {}

    private static final Catalog.Table TRANSACTIONS = new Catalog.Table("dbo", "Transactions");

    public static Catalog create() {
        Catalog.Entity transactions = new Catalog.Entity(
                "transactions",
                TRANSACTIONS,
                List.of(),
                List.of(
                        Catalog.Field.of("TransactionId", TaqlType.STRING, new SqlType.VarChar(50)),
                        Catalog.Field.of("ClientId", TaqlType.STRING, new SqlType.VarChar(50)),
                        Catalog.Field.of("CounterpartyId", TaqlType.STRING, new SqlType.VarChar(50)),
                        Catalog.Field.of("CounterpartyName", TaqlType.STRING, new SqlType.VarChar(200)),
                        Catalog.Field.of("Country", TaqlType.STRING, new SqlType.VarChar(2)),
                        Catalog.Field.of("TransactionType", TaqlType.STRING, new SqlType.VarChar(50)),
                        Catalog.Field.of("Currency", TaqlType.STRING, new SqlType.VarChar(3)),
                        Catalog.Field.of("TransactionValue", TaqlType.DECIMAL, new SqlType.Decimal(10, 2)),
                        Catalog.Field.of("TransactionDate", TaqlType.DATE, new SqlType.Date()),
                        Catalog.Field.of("Direction", TaqlType.STRING, new SqlType.VarChar(1))));

        return new Catalog(Map.of("transactions", transactions));
    }
}
