package co.edu.corhuila.opti.worker.adapter.in.health;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import co.edu.corhuila.opti.worker.adapter.in.scheduler.JobScheduler.LastRun;

/**
 * The only HTTP the worker exposes: a health point. It has no business interface. It also shows the
 * last run of each job, which is what an operator checks first.
 */
public class HealthServer implements AutoCloseable {

    private final HttpServer server;
    private final ObjectMapper json;
    private final Supplier<Map<String, LastRun>> lastRuns;

    public HealthServer(int port, ObjectMapper json, Supplier<Map<String, LastRun>> lastRuns) throws IOException {
        this.json = json;
        this.lastRuns = lastRuns;
        this.server = HttpServer.create(new InetSocketAddress(port), 8);
        this.server.createContext("/health", this::handle);
    }

    public void start() {
        server.start();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"GET".equals(exchange.getRequestMethod()) || !"/health".equals(exchange.getRequestURI().getPath())) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "UP");
            body.put("jobs", lastRuns.get());
            byte[] bytes = json.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(1);
    }
}
