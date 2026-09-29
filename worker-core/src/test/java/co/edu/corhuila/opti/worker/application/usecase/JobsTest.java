package co.edu.corhuila.opti.worker.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import co.edu.corhuila.opti.worker.application.port.in.Deadline;
import co.edu.corhuila.opti.worker.application.port.in.JobResult;
import co.edu.corhuila.opti.worker.application.port.out.PatientsApi;
import co.edu.corhuila.opti.worker.application.port.out.StaleOrders;

/** Rules every job of the worker must keep: bounded batch, one failure never stops the rest, time limit. */
class JobsTest {

    private static final Instant NOW = Instant.parse("2026-09-29T15:00:00Z");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    // ---- expire-stale-orders --------------------------------------------------------------

    @Test
    void aFailureInTheSecondOrderDoesNotStopTheThird() {
        var ids = ids(3);
        var orders = new FakeOrders(ids, java.util.Set.of(ids.get(1)));
        var job = new ExpireStaleOrders(orders, clock, Duration.ofDays(3), 50);

        JobResult result = job.run(Deadline.in(Duration.ofMinutes(1), clock));

        assertThat(result).isEqualTo(new JobResult(2, 1));
        assertThat(orders.attempted).containsExactlyElementsOf(ids);
        assertThat(orders.cancelled).containsExactly(ids.get(0), ids.get(2));
    }

    @Test
    void theRunAsksForExactlyTheBatchSizeAndForQuotationsOlderThanTheAllowedAge() {
        var orders = new FakeOrders(ids(0), java.util.Set.of());

        new ExpireStaleOrders(orders, clock, Duration.ofDays(3), 25).run(Deadline.in(Duration.ofMinutes(1), clock));

        assertThat(orders.askedLimit).isEqualTo(25);
        assertThat(orders.askedBefore).isEqualTo(NOW.minus(Duration.ofDays(3)));
    }

    @Test
    void aRunWhoseTimeIsOverProcessesNothingMore() {
        var orders = new FakeOrders(ids(3), java.util.Set.of());
        var moving = new MovingClock(NOW);
        var deadline = Deadline.in(Duration.ofSeconds(1), moving);
        moving.now = NOW.plusSeconds(5);

        JobResult result = new ExpireStaleOrders(orders, clock, Duration.ofDays(3), 50).run(deadline);

        assertThat(result).isEqualTo(new JobResult(0, 0));
        assertThat(orders.attempted).isEmpty();
    }

    @Test
    void aRunThatRunsOutOfTimeInTheMiddleStopsAndLeavesTheRestForTheNextRun() {
        var ids = ids(3);
        var moving = new MovingClock(NOW);
        var orders = new FakeOrders(ids, java.util.Set.of());
        orders.afterEachCancel = () -> moving.now = moving.now.plusSeconds(30);

        JobResult result = new ExpireStaleOrders(orders, clock, Duration.ofDays(3), 50)
                .run(Deadline.in(Duration.ofSeconds(20), moving));

        assertThat(result).isEqualTo(new JobResult(1, 0));
        assertThat(orders.cancelled).containsExactly(ids.get(0));
    }

    @Test
    void ifListingFailsTheRunFailsLoudlyAndTheNextOneStartsClean() {
        var orders = new FakeOrders(ids(1), java.util.Set.of());
        orders.failListing = true;
        var job = new ExpireStaleOrders(orders, clock, Duration.ofDays(3), 50);

        assertThatThrownBy(() -> job.run(Deadline.in(Duration.ofMinutes(1), clock))).isInstanceOf(IllegalStateException.class);

        orders.failListing = false;
        assertThat(job.run(Deadline.in(Duration.ofMinutes(1), clock))).isEqualTo(new JobResult(1, 0));
    }

    // ---- flag-overdue-controls ------------------------------------------------------------

    @Test
    void controlsOlderThanTheAllowedPeriodAreFlaggedUsingTheBusinessDay() {
        var ids = ids(2);
        var patients = new FakePatients(ids);
        // 2026-09-29T03:00Z is still 2026-09-28 in Bogota (UTC-5)
        Clock early = Clock.fixed(Instant.parse("2026-09-29T03:00:00Z"), ZoneOffset.UTC);

        JobResult result = new FlagOverdueControls(patients, early, Period.ofMonths(12), 40)
                .run(Deadline.in(Duration.ofMinutes(1), early));

        assertThat(patients.askedBefore).isEqualTo(LocalDate.of(2025, 9, 28));
        assertThat(patients.askedLimit).isEqualTo(40);
        assertThat(result).isEqualTo(new JobResult(2, 0));
        assertThat(patients.flagged).containsExactlyElementsOf(ids);
    }

    @Test
    void oneFailingPatientIsCountedAndTheRestAreFlagged() {
        var ids = ids(3);
        var patients = new FakePatients(ids);
        patients.failOn = ids.get(0);

        JobResult result = new FlagOverdueControls(patients, clock, Period.ofMonths(12), 40)
                .run(Deadline.in(Duration.ofMinutes(1), clock));

        assertThat(result).isEqualTo(new JobResult(2, 1));
        assertThat(patients.flagged).containsExactly(ids.get(1), ids.get(2));
    }

    @Test
    void invalidRunLimitsAreRefused() {
        assertThatThrownBy(() -> Deadline.in(Duration.ZERO, clock)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JobResult(-1, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- fakes ----------------------------------------------------------------------------

    private static List<UUID> ids(int count) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(new UUID(0, i + 1));
        }
        return ids;
    }

    private static final class FakeOrders implements StaleOrders {
        final List<UUID> stale;
        final java.util.Set<UUID> failing;
        final List<UUID> attempted = new ArrayList<>();
        final List<UUID> cancelled = new ArrayList<>();
        int askedLimit;
        Instant askedBefore;
        boolean failListing;
        Runnable afterEachCancel = () -> { };

        FakeOrders(List<UUID> stale, java.util.Set<UUID> failing) {
            this.stale = stale;
            this.failing = failing;
        }

        @Override
        public List<UUID> staleQuotations(Instant createdBefore, int limit) {
            if (failListing) {
                throw new IllegalStateException("sales is down");
            }
            askedBefore = createdBefore;
            askedLimit = limit;
            return stale;
        }

        @Override
        public void cancel(UUID orderId) {
            attempted.add(orderId);
            if (failing.contains(orderId)) {
                throw new IllegalStateException("cancel failed");
            }
            cancelled.add(orderId);
            afterEachCancel.run();
        }
    }

    /** A clock the test can move. */
    private static final class MovingClock extends Clock {
        Instant now;

        MovingClock(Instant start) {
            this.now = start;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final class FakePatients implements PatientsApi {
        final List<UUID> overdue;
        final List<UUID> flagged = new ArrayList<>();
        LocalDate askedBefore;
        int askedLimit;
        UUID failOn;

        FakePatients(List<UUID> overdue) {
            this.overdue = overdue;
        }

        @Override
        public List<UUID> withControlOlderThan(LocalDate lastControlBefore, int limit) {
            askedBefore = lastControlBefore;
            askedLimit = limit;
            return overdue;
        }

        @Override
        public void flagControlOverdue(UUID patientId) {
            if (patientId.equals(failOn)) {
                throw new IllegalStateException("flag failed");
            }
            flagged.add(patientId);
        }
    }
}
