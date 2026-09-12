package ch.lenglet.taql;

import ch.lenglet.taql.execution.CircuitBreakingPlanRunner;
import ch.lenglet.taql.execution.FailureCategory;
import ch.lenglet.taql.execution.RetryingPlanRunner;
import ch.lenglet.taql.execution.TaqlExecutionException;
import ch.lenglet.taql.spi.PlanRunner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Retrying and circuit breaking, which are the code that only runs when
 * something is already wrong -- so the code least likely to be exercised by
 * accident, and most expensive to get wrong.
 */
@DisplayName("resilience")
class ResiliencePlanRunnerTest {

    private static final List<Map<String, Object>> ROWS = List.of(Map.of("TransactionId", "T1"));

    /**
     * A runner playing a scripted sequence, counting how many times it was
     * asked. Once the script runs out it succeeds, so a test only has to write
     * down the failures it cares about.
     */
    private static final class Scripted implements PlanRunner {
        private final List<FailureCategory> script = new ArrayList<>();
        int calls;

        Scripted failing(FailureCategory... sequence) {
            script.addAll(Arrays.asList(sequence));
            return this;
        }

        Scripted thenSucceeds() {
            script.add(null);
            return this;
        }

        @Override
        public List<Map<String, Object>> run(Plan plan, List<Object> values) {
            FailureCategory next = calls < script.size() ? script.get(calls) : null;
            calls++;
            if (next == null) return ROWS;
            throw new TaqlExecutionException(next, "test", new RuntimeException("scripted"), 1);
        }
    }

    private static List<Map<String, Object>> run(PlanRunner runner) {
        return runner.run(null, List.of());
    }

    private static TaqlExecutionException failing(PlanRunner runner) {
        return assertThrows(TaqlExecutionException.class, () -> run(runner));
    }

    // ==================================================================
    @Nested
    @DisplayName("retrying")
    class Retrying {

        private static final RetryingPlanRunner.Options FAST = new RetryingPlanRunner.Options(3, 1);

        @Test
        void triesAgainAfterAFailureThatMightNotRecur() {
            var store = new Scripted().failing(FailureCategory.RETRYABLE);
            assertEquals(ROWS, run(new RetryingPlanRunner(store, FAST)));
            assertEquals(2, store.calls, "should have tried again after the deadlock");
        }

        @Test
        void givesUpAfterTheConfiguredAttempts() {
            var store = new Scripted().failing(
                    FailureCategory.RETRYABLE, FailureCategory.RETRYABLE, FailureCategory.RETRYABLE);
            TaqlExecutionException e = failing(new RetryingPlanRunner(store, FAST));

            assertEquals(3, store.calls);
            // The count covers the whole call, not the one attempt below.
            assertEquals(3, e.attempts());
            assertTrue(e.logDetail().contains("attempts=3"), e.logDetail());
        }

        @Test
        void doesNotTryAgainWhenTheSameQueryWouldFailTheSameWay() {
            // A timeout will take just as long again; bad data stays bad.
            for (FailureCategory hopeless : List.of(FailureCategory.TIMEOUT,
                    FailureCategory.INVALID_DATA, FailureCategory.SCHEMA_MISMATCH,
                    FailureCategory.PERMISSION, FailureCategory.UNKNOWN)) {
                var store = new Scripted().failing(hopeless, hopeless, hopeless);
                failing(new RetryingPlanRunner(store, FAST));
                assertEquals(1, store.calls, hopeless + " must not be retried");
            }
        }

        @Test
        void maxAttemptsOfOneMeansNoRetrying() {
            var store = new Scripted().failing(FailureCategory.RETRYABLE);
            failing(new RetryingPlanRunner(store, new RetryingPlanRunner.Options(1, 1)));
            assertEquals(1, store.calls);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("circuit breaking")
    class Breaking {

        private static final CircuitBreakingPlanRunner.Options QUICK =
                new CircuitBreakingPlanRunner.Options(3, Duration.ofMillis(60));

        @Test
        void opensAfterEnoughConsecutiveStoreFailures() {
            var store = new Scripted().failing(FailureCategory.RETRYABLE, FailureCategory.RETRYABLE,
                    FailureCategory.RETRYABLE, FailureCategory.RETRYABLE);
            var breaker = new CircuitBreakingPlanRunner(store, QUICK);

            for (int i = 0; i < 3; i++) failing(breaker);
            assertEquals(3, store.calls);

            // The fourth is refused without asking the store at all.
            TaqlExecutionException open = failing(breaker);
            assertEquals(FailureCategory.UNAVAILABLE, open.failure());
            assertEquals(3, store.calls, "an open circuit must not reach the store");
        }

        @Test
        void aSuccessResetsTheCount() {
            // Consecutive, not cumulative: the question a breaker asks is
            // whether the store is answering, and one answer settles it.
            var store = new Scripted()
                    .failing(FailureCategory.RETRYABLE, FailureCategory.RETRYABLE)
                    .thenSucceeds()
                    .failing(FailureCategory.RETRYABLE, FailureCategory.RETRYABLE);
            var breaker = new CircuitBreakingPlanRunner(store, QUICK);

            failing(breaker);
            failing(breaker);
            assertEquals(ROWS, run(breaker));
            failing(breaker);
            failing(breaker);

            // Four failures, never three in a row, so the store is still asked.
            assertEquals(ROWS, run(breaker));
            assertEquals(6, store.calls);
        }

        @Test
        void oneCallersBadQueryDoesNotShedEveryoneElsesTraffic() {
            // A malformed value or a missing permission fails every time and
            // says nothing about the store's health.
            for (FailureCategory theirFault : List.of(FailureCategory.INVALID_DATA,
                    FailureCategory.SCHEMA_MISMATCH, FailureCategory.PERMISSION,
                    FailureCategory.TIMEOUT, FailureCategory.UNKNOWN)) {
                var store = new Scripted().failing(theirFault, theirFault, theirFault, theirFault);
                var breaker = new CircuitBreakingPlanRunner(store, QUICK);
                for (int i = 0; i < 4; i++) failing(breaker);
                assertEquals(4, store.calls, theirFault + " must not open the circuit");
            }
        }

        @Test
        void letsOneQueryThroughOnceItHasWaited() throws Exception {
            var store = new Scripted().failing(
                    FailureCategory.RETRYABLE, FailureCategory.RETRYABLE, FailureCategory.RETRYABLE);
            var breaker = new CircuitBreakingPlanRunner(store, QUICK);
            for (int i = 0; i < 3; i++) failing(breaker);
            assertEquals(FailureCategory.UNAVAILABLE, failing(breaker).failure());

            Thread.sleep(80);

            // The script is spent, so the probe succeeds and the circuit closes.
            assertEquals(ROWS, run(breaker));
            assertEquals(4, store.calls);
            assertEquals(ROWS, run(breaker), "a closed circuit passes everything");
        }

        @Test
        void aFailedProbeOpensItAgainImmediately() throws Exception {
            var store = new Scripted().failing(FailureCategory.RETRYABLE, FailureCategory.RETRYABLE,
                    FailureCategory.RETRYABLE, FailureCategory.RETRYABLE);
            var breaker = new CircuitBreakingPlanRunner(store, QUICK);
            for (int i = 0; i < 3; i++) failing(breaker);

            Thread.sleep(80);
            failing(breaker);                   // the probe, and it fails
            assertEquals(4, store.calls);

            // One failed probe is enough; no need to count to the threshold again.
            assertEquals(FailureCategory.UNAVAILABLE, failing(breaker).failure());
            assertEquals(4, store.calls);
        }
    }
}
