package co.edu.corhuila.opti.worker.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Period;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class WorkerConfigTest {

    private static Map<String, String> required() {
        Map<String, String> env = new HashMap<>();
        env.put("CUSTOMERS_API_URL", "http://customers-api:8080");
        env.put("SALES_API_URL", "http://sales-api:8080");
        env.put("WORKFLOW_URL", "http://workflow:8080");
        env.put("AUTH_API_URL", "http://auth-api:8080");
        env.put("SERVICE_TOKEN", "token");
        return env;
    }

    @Test
    void defaultsAreExplicitAndBounded() {
        WorkerConfig config = WorkerConfig.from(required());

        assertThat(config.batchSize()).isEqualTo(50);
        assertThat(config.runTimeout()).isEqualTo(Duration.ofMinutes(2));
        assertThat(config.httpTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(config.httpAttempts()).isEqualTo(3);
        assertThat(config.quotationMaxAge()).isEqualTo(Duration.ofDays(3));
        assertThat(config.controlMaxAge()).isEqualTo(Period.ofMonths(12));
        assertThat(config.salesGoalsEvery()).isEqualTo(Duration.ofHours(1));
        assertThat(config.healthPort()).isEqualTo(8080);
    }

    @Test
    void everyProblemIsReportedTogetherAndNamesTheVariable() {
        Map<String, String> env = new HashMap<>();
        env.put("BATCH_SIZE", "5000");
        env.put("RUN_TIMEOUT", "soon");
        env.put("HTTP_ATTEMPTS", "0");

        assertThatThrownBy(() -> WorkerConfig.from(env)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CUSTOMERS_API_URL is required")
                .hasMessageContaining("SALES_API_URL is required")
                .hasMessageContaining("WORKFLOW_URL is required")
                .hasMessageContaining("SERVICE_TOKEN is required")
                .hasMessageContaining("BATCH_SIZE=5000")
                .hasMessageContaining("RUN_TIMEOUT=soon")
                .hasMessageContaining("HTTP_ATTEMPTS=0");
    }

    @Test
    void theBatchIsNeverUnbounded() {
        Map<String, String> env = required();
        env.put("BATCH_SIZE", "101");

        assertThatThrownBy(() -> WorkerConfig.from(env)).hasMessageContaining("BATCH_SIZE=101");
    }
}
