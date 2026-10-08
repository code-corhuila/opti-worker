package co.edu.corhuila.opti.worker.adapter.out.auth;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

import co.edu.corhuila.opti.worker.adapter.out.http.ApiClient;
import co.edu.corhuila.opti.worker.application.port.out.SellersApi;

/** Sellers and notifications through the identity API: never through its database. */
public class AuthHttpClient implements SellersApi {

    private final ApiClient api;
    private final String authUrl;

    public AuthHttpClient(ApiClient api, String authUrl) {
        this.api = api;
        this.authUrl = authUrl;
    }

    @Override
    public List<SellerGoal> withSalesGoal(int limit) {
        JsonNode page = api.get(authUrl + "/api/v1/users?role=SELLER&active=true&limit=" + limit);
        List<SellerGoal> goals = new ArrayList<>();
        page.path("data").forEach(user -> {
            JsonNode goal = user.path("salesGoalCents");
            if (goal.isNumber()) {
                goals.add(new SellerGoal(UUID.fromString(user.path("id").asText()), goal.asLong()));
            }
        });
        return goals;
    }

    @Override
    public void notify(UUID sellerId, String message, String idempotencyKey) {
        api.post(authUrl + "/api/v1/notifications",
                Map.of("userId", sellerId, "type", "SALES_GOAL_REACHED", "message", message), idempotencyKey);
    }
}
