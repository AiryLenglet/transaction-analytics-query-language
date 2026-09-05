package ch.lenglet.taql.catalog;

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
                        Catalog.Field.of("transactionId", TaqlType.STRING, "t", "TransactionId", "varchar(50)")
                                .withAliases("TransactionId", "id"),
                        Catalog.Field.of("clientId", TaqlType.STRING, "t", "ClientId", "varchar(50)")
                                .withAliases("ClientId"),
                        Catalog.Field.of("counterpartyId", TaqlType.STRING, "t", "CounterpartyId", "varchar(50)")
                                .withAliases("CounterpartyId"),
                        Catalog.Field.of("counterpartyName", TaqlType.STRING, "t", "CounterpartyName", "varchar(200)")
                                .withAliases("CounterpartyName", "counterparty"),
                        Catalog.Field.of("country", TaqlType.STRING, "t", "Country", "varchar(2)")
                                .withAliases("Country"),
                        Catalog.Field.of("transactionType", TaqlType.STRING, "t", "TransactionType", "varchar(50)")
                                .withAliases("TransactionType"),
                        Catalog.Field.of("currency", TaqlType.STRING, "t", "Currency", "varchar(3)")
                                .withAliases("Currency"),
                        Catalog.Field.of("amount", TaqlType.DECIMAL, "t", "TransactionValue", "decimal(10,2)")
                                .withAliases("TransactionValue"),
                        Catalog.Field.of("date", TaqlType.DATE, "t", "TransactionDate", "date")
                                .withAliases("TransactionDate"),
                        Catalog.Field.of("direction", TaqlType.STRING, "t", "Direction", "varchar(1)")
                                .withAliases("Direction")));

        return new Catalog(Map.of("transactions", transactions));
    }
}
