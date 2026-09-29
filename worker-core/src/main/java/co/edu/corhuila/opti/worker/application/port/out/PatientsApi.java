package co.edu.corhuila.opti.worker.application.port.out;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Patients as the worker sees them: only what the customers contract lets it ask and do. */
public interface PatientsApi {

    /** At most {@code limit} active patients whose last control is older than the given date. */
    List<UUID> withControlOlderThan(LocalDate lastControlBefore, int limit);

    /** Flags the patient. Flagging twice is harmless. */
    void flagControlOverdue(UUID patientId);
}
