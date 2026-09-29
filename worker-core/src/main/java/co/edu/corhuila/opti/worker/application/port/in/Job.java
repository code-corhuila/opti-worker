package co.edu.corhuila.opti.worker.application.port.in;

/** A unit of scheduled work. It must be safe to run twice: overlapping or repeated runs are normal. */
public interface Job {

    String name();

    /** Runs one bounded batch and stops early when the deadline passes. What is left waits for the next run. */
    JobResult run(Deadline deadline);
}
