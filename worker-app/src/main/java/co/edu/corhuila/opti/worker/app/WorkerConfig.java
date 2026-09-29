package co.edu.corhuila.opti.worker.app;

import java.time.Duration;
import java.time.Period;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Every setting and limit of the worker, read from the environment and validated at start: a bad
 * value stops the process with a message that names the variable, instead of failing at 3 a.m.
 */
public record WorkerConfig(String customersUrl, String salesUrl, String workflowUrl, String serviceToken,
                           Duration expireEvery, Duration quotationMaxAge, Duration controlsEvery,
                           Period controlMaxAge, int batchSize, Duration runTimeout, Duration httpTimeout,
                           int httpAttempts, int healthPort) {

    private static final int MAX_BATCH = 100;

    /** Reads the settings; {@code env} is {@code System.getenv()} in production. */
    public static WorkerConfig from(Map<String, String> env) {
        List<String> problems = new ArrayList<>();
        Reader read = new Reader(env, problems);

        String customers = read.text("CUSTOMERS_API_URL", null);
        String sales = read.text("SALES_API_URL", null);
        String workflow = read.text("WORKFLOW_URL", null);
        String token = read.text("SERVICE_TOKEN", null);
        Duration expireEvery = read.duration("EXPIRE_EVERY", "PT10M", Duration.ofMinutes(1), Duration.ofDays(1));
        Duration maxAge = read.duration("PENDING_TTL", "P3D", Duration.ofHours(1), Duration.ofDays(60));
        Duration controlsEvery = read.duration("CONTROLS_EVERY", "PT6H", Duration.ofMinutes(1), Duration.ofDays(7));
        Period controlAge = read.period("CONTROL_MAX_AGE", "P12M");
        int batch = read.integer("BATCH_SIZE", 50, 1, MAX_BATCH);
        Duration runTimeout = read.duration("RUN_TIMEOUT", "PT2M", Duration.ofSeconds(5), Duration.ofHours(1));
        Duration httpTimeout = read.duration("HTTP_TIMEOUT", "PT5S", Duration.ofMillis(200), Duration.ofMinutes(1));
        int attempts = read.integer("HTTP_ATTEMPTS", 3, 1, 10);
        int port = read.integer("HEALTH_PORT", 8080, 1, 65535);

        if (!problems.isEmpty()) {
            throw new IllegalStateException("invalid worker configuration:\n - " + String.join("\n - ", problems));
        }
        return new WorkerConfig(customers, sales, workflow, token, expireEvery, maxAge, controlsEvery, controlAge,
                batch, runTimeout, httpTimeout, attempts, port);
    }

    /** Reads one variable at a time and collects every problem, so all are shown together. */
    private static final class Reader {
        private final Map<String, String> env;
        private final List<String> problems;

        Reader(Map<String, String> env, List<String> problems) {
            this.env = env;
            this.problems = problems;
        }

        String text(String name, String fallback) {
            String value = env.get(name);
            if (value == null || value.isBlank()) {
                if (fallback == null) {
                    problems.add(name + " is required");
                }
                return fallback;
            }
            return value.trim();
        }

        int integer(String name, int fallback, int min, int max) {
            return parse(name, String.valueOf(fallback), Integer::parseInt, Comparator.naturalOrder(), min, max,
                    "an integer between " + min + " and " + max);
        }

        Duration duration(String name, String fallback, Duration min, Duration max) {
            return parse(name, fallback, Duration::parse, Comparator.naturalOrder(), min, max,
                    "an ISO-8601 duration between " + min + " and " + max);
        }

        Period period(String name, String fallback) {
            return parse(name, fallback, Period::parse, null, null, null, "an ISO-8601 period such as P12M");
        }

        private <T> T parse(String name, String fallback, Function<String, T> parser, Comparator<T> order, T min, T max,
                            String expected) {
            String raw = env.getOrDefault(name, fallback);
            try {
                T value = parser.apply(raw.trim());
                if (order != null && (order.compare(value, min) < 0 || order.compare(value, max) > 0)) {
                    problems.add(name + "=" + raw + " must be " + expected);
                    return parser.apply(fallback);
                }
                return value;
            } catch (RuntimeException e) {
                problems.add(name + "=" + raw + " must be " + expected);
                return parser.apply(fallback);
            }
        }
    }
}
