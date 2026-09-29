package co.edu.corhuila.opti.worker.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Work orders as the worker sees them: only what its published contract lets it ask and do. */
public interface StaleOrders {

    /** At most {@code limit} quotations created before the given moment. */
    List<UUID> staleQuotations(Instant createdBefore, int limit);

    /** Cancels the order and gives its reserved stock back. Cancelling twice is harmless. */
    void cancel(UUID orderId);
}
