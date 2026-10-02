package co.edu.corhuila.opti.worker.application.usecase;

import java.time.Clock;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import co.edu.corhuila.opti.worker.application.port.in.Deadline;
import co.edu.corhuila.opti.worker.application.port.in.Job;
import co.edu.corhuila.opti.worker.application.port.in.JobResult;
import co.edu.corhuila.opti.worker.application.port.out.PatientsApi;

/**
 * Flags the patients whose last optical control is older than the allowed period (HU-04). The day
 * boundary is the business day in Colombia, not the server's UTC day. Safe to run twice.
 */
public class FlagOverdueControls implements Job {

    private static final String NAME = "flag-overdue-controls";
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Bogota");

    private final PatientsApi patients;
    private final Clock clock;
    private final Period maxAge;
    private final int batchSize;

    public FlagOverdueControls(PatientsApi patients, Clock clock, Period maxAge, int batchSize) {
        this.patients = patients;
        this.clock = clock;
        this.maxAge = maxAge;
        this.batchSize = batchSize;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public JobResult run(Deadline deadline) {
        LocalDate cutoff = LocalDate.now(clock.withZone(BUSINESS_ZONE)).minus(maxAge);
        List<UUID> overdue = patients.withControlOlderThan(cutoff, batchSize);
        return BatchRunner.process(NAME, overdue, deadline, patients::flagControlOverdue);
    }
}
