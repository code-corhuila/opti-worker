package co.edu.corhuila.opti.worker.adapter.out.orders;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

import co.edu.corhuila.opti.worker.adapter.out.http.ApiClient;
import co.edu.corhuila.opti.worker.application.port.out.StaleOrders;

/**
 * Work orders through their published contracts: the list comes from the sales API, and the
 * cancellation goes through the workflow, because cancelling also gives the stock back to the
 * products domain and a process that touches several domains belongs to the workflow.
 */
public class OrdersHttpClient implements StaleOrders {

    private final ApiClient api;
    private final String salesUrl;
    private final String workflowUrl;

    public OrdersHttpClient(ApiClient api, String salesUrl, String workflowUrl) {
        this.api = api;
        this.salesUrl = salesUrl;
        this.workflowUrl = workflowUrl;
    }

    @Override
    public List<UUID> staleQuotations(Instant createdBefore, int limit) {
        JsonNode page = api.get(salesUrl + "/api/v1/work-orders?status=QUOTATION&createdBefore=" + createdBefore
                + "&limit=" + limit);
        List<UUID> ids = new ArrayList<>();
        page.path("data").forEach(order -> ids.add(UUID.fromString(order.path("id").asText())));
        return ids;
    }

    @Override
    public void cancel(UUID orderId) {
        api.post(workflowUrl + "/api/v1/sagas/cancel-order", Map.of("orderId", orderId.toString()),
                "expire-" + orderId);
    }
}
