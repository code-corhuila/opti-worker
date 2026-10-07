package co.edu.corhuila.opti.worker.adapter.out.http;

/** A call to a domain API that did not succeed. {@code status} is 0 when there was no HTTP response at all. */
public class ApiCallException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final String code;
    private final String traceId;

    public ApiCallException(int status, String code, String message, String traceId, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
        this.traceId = traceId;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String traceId() {
        return traceId;
    }

    /** Only what a retry can fix: no response (network, timeout), 429 and 5xx. A 4xx is the caller's mistake. */
    public boolean retryable() {
        return status == 0 || status == 429 || status >= 500;
    }
}
