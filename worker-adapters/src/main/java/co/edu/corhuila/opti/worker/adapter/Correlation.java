package co.edu.corhuila.opti.worker.adapter;

import java.util.UUID;

import org.slf4j.MDC;

/**
 * The correlation id of the run in progress. The scheduler fills it, the HTTP client reads it and
 * every log line carries it (through the logging context): neither adapter knows the other.
 */
public final class Correlation {

    public static final String HEADER = "X-Correlation-Id";
    private static final String MDC_KEY = "correlationId";
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private Correlation() {
    }

    /** Starts a new run and returns its id. */
    public static String begin() {
        String id = UUID.randomUUID().toString();
        CURRENT.set(id);
        MDC.put(MDC_KEY, id);
        return id;
    }

    public static void end() {
        CURRENT.remove();
        MDC.remove(MDC_KEY);
    }

    public static String current() {
        String id = CURRENT.get();
        return id == null ? "no-run" : id;
    }
}
