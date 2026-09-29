package co.edu.corhuila.opti.worker.application.port.in;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** The moment a run must stop. A run without a limit is a run that one day never ends. */
public final class Deadline {

    private final Clock clock;
    private final Instant end;

    private Deadline(Clock clock, Instant end) {
        this.clock = clock;
        this.end = end;
    }

    public static Deadline in(Duration timeout, Clock clock) {
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("the run timeout must be positive");
        }
        return new Deadline(clock, clock.instant().plus(timeout));
    }

    public boolean expired() {
        return !clock.instant().isBefore(end);
    }
}
