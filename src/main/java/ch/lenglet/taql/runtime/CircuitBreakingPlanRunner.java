package ch.lenglet.taql.runtime;

import ch.lenglet.taql.plan.Plan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Stops sending queries to a store that keeps failing, and starts again once it
 * looks recovered.
 *
 * Retrying alone makes an outage worse: when the database is down for everyone,
 * every request tries three times instead of once, so the moment the store is
 * least able to cope is the moment it receives the most traffic. A breaker is
 * what makes retrying safe to have -- one turns a blip into a success, the other
 * stops a failure becoming a flood.
 *
 * <h2>Put it outside the retry, not inside</h2>
 * Wrapped as {@code new CircuitBreakingPlanRunner(new RetryingPlanRunner(jdbc))},
 * the breaker counts one failure per <em>request</em> -- a request that failed
 * despite retrying -- rather than one per attempt. That is the right thing to
 * count on both sides: a single deadlock never reaches the breaker, because the
 * retry absorbs it, while a request that deadlocked every time really is a sign
 * of trouble. And once open, the whole request is refused, so nothing retries
 * into a store that is not answering.
 *
 * <h2>What counts as a failure</h2>
 * Only what {@link FailureCategory#reflectsStoreHealth()} admits. A breaker
 * sheds traffic for everyone, so one caller's malformed value or missing
 * permission must not open it -- see that method for why timeouts are excluded
 * too.
 */
public final class CircuitBreakingPlanRunner implements PlanRunner {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakingPlanRunner.class);

    /**
     * @param failureThreshold consecutive store failures that open the circuit.
     *                         Consecutive, not a rate: one success means the
     *                         store is answering, and that is the question.
     * @param openFor          how long to refuse everything before letting a
     *                         single query through to find out.
     */
    public record Options(int failureThreshold, Duration openFor) {

        public static final Options DEFAULTS = new Options(5, Duration.ofSeconds(10));

        public Options {
            if (failureThreshold < 1) throw new IllegalArgumentException("failureThreshold must be at least 1");
            if (openFor.isNegative() || openFor.isZero()) {
                throw new IllegalArgumentException("openFor must be positive");
            }
        }
    }

    private enum State {
        /** Everything through, failures counted. */
        CLOSED,
        /** Nothing through, until openFor has elapsed. */
        OPEN,
        /** One query through, to find out whether to close or re-open. */
        HALF_OPEN
    }

    private final PlanRunner delegate;
    private final Options options;

    private final Object lock = new Object();
    private State state = State.CLOSED;
    private int consecutiveFailures;
    private long openedAtNanos;
    private boolean probing;

    public CircuitBreakingPlanRunner(PlanRunner delegate) {
        this(delegate, Options.DEFAULTS);
    }

    public CircuitBreakingPlanRunner(PlanRunner delegate, Options options) {
        this.delegate = delegate;
        this.options = options;
    }

    @Override
    public List<Map<String, Object>> run(Plan plan, List<Object> values) {
        boolean isProbe = admit();
        try {
            List<Map<String, Object>> rows = delegate.run(plan, values);
            succeeded();
            return rows;
        } catch (TaqlExecutionException e) {
            failed(e.failure(), isProbe);
            throw e;
        } catch (RuntimeException | Error e) {
            // Not a store failure -- a policy refusal, a row ceiling, a bug.
            // Let a probe go again rather than counting it either way.
            released(isProbe);
            throw e;
        }
    }

    /** @return true if this call is the half-open probe. Never holds the lock across the call. */
    private boolean admit() {
        synchronized (lock) {
            if (state == State.OPEN) {
                if (System.nanoTime() - openedAtNanos < options.openFor().toNanos()) throw refuse();
                state = State.HALF_OPEN;
                probing = true;
                log.info("circuit half-open: letting one query through");
                return true;
            }
            // Half-open already, and someone else is the probe.
            if (state == State.HALF_OPEN && probing) throw refuse();
            if (state == State.HALF_OPEN) {
                probing = true;
                return true;
            }
            return false;
        }
    }

    private void succeeded() {
        synchronized (lock) {
            if (state != State.CLOSED) log.info("circuit closed: the store answered");
            state = State.CLOSED;
            consecutiveFailures = 0;
            probing = false;
        }
    }

    private void failed(FailureCategory failure, boolean isProbe) {
        synchronized (lock) {
            probing = false;
            if (!failure.reflectsStoreHealth()) {
                // The query's fault, not the store's. A probe that fails this
                // way has told us nothing, so leave the circuit as it was.
                return;
            }
            if (isProbe || ++consecutiveFailures >= options.failureThreshold()) {
                if (state != State.OPEN) {
                    log.warn("circuit open for {}: {} consecutive store failures, last {}",
                            options.openFor(), consecutiveFailures, failure);
                }
                state = State.OPEN;
                openedAtNanos = System.nanoTime();
            }
        }
    }

    private void released(boolean isProbe) {
        if (!isProbe) return;
        synchronized (lock) {
            probing = false;
        }
    }

    private TaqlExecutionException refuse() {
        return new TaqlExecutionException(FailureCategory.UNAVAILABLE, "circuit-open", null, 0);
    }
}
