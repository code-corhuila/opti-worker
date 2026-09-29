package co.edu.corhuila.opti.worker.adapter.in.scheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import co.edu.corhuila.opti.worker.adapter.Correlation;
import co.edu.corhuila.opti.worker.adapter.out.http.ApiCallException;
import co.edu.corhuila.opti.worker.application.port.in.Deadline;
import co.edu.corhuila.opti.worker.application.port.in.Job;
import co.edu.corhuila.opti.worker.application.port.in.JobResult;

/**
 * Inbound adapter of the worker: runs each job on its own schedule. Every run gets its own
 * correlation id and a time limit, and a failed run never kills the schedule: the next one starts clean.
 */
public class JobScheduler implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(JobScheduler.class);

    /** A job and how often it runs. */
    public record Schedule(Job job, Duration every) {
    }

    /** The outcome of the last run of a job, for the health endpoint. */
    public record LastRun(Instant at, String correlationId, Integer processed, Integer failed, String error) {
    }

    private final List<Schedule> schedules;
    private final Duration runTimeout;
    private final Clock clock;
    private final ScheduledExecutorService executor;
    private final Map<String, LastRun> lastRuns = new ConcurrentHashMap<>();

    public JobScheduler(List<Schedule> schedules, Duration runTimeout, Clock clock) {
        this.schedules = schedules;
        this.runTimeout = runTimeout;
        this.clock = clock;
        this.executor = Executors.newScheduledThreadPool(Math.max(1, schedules.size()));
    }

    /** Starts every job; the fixed delay guarantees that two runs of the same job never overlap. */
    public void start() {
        int stagger = 0;
        for (Schedule schedule : schedules) {
            executor.scheduleWithFixedDelay(() -> runOnce(schedule.job()), 5L + 5L * stagger++,
                    schedule.every().toSeconds(), TimeUnit.SECONDS);
            LOG.info("job {} scheduled every {}", schedule.job().name(), schedule.every());
        }
    }

    /** One run. Public so tests and an operator can trigger it without waiting for the schedule. */
    public LastRun runOnce(Job job) {
        String id = Correlation.begin();
        LastRun outcome;
        try {
            JobResult result = job.run(Deadline.in(runTimeout, clock));
            LOG.info("job {} finished: {} processed, {} failed", job.name(), result.processed(), result.failed());
            outcome = new LastRun(clock.instant(), id, result.processed(), result.failed(), null);
        } catch (ApiCallException e) {
            LOG.error("job {} failed calling an API (status {}, code {}, traceId {}): {}", job.name(), e.status(),
                    e.code(), e.traceId(), e.getMessage());
            outcome = new LastRun(clock.instant(), id, null, null, e.code());
        } catch (RuntimeException e) {
            LOG.error("job {} failed", job.name(), e);
            outcome = new LastRun(clock.instant(), id, null, null, e.getClass().getSimpleName());
        } finally {
            Correlation.end();
        }
        lastRuns.put(job.name(), outcome);
        return outcome;
    }

    public Map<String, LastRun> lastRuns() {
        return Map.copyOf(lastRuns);
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(runTimeout.toSeconds(), TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
