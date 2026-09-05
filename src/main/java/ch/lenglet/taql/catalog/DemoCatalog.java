package ch.lenglet.taql.catalog;

import ch.lenglet.taql.TaqlType;

import java.util.List;
import java.util.Map;

/**
 * The catalog for init.sql. In a real service this would be loaded from
 * configuration (or reflected from INFORMATION_SCHEMA plus a mapping file)
 * rather than written in Java.
 */
public final class DemoCatalog {

    private DemoCatalog() {}

    private static final Catalog.Table TRANSACTIONS = new Catalog.Table("dbo", "Transactions", "t");
    private static final Catalog.Table COUNTERPARTIES = new Catalog.Table("dbo", "Counterparties", "cp");
    private static final Catalog.Table TYPES = new Catalog.Table("dbo", "TransactionTypes", "tt");

    public static Catalog create() {
        Catalog.Entity transactions = new Catalog.Entity(
                "transactions",
                TRANSACTIONS,
                List.of(
                        new Catalog.Join("counterparty", COUNTERPARTIES, true,
                                "t.[CounterpartyId] = cp.[CounterpartyId]"),
                        new Catalog.Join("type", TYPES, true,
                                "t.[TransactionTypeId] = tt.[TransactionTypeId]")),
                List.of(
                        Catalog.Field.of("transactionId", TaqlType.STRING, "t", "TransactionId", "varchar(50)")
                                .withAliases("TransactionId", "id"),
                        Catalog.Field.of("clientId", TaqlType.STRING, "t", "ClientId", "varchar(50)")
                                .withAliases("ClientId"),
                        Catalog.Field.of("counterpartyId", TaqlType.STRING, "t", "CounterpartyId", "varchar(50)")
                                .withAliases("CounterpartyId"),
                        Catalog.Field.of("currency", TaqlType.STRING, "t", "Currency", "varchar(3)")
                                .withAliases("Currency"),
                        Catalog.Field.of("amount", TaqlType.DECIMAL, "t", "TransactionValue", "decimal(10,2)")
                                .withAliases("TransactionValue"),
                        Catalog.Field.of("date", TaqlType.DATE, "t", "TransactionDate", "date")
                                .withAliases("TransactionDate"),
                        Catalog.Field.of("direction", TaqlType.STRING, "t", "Direction", "varchar(1)")
                                .withAliases("Direction"),

                        // Reached through the counterparty join.
                        Catalog.Field.of("country", TaqlType.STRING, "cp", "Country", "varchar(2)")
                                .withAliases("Country"),
                        Catalog.Field.of("counterpartyName", TaqlType.STRING, "cp", "Name", "varchar(200)"),

                        // Reached through the transaction-type join.
                        Catalog.Field.of("transactionType", TaqlType.STRING, "tt", "Name", "varchar(50)")
                                .withAliases("TransactionType")));

        return new Catalog(Map.of("transactions", transactions));
    }
}
