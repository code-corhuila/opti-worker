package co.edu.corhuila.opti.worker.adapter.out.http;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import co.edu.corhuila.opti.worker.adapter.Correlation;

/**
 * The one HTTP client of the worker: service token, correlation id of the run, a time limit per
 * request, bounded retries and the error envelope of the domain APIs turned into an exception.
 */
public class ApiClient {

    private final HttpClient http;
    private final ObjectMapper json;
    private final String serviceToken;
    private final Duration requestTimeout;
    private final RetryPolicy retries;

    public ApiClient(HttpClient http, ObjectMapper json, String serviceToken, Duration requestTimeout,
                     RetryPolicy retries) {
        this.http = http;
        this.json = json;
        this.serviceToken = serviceToken;
        this.requestTimeout = requestTimeout;
        this.retries = retries;
    }

    public JsonNode get(String url) {
        return send(builder(url).GET().build());
    }

    /** POST with a JSON body and an Idempotency-Key, so a retried request cannot act twice. */
    public JsonNode post(String url, Object body, String idempotencyKey) {
        try {
            HttpRequest.Builder request = builder(url).header("Content-Type", "application/json");
            if (idempotencyKey != null) {
                request.header("Idempotency-Key", idempotencyKey);
            }
            return send(request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build());
        } catch (IOException e) {
            throw new ApiCallException(0, "BAD_REQUEST_BODY", "could not serialize the request body", null, e);
        }
    }

    private HttpRequest.Builder builder(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + serviceToken)
                .header("Accept", "application/json")
                .header(Correlation.HEADER, Correlation.current());
    }

    private JsonNode send(HttpRequest request) {
        return retries.execute(() -> once(request));
    }

    private JsonNode once(HttpRequest request) {
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ApiCallException(0, "NETWORK", "no response from " + request.uri().getHost() + ": "
                    + e.getClass().getSimpleName(), null, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiCallException(0, "INTERRUPTED", "the request was interrupted", null, e);
        }
        if (response.statusCode() / 100 == 2) {
            return parse(response.body());
        }
        throw failure(response);
    }

    /** Reads the common envelope {@code {error, message, traceId}}; a body that is not one is still reported. */
    private ApiCallException failure(HttpResponse<String> response) {
        String code = "HTTP_" + response.statusCode();
        String message = "the API answered " + response.statusCode();
        String traceId = null;
        try {
            JsonNode body = json.readTree(response.body());
            code = body.path("error").asText(code);
            message = body.path("message").asText(message);
            traceId = body.path("traceId").asText(null);
        } catch (IOException | RuntimeException ignored) {
            // an error page from a proxy, for example: keep the status-based description
        }
        return new ApiCallException(response.statusCode(), code, message, traceId, null);
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return json.createObjectNode();
        }
        try {
            return json.readTree(body);
        } catch (IOException e) {
            throw new ApiCallException(0, "BAD_RESPONSE", "the API answered with a body that is not JSON", null, e);
        }
    }
}
