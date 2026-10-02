package co.edu.corhuila.opti.worker.application.usecase;

import java.util.List;
import java.util.function.Consumer;

import co.edu.corhuila.opti.worker.application.port.in.Deadline;
import co.edu.corhuila.opti.worker.application.port.in.JobResult;

/**
 * The batch loop every job shares: one failing element never stops the batch, it is counted and
 * left for the next run; a run whose time is over stops before the next element.
 */
final class BatchRunner {

    private static final System.Logger LOG = System.getLogger(BatchRunner.class.getName());

    private BatchRunner() {
    }

    static <T> JobResult process(String job, List<T> batch, Deadline deadline, Consumer<T> action) {
        int processed = 0;
        int failed = 0;
        for (T element : batch) {
            if (deadline.expired()) {
                LOG.log(System.Logger.Level.WARNING, "{0}: run timeout reached, the rest waits for the next run", job);
                break;
            }
            try {
                action.accept(element);
                processed++;
            } catch (RuntimeException e) {
                failed++;
                LOG.log(System.Logger.Level.ERROR, "{0}: element {1} failed and stays for the next run: {2}", job,
                        element, e.getMessage());
            }
        }
        return new JobResult(processed, failed);
    }
}
