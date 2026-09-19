package io.nullnull;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MigrationRunnerTest {
    @Test
    void refusesMissingCredentialsWithoutStartingTheApplication() {
        assertThatThrownBy(() -> MigrationRunner.run(Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Missing SPRING_DATASOURCE_URL");
    }
    @Test
    void refusesJobsEnabledBeforeConnecting() {
        assertThatThrownBy(() -> MigrationRunner.run(Map.of(
                "SPRING_DATASOURCE_URL", "jdbc:postgresql://invalid.invalid/example",
                "SPRING_DATASOURCE_USERNAME", "synthetic",
                "SPRING_DATASOURCE_PASSWORD", "synthetic")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Migration requires jobs disabled");
    }
}
