package co.edu.corhuila.opti.worker.adapter.out.sales;

import java.time.Instant;
import java.util.UUID;

import co.edu.corhuila.opti.worker.adapter.out.http.ApiClient;
import co.edu.corhuila.opti.worker.application.port.out.SalesReportsApi;

/** Revenue through the sales API's reports: never through its database. */
public class SalesReportsHttpClient implements SalesReportsApi {

    private final ApiClient api;
    private final String salesUrl;

    public SalesReportsHttpClient(ApiClient api, String salesUrl) {
        this.api = api;
        this.salesUrl = salesUrl;
    }

    @Override
    public long revenueSince(UUID sellerId, Instant periodStart) {
        var summary = api.get(salesUrl + "/api/v1/reports/seller-sales?sellerId=" + sellerId + "&from=" + periodStart);
        return summary.path("totalCents").asLong();
    }
}
