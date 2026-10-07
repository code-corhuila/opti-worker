package co.edu.corhuila.opti.worker.adapter.out.http;

import java.time.Duration;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/**
 * Bounded retries with exponential back-off and full jitter: the wait before attempt {@code n} is a
 * random value between 0 and {@code base * 2^n}. Without the randomness, every process that failed
 * together would retry together and prolong the outage.
 */
public class RetryPolicy {

    /** Waits; replaced in tests so they do not sleep. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final int attempts;
    private final Duration base;
    private final Sleeper sleeper;
    private final DoubleSupplier random;

    public RetryPolicy(int attempts, Duration base, Sleeper sleeper, DoubleSupplier random) {
        if (attempts < 1) {
            throw new IllegalArgumentException("at least one attempt");
        }
        this.attempts = attempts;
        this.base = base;
        this.sleeper = sleeper;
        this.random = random;
    }

    public <T> T execute(Supplier<T> call) {
        ApiCallException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return call.get();
            } catch (ApiCallException e) {
                if (!e.retryable() || attempt == attempts) {
                    throw e;
                }
                last = e;
                pause(attempt);
            }
        }
        throw last;
    }

    /** Upper bound of the wait after the given failed attempt (1-based). */
    Duration ceiling(int failedAttempt) {
        return base.multipliedBy(1L << failedAttempt);
    }

    private void pause(int failedAttempt) {
        long millis = (long) (random.getAsDouble() * ceiling(failedAttempt).toMillis());
        try {
            sleeper.sleep(Duration.ofMillis(millis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiCallException(0, "INTERRUPTED", "the run was interrupted while waiting to retry", null, e);
        }
    }
}
