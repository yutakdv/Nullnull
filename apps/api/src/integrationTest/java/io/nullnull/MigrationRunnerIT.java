package io.nullnull;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.nullnull.testsupport.TestcontainersConfiguration;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class MigrationRunnerIT {
    @Test
    void migratesWithoutWebContextAndRestrictsApplicationRole() throws Exception {
        PostgreSQLContainer container = null;
        var environment = new HashMap<String, String>();
        try {
            if ("external".equals(System.getenv("NULLNULL_TEST_DATABASE"))) {
                for (String key : new String[]{"SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_USERNAME", "SPRING_DATASOURCE_PASSWORD"}) {
                    environment.put(key, System.getenv(key));
                }
            } else {
                container = new PostgreSQLContainer(DockerImageName.parse(TestcontainersConfiguration.POSTGRES_IMAGE)
                        .asCompatibleSubstituteFor("postgres")).withDatabaseName("nullnull");
                container.start();
                environment.put("SPRING_DATASOURCE_URL", container.getJdbcUrl());
                environment.put("SPRING_DATASOURCE_USERNAME", container.getUsername());
                environment.put("SPRING_DATASOURCE_PASSWORD", container.getPassword());
            }
            environment.put("NULLNULL_JOBS_ENABLED", "false");
            environment.put("SPRING_FLYWAY_ENABLED", "true");
            environment.put("NULLNULL_APP_DB_USERNAME", "nullnull_app");
            environment.put("NULLNULL_APP_DB_PASSWORD", "synthetic-integration-only");
            MigrationRunner.run(environment);
            MigrationRunner.run(environment); // Retry must neither rotate nor recreate the role.
            var wrongSecret = new HashMap<>(environment);
            wrongSecret.put("NULLNULL_APP_DB_PASSWORD", "different-synthetic-secret");
            assertThatThrownBy(() -> MigrationRunner.run(wrongSecret))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Application role provisioning failed");
            try (var connection = DriverManager.getConnection(environment.get("SPRING_DATASOURCE_URL"),
                    "nullnull_app", "synthetic-integration-only"); var query = connection.createStatement()) {
                assertThat(query.execute("SELECT id FROM owners LIMIT 0")).isTrue();
                assertThatThrownBy(() -> query.execute("SELECT * FROM flyway_schema_history"))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> query.execute("CREATE TABLE migration_runner_forbidden (id integer)"))
                        .isInstanceOf(SQLException.class);
            }
        } finally {
            if (container != null) container.stop();
        }
    }
}
