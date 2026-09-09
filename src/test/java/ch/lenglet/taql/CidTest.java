package ch.lenglet.taql;

import ch.lenglet.taql.ast.Ast;
import ch.lenglet.taql.ast.TaqlParserFacade;
import ch.lenglet.taql.catalog.DemoCatalog;
import ch.lenglet.taql.runtime.SqlFailure;
import ch.lenglet.taql.runtime.TaqlExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Client-identifying data must not be reachable by accident.
 *
 * A query's constants and a caller's variable values are the client ids,
 * amounts and names the question was asked about. The library never logs them --
 * but "never logs them" is a property of today's call sites, and call sites get
 * added. These tests cover the types that <em>hold</em> such values, so that
 * rendering one cannot expose them however it is reached.
 */
@DisplayName("client-identifying data")
class CidTest {

    private static final String CID = "CH-90210-SECRET";

    private final TaqlCompiler compiler = new TaqlCompiler(DemoCatalog.create());

    @Test
    void aQueryDoesNotRenderItsValuesOrItsSource() {
        // A record's generated toString would print both, so one
        // log.debug("{}", query) would be a disclosure.
        TaqlQuery query = TaqlQuery.of("list { TransactionId } over { ClientId = '" + CID + "' }",
                Map.of("other", CID));

        assertFalse(query.toString().contains(CID), query.toString());
        // Variable names are safe: the query author wrote them, and they are
        // part of the published contract.
        assertTrue(query.toString().contains("other"), query.toString());
    }

    @Test
    void aCompiledQueryDoesNotRenderItsLiteralTable() {
        var compiled = compiler.compile("list { TransactionId } over { ClientId = '" + CID + "' }");
        assertFalse(compiled.toString().contains(CID), compiled.toString());
        // The value is still bindable -- hidden from rendering, not from use.
        assertTrue(compiled.bind().contains(CID));
    }

    @Test
    void aParsedQueryDoesNotRenderTheLiftedLiterals() {
        Ast.Query parsed = TaqlParserFacade.parse("list { TransactionId } over { ClientId = '" + CID + "' }");
        assertFalse(parsed.toString().contains(CID), parsed.toString());
    }

    @Test
    void theGeneratedSqlAndShapeKeyAreValueFreeSoBothAreSafeToLog() {
        // This is what the library does log, and why it is allowed to.
        var plan = compiler.compileUncached("list { TransactionId } over { ClientId = '" + CID + "' }");
        assertFalse(plan.sql().contains(CID), plan.sql());
        assertFalse(plan.shapeKey().contains(CID), plan.shapeKey());
    }

    @Test
    void anExecutionFailureLogsNoServerMessage() {
        // SQL Server quotes values back: error 245 is "Conversion failed when
        // converting the varchar value '...'". That message is localised too,
        // so it was never the diagnosis -- the code and state are.
        SQLException quotesTheValue = new SQLException(
                "Conversion failed when converting the varchar value '" + CID + "' to data type int",
                "22018", 245);
        var failure = new TaqlExecutionException(SqlFailure.classify(quotesTheValue), quotesTheValue, 1);

        assertFalse(failure.logDetail().contains(CID), failure.logDetail());
        assertFalse(failure.getMessage().contains(CID), failure.getMessage());
        assertTrue(failure.logDetail().contains("errorNumber=245"), failure.logDetail());
        // Still reachable for a deployment that has decided where it may go.
        assertTrue(failure.databaseMessage().contains(CID));
    }
}
