package co.edu.corhuila.opti.worker.application.usecase;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import co.edu.corhuila.opti.worker.application.port.in.Deadline;
import co.edu.corhuila.opti.worker.application.port.in.Job;
import co.edu.corhuila.opti.worker.application.port.in.JobResult;
import co.edu.corhuila.opti.worker.application.port.out.StaleOrders;

/**
 * Cancels the quotations that stayed unanswered longer than the allowed time, giving their stock
 * back. Safe to run twice: cancelling an order is idempotent.
 */
public class ExpireStaleOrders implements Job {

    private static final String NAME = "expire-stale-orders";

    private final StaleOrders orders;
    private final Clock clock;
    private final Duration maxAge;
    private final int batchSize;

    public ExpireStaleOrders(StaleOrders orders, Clock clock, Duration maxAge, int batchSize) {
        this.orders = orders;
        this.clock = clock;
        this.maxAge = maxAge;
        this.batchSize = batchSize;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public JobResult run(Deadline deadline) {
        List<UUID> stale = orders.staleQuotations(clock.instant().minus(maxAge), batchSize);
        return BatchRunner.process(NAME, stale, deadline, orders::cancel);
    }
}
