package co.edu.corhuila.opti.worker.application.port.in;

/** What a run did: how many elements it handled and how many failed (they are retried by the next run). */
public record JobResult(int processed, int failed) {

    public JobResult {
        if (processed < 0 || failed < 0) {
            throw new IllegalArgumentException("counts cannot be negative");
        }
    }
}
