package co.edu.corhuila.opti.worker.adapter.out.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import co.edu.corhuila.opti.worker.adapter.Correlation;
import co.edu.corhuila.opti.worker.adapter.out.customers.CustomersHttpClient;
import co.edu.corhuila.opti.worker.adapter.out.orders.OrdersHttpClient;

/** The clients against a fake API that speaks the common contract: envelope, list shape, headers. */
class HttpClientsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private String base;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final Queue<Reply> replies = new LinkedList<>();
    private final List<Duration> sleeps = new ArrayList<>();
    private ApiClient api;

    private record Received(String method, String pathAndQuery, String authorization, String correlationId,
                            String idempotencyKey, String body) {
    }

    private record Reply(int status, String body) {
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::answer);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        api = new ApiClient(HttpClient.newHttpClient(), JSON, "service-token", Duration.ofSeconds(2),
                new RetryPolicy(3, Duration.ofMillis(200), sleeps::add, () -> 0.5));
        Correlation.begin();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        Correlation.end();
        MDC.clear();
    }

    private void answer(HttpExchange exchange) throws IOException {
        received.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                exchange.getRequestHeaders().getFirst("Authorization"),
                exchange.getRequestHeaders().getFirst("X-Correlation-Id"),
                exchange.getRequestHeaders().getFirst("Idempotency-Key"),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
        Reply reply = replies.isEmpty() ? new Reply(200, "{}") : replies.poll();
        byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(reply.status(), bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String envelope(String code, String message) {
        return "{\"error\":\"" + code + "\",\"message\":\"" + message + "\",\"traceId\":\"trace-9\"}";
    }

    // ---- headers --------------------------------------------------------------------------

    @Test
    void everyCallCarriesTheServiceTokenAndTheCorrelationIdOfTheRun() {
        String run = Correlation.current();

        api.get(base + "/api/v1/anything");

        Received call = received.get(0);
        assertThat(call.authorization()).isEqualTo("Bearer service-token");
        assertThat(call.correlationId()).isEqualTo(run).isNotEqualTo("no-run");
    }

    // ---- retries --------------------------------------------------------------------------

    @Test
    void serviceUnavailableAndTooManyRequestsAreRetriedUntilTheLimitWithGrowingJitter() {
        replies.add(new Reply(503, envelope("SERVICE_UNAVAILABLE", "down")));
        replies.add(new Reply(429, envelope("TOO_MANY_REQUESTS", "slow down")));
        replies.add(new Reply(200, "{\"ok\":true}"));

        assertThat(api.get(base + "/x").path("ok").asBoolean()).isTrue();

        assertThat(received).hasSize(3);
        assertThat(sleeps).containsExactly(Duration.ofMillis(200), Duration.ofMillis(400)); // random 0.5 * (400, 800)
    }

    @Test
    void afterTheLastAttemptTheFailureIsReportedWithItsEnvelope() {
        for (int i = 0; i < 3; i++) {
            replies.add(new Reply(503, envelope("SERVICE_UNAVAILABLE", "down")));
        }

        assertThatThrownBy(() -> api.get(base + "/x")).isInstanceOfSatisfying(ApiCallException.class, e -> {
            assertThat(e.status()).isEqualTo(503);
            assertThat(e.code()).isEqualTo("SERVICE_UNAVAILABLE");
            assertThat(e.traceId()).isEqualTo("trace-9");
        });
        assertThat(received).hasSize(3);
    }

    @Test
    void unprocessableAndUnauthorizedAreNeverRetried() {
        replies.add(new Reply(422, envelope("BUSINESS_RULE_VIOLATION", "no")));
        assertThatThrownBy(() -> api.get(base + "/x")).isInstanceOf(ApiCallException.class);
        replies.add(new Reply(401, envelope("UNAUTHORIZED", "no token")));
        assertThatThrownBy(() -> api.get(base + "/x")).isInstanceOf(ApiCallException.class);

        assertThat(received).hasSize(2);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void aNonJsonErrorPageIsStillReportedByItsStatus() {
        replies.add(new Reply(400, "<html>bad gateway</html>"));

        assertThatThrownBy(() -> api.get(base + "/x")).isInstanceOfSatisfying(ApiCallException.class, e -> {
            assertThat(e.status()).isEqualTo(400);
            assertThat(e.code()).isEqualTo("HTTP_400");
        });
    }

    @Test
    void aServerThatIsGoneIsARetryableNetworkFailure() {
        server.stop(0);

        assertThatThrownBy(() -> api.get(base + "/x")).isInstanceOfSatisfying(ApiCallException.class, e -> {
            assertThat(e.status()).isZero();
            assertThat(e.code()).isEqualTo("NETWORK");
            assertThat(e.retryable()).isTrue();
        });
        assertThat(sleeps).hasSize(2);
    }

    @Test
    void theWaitCeilingDoublesWithEachAttempt() {
        var policy = new RetryPolicy(5, Duration.ofMillis(200), d -> { }, () -> 1.0);

        assertThat(policy.ceiling(1)).isEqualTo(Duration.ofMillis(400));
        assertThat(policy.ceiling(2)).isEqualTo(Duration.ofMillis(800));
        assertThat(policy.ceiling(3)).isEqualTo(Duration.ofMillis(1600));
    }

    // ---- orders ---------------------------------------------------------------------------

    @Test
    void staleQuotationsAskForTheBatchAndReadTheListAsDataAndMeta() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        replies.add(new Reply(200, "{\"data\":[{\"id\":\"" + first + "\"},{\"id\":\"" + second
                + "\"}],\"meta\":{\"page\":1,\"limit\":50,\"total\":2,\"totalPages\":1}}"));
        var orders = new OrdersHttpClient(api, base, base);

        List<UUID> ids = orders.staleQuotations(Instant.parse("2026-09-26T15:00:00Z"), 50);

        assertThat(ids).containsExactly(first, second);
        assertThat(received.get(0).pathAndQuery())
                .isEqualTo("/api/v1/work-orders?status=QUOTATION&createdBefore=2026-09-26T15:00:00Z&limit=50");
    }

    @Test
    void cancelGoesThroughTheWorkflowWithAnIdempotencyKeyPerOrder() throws Exception {
        UUID order = UUID.randomUUID();
        var orders = new OrdersHttpClient(api, base, base);

        orders.cancel(order);

        Received call = received.get(0);
        assertThat(call.method()).isEqualTo("POST");
        assertThat(call.pathAndQuery()).isEqualTo("/api/v1/sagas/cancel-order");
        assertThat(call.idempotencyKey()).isEqualTo("expire-" + order);
        assertThat(JSON.readTree(call.body()).path("orderId").asText()).isEqualTo(order.toString());
    }

    // ---- customers ------------------------------------------------------------------------

    @Test
    void overduePatientsAreAskedByStatusCutoffAndLimitAndFlaggedOneByOne() {
        UUID patient = UUID.randomUUID();
        replies.add(new Reply(200, "{\"data\":[{\"id\":\"" + patient + "\"}],\"meta\":{}}"));
        var customers = new CustomersHttpClient(api, base);

        List<UUID> ids = customers.withControlOlderThan(LocalDate.of(2025, 9, 29), 40);
        customers.flagControlOverdue(patient);

        assertThat(ids).containsExactly(patient);
        assertThat(received.get(0).pathAndQuery())
                .isEqualTo("/api/v1/patients?status=ACTIVE&controlDueBefore=2025-09-29&limit=40");
        assertThat(received.get(1).method()).isEqualTo("POST");
        assertThat(received.get(1).pathAndQuery()).isEqualTo("/api/v1/patients/" + patient + "/control-overdue");
    }
}
