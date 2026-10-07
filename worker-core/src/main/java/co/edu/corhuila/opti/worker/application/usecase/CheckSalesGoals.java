package co.edu.corhuila.opti.worker.application.usecase;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

import co.edu.corhuila.opti.worker.application.port.in.Deadline;
import co.edu.corhuila.opti.worker.application.port.in.Job;
import co.edu.corhuila.opti.worker.application.port.in.JobResult;
import co.edu.corhuila.opti.worker.application.port.out.SalesReportsApi;
import co.edu.corhuila.opti.worker.application.port.out.SellersApi;
import co.edu.corhuila.opti.worker.application.port.out.SellersApi.SellerGoal;

/**
 * Notifies a seller the first time, each calendar month, that their revenue since the 1st reaches
 * their sales goal. The idempotency key ("sales-goal:{sellerId}:{yyyy-MM}") is what makes "the
 * first time" true even if this job runs many times across the month. Safe to run twice.
 */
public class CheckSalesGoals implements Job {

    private static final String NAME = "check-sales-goals";
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Bogota");

    private final SellersApi sellers;
    private final SalesReportsApi sales;
    private final Clock clock;
    private final int batchSize;

    public CheckSalesGoals(SellersApi sellers, SalesReportsApi sales, Clock clock, int batchSize) {
        this.sellers = sellers;
        this.sales = sales;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public JobResult run(Deadline deadline) {
        List<SellerGoal> goals = sellers.withSalesGoal(batchSize);
        Instant periodStart = startOfMonth();
        String period = YearMonth.now(clock.withZone(BUSINESS_ZONE)).toString();
        return BatchRunner.process(NAME, goals, deadline, goal -> checkOne(goal, periodStart, period));
    }

    private void checkOne(SellerGoal goal, Instant periodStart, String period) {
        long revenue = sales.revenueSince(goal.id(), periodStart);
        if (revenue >= goal.salesGoalCents()) {
            sellers.notify(goal.id(), "Alcanzaste tu meta de ventas de " + period + ".",
                    "sales-goal:" + goal.id() + ":" + period);
        }
    }

    private Instant startOfMonth() {
        return LocalDate.now(clock.withZone(BUSINESS_ZONE)).withDayOfMonth(1).atStartOfDay(BUSINESS_ZONE).toInstant();
    }
}
