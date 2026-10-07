package co.edu.corhuila.opti.worker.app;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import co.edu.corhuila.opti.worker.adapter.in.health.HealthServer;
import co.edu.corhuila.opti.worker.adapter.in.scheduler.JobScheduler;
import co.edu.corhuila.opti.worker.adapter.in.scheduler.JobScheduler.Schedule;
import co.edu.corhuila.opti.worker.adapter.out.auth.AuthHttpClient;
import co.edu.corhuila.opti.worker.adapter.out.customers.CustomersHttpClient;
import co.edu.corhuila.opti.worker.adapter.out.http.ApiClient;
import co.edu.corhuila.opti.worker.adapter.out.http.RetryPolicy;
import co.edu.corhuila.opti.worker.adapter.out.orders.OrdersHttpClient;
import co.edu.corhuila.opti.worker.adapter.out.sales.SalesReportsHttpClient;
import co.edu.corhuila.opti.worker.application.usecase.CheckSalesGoals;
import co.edu.corhuila.opti.worker.application.usecase.ExpireStaleOrders;
import co.edu.corhuila.opti.worker.application.usecase.FlagOverdueControls;

/**
 * Composition root of the worker: the only place that knows every concrete type and that declares
 * every limit (batch size, run timeout, request timeout, attempts). No framework: plain Java.
 */
public final class WorkerApplication {

    private static final Logger LOG = LoggerFactory.getLogger(WorkerApplication.class);
    private static final Duration RETRY_BASE = Duration.ofMillis(200);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

    private WorkerApplication() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        WorkerConfig config = WorkerConfig.from(System.getenv());
        Clock clock = Clock.systemUTC();
        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        ApiClient api = new ApiClient(
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build(), json, config.serviceToken(),
                config.httpTimeout(),
                new RetryPolicy(config.httpAttempts(), RETRY_BASE, d -> Thread.sleep(d.toMillis()),
                        () -> java.util.concurrent.ThreadLocalRandom.current().nextDouble()));

        var orders = new OrdersHttpClient(api, config.salesUrl(), config.workflowUrl());
        var patients = new CustomersHttpClient(api, config.customersUrl());
        var sellers = new AuthHttpClient(api, config.authUrl());
        var salesReports = new SalesReportsHttpClient(api, config.salesUrl());

        var scheduler = new JobScheduler(List.of(
                new Schedule(new ExpireStaleOrders(orders, clock, config.quotationMaxAge(), config.batchSize()),
                        config.expireEvery()),
                new Schedule(new FlagOverdueControls(patients, clock, config.controlMaxAge(), config.batchSize()),
                        config.controlsEvery()),
                new Schedule(new CheckSalesGoals(sellers, salesReports, clock, config.batchSize()),
                        config.salesGoalsEvery())),
                config.runTimeout(), clock);
        var health = new HealthServer(config.healthPort(), json, scheduler::lastRuns);

        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("shutting down: letting the current run finish");
            scheduler.close();
            health.close();
            stopped.countDown();
        }, "shutdown"));

        health.start();
        scheduler.start();
        LOG.info("worker started: batch {}, run timeout {}, request timeout {}, attempts {}", config.batchSize(),
                config.runTimeout(), config.httpTimeout(), config.httpAttempts());
        stopped.await();
    }
}
