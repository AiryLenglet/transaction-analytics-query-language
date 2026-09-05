package ch.lenglet.taql.catalog;

import ch.lenglet.taql.SqlType;
import ch.lenglet.taql.TaqlType;

import java.util.List;
import java.util.Map;

/**
 * The catalog for init.sql: one table, no joins.
 *
 * The mapping still earns its keep even without joins -- the DSL says
 * {@code date} and {@code amount} where the columns are TransactionDate and
 * TransactionValue -- and it is still the boundary that decides which names
 * exist at all. In a real service this would be configuration rather than Java.
 */
public final class DemoCatalog {

    private DemoCatalog() {}

    private static final Catalog.Table TRANSACTIONS = new Catalog.Table("dbo", "Transactions", "t");

    public static Catalog create() {
        Catalog.Entity transactions = new Catalog.Entity(
                "transactions",
                TRANSACTIONS,
                List.of(),
                List.of(
                        Catalog.Field.of("transactionId", TaqlType.STRING, "t", "TransactionId", new SqlType.VarChar(50))
                                .withAliases("TransactionId", "id"),
                        Catalog.Field.of("clientId", TaqlType.STRING, "t", "ClientId", new SqlType.VarChar(50))
                                .withAliases("ClientId"),
                        Catalog.Field.of("counterpartyId", TaqlType.STRING, "t", "CounterpartyId", new SqlType.VarChar(50))
                                .withAliases("CounterpartyId"),
                        Catalog.Field.of("counterpartyName", TaqlType.STRING, "t", "CounterpartyName", new SqlType.VarChar(200))
                                .withAliases("CounterpartyName", "counterparty"),
                        Catalog.Field.of("country", TaqlType.STRING, "t", "Country", new SqlType.VarChar(2))
                                .withAliases("Country"),
                        Catalog.Field.of("transactionType", TaqlType.STRING, "t", "TransactionType", new SqlType.VarChar(50))
                                .withAliases("TransactionType"),
                        Catalog.Field.of("currency", TaqlType.STRING, "t", "Currency", new SqlType.VarChar(3))
                                .withAliases("Currency"),
                        Catalog.Field.of("amount", TaqlType.DECIMAL, "t", "TransactionValue", new SqlType.Decimal(10, 2))
                                .withAliases("TransactionValue"),
                        Catalog.Field.of("date", TaqlType.DATE, "t", "TransactionDate", new SqlType.Date())
                                .withAliases("TransactionDate"),
                        Catalog.Field.of("direction", TaqlType.STRING, "t", "Direction", new SqlType.VarChar(1))
                                .withAliases("Direction")));

        return new Catalog(Map.of("transactions", transactions));
    }
}
