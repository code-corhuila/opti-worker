package co.edu.corhuila.opti.worker.application.port.out;

import java.time.Instant;
import java.util.UUID;

/** Revenue, as the worker sees it through the sales API. */
public interface SalesReportsApi {

    /** Total of that seller's non-cancelled orders since {@code periodStart}, in cents. */
    long revenueSince(UUID sellerId, Instant periodStart);
}
