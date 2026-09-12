package ch.lenglet.taql.runtime;

import ch.lenglet.taql.plan.Plan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Runs a plan again when the first attempt failed for a reason that might not
 * recur.
 *
 * Safe here in a way it usually is not: every TAQL query is a {@code SELECT},
 * so running one twice is indistinguishable from running it once. That is the
 * whole licence for this class, and it is why retrying lives in the library
 * rather than being left to the caller -- the caller would have to know that
 * about TAQL to know it was allowed.
 *
 * What it will not do is retry a {@link FailureCategory#TIMEOUT}: the same query
 * will take just as long again, and trying costs another full timeout.
 */
public final class RetryingPlanRunner implements PlanRunner {

    private static final Logger log = LoggerFactory.getLogger(RetryingPlanRunner.class);

    /**
     * @param maxAttempts        total attempts, so 1 disables retrying.
     * @param baseBackoffMillis  first delay, doubled per attempt and jittered.
     */
    public record Options(int maxAttempts, long baseBackoffMillis) {

        public static final Options DEFAULTS = new Options(3, 50);

        public Options {
            if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be at least 1");
            if (baseBackoffMillis < 0) throw new IllegalArgumentException("baseBackoffMillis cannot be negative");
        }
    }

    private final PlanRunner delegate;
    private final Options options;

    public RetryingPlanRunner(PlanRunner delegate) {
        this(delegate, Options.DEFAULTS);
    }

    public RetryingPlanRunner(PlanRunner delegate, Options options) {
        this.delegate = delegate;
        this.options = options;
    }

    @Override
    public List<Map<String, Object>> run(Plan plan, List<Object> values) {
        for (int attempt = 1; ; attempt++) {
            try {
                return delegate.run(plan, values);
            } catch (TaqlExecutionException e) {
                if (!e.failure().worthRetrying() || attempt == options.maxAttempts()) {
                    // Re-thrown so the attempt count covers the whole call; the
                    // runner below only ever knows about the one it made.
                    TaqlExecutionException giveUp =
                            new TaqlExecutionException(e.failure(), e.code(), e.getCause(), attempt);
                    log.error("query failed: {}", giveUp.logDetail());
                    throw giveUp;
                }
                log.warn("attempt {} of {} failed as {} (code {}); retrying",
                        attempt, options.maxAttempts(), e.failure(), e.code());
                backoff(attempt, e);
            }
        }
    }

    /**
     * Exponential backoff, jittered.
     *
     * Without the jitter this is synchronised retry: every caller that lost the
     * same deadlock, or that was holding a connection when the store went away,
     * sleeps exactly the same doubling interval and collides again on each
     * wake-up. Spreading them is most of the value of backing off at all.
     *
     * Half the interval is fixed and half is random, so there is still a floor
     * under the wait -- full jitter can pick a delay near zero and retry into a
     * server that has not recovered.
     */
    private void backoff(int attempt, TaqlExecutionException cause) {
        // Shift capped so a generous maxAttempts cannot overflow into a
        // negative delay, which Thread.sleep rejects.
        long ceiling = options.baseBackoffMillis() << Math.min(attempt - 1, 16);
        long delay = ceiling / 2 + ThreadLocalRandom.current().nextLong(ceiling / 2 + 1);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw cause;
        }
    }
}
