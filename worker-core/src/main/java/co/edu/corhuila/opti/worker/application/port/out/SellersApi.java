package co.edu.corhuila.opti.worker.application.port.out;

import java.util.List;
import java.util.UUID;

/** Sellers and their notifications, as the worker sees them through the identity API. */
public interface SellersApi {

    /** At most {@code limit} active sellers that have a sales goal set. */
    List<SellerGoal> withSalesGoal(int limit);

    /** Notifies the seller. The idempotency key keeps one notification per event. */
    void notify(UUID sellerId, String message, String idempotencyKey);

    record SellerGoal(UUID id, long salesGoalCents) {
    }
}
