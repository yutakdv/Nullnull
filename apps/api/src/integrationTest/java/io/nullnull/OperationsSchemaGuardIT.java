package io.nullnull;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import io.nullnull.testsupport.TestcontainersConfiguration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * #183: the migration strategy an operations tool runs with. Each case uses its own throwaway schema and a
 * one-file migration location, so the application's schema - shared by every class at the gate - is never
 * touched.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@DisplayName("#183 an operations tool and the database schema")
class OperationsSchemaGuardIT {

    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;

    private final List<String> schemas = new ArrayList<>();
    private final List<String> roles = new ArrayList<>();

    @AfterEach
    void dropOwnSchemasAndRoles() {
        for (String schema : schemas) {
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
        for (String role : roles) {
            jdbc.execute("DROP ROLE IF EXISTS " + role);
        }
    }

    @Test
    @DisplayName("in a deployed environment a checkout ahead of the database migrates nothing and refuses")
    void aNewerCheckoutRefusesInsteadOfMigrating() {
        String schema = schema();
        OperationsContext.guardSchema(flyway(schema, "classpath:db/operations-guard-none"), "local");
        assertThatThrownBy(() -> OperationsContext.guardSchema(flyway(schema, "classpath:db/operations-guard"), "staging"))
                .hasMessageContaining("environment=staging")
                .hasMessageContaining("1 RESOLVED_VERSIONED_MIGRATION_NOT_APPLIED")
                // Flyway's own advice is to run migrate - the one thing this must never suggest for a deployed database.
                .hasMessageNotContaining("run migrate").hasMessageNotContaining("run repair")
                .isInstanceOfSatisfying(OperationsContext.Refused.class, refused -> assertThat(refused.code())
                        .isEqualTo(OperationsContext.Refused.Code.SCHEMA_NOT_THIS_CHECKOUT));
        assertThat(tableExists(schema)).as("nothing was migrated").isFalse();
    }

    @Test
    @DisplayName("in a deployed environment a migration changed after it was applied refuses")
    void aChangedMigrationRefuses() {
        String schema = schema();
        OperationsContext.guardSchema(flyway(schema, "classpath:db/operations-guard"), "local");
        assertThatThrownBy(() -> OperationsContext.guardSchema(flyway(schema, "classpath:db/operations-guard-changed"),
                "staging"))
                .hasMessageContaining("CHECKSUM_MISMATCH")
                .isInstanceOf(OperationsContext.Refused.class);
    }

    @Test
    @DisplayName("in a deployed environment a role that may not read the migration history stops with a code of its own")
    void anUnreadableHistoryIsRefusedWithACode() {
        String schema = schema();
        OperationsContext.guardSchema(flyway(schema, "classpath:db/operations-guard"), "local");
        // As in staging: the application role sees the schema but not flyway_schema_history.
        String role = schema + "_app";
        String password = UUID.randomUUID().toString().replace("-", "");
        roles.add(role);
        jdbc.execute("CREATE ROLE " + role + " LOGIN PASSWORD '" + password + "'");
        jdbc.execute("GRANT USAGE ON SCHEMA " + schema + " TO " + role);
        Flyway asTheApplication = Flyway.configure()
                .dataSource(((HikariDataSource) dataSource).getJdbcUrl(), role, password)
                .schemas(schema).locations("classpath:db/operations-guard").load();
        assertThatThrownBy(() -> OperationsContext.guardSchema(asTheApplication, "staging"))
                .hasMessageContaining("SPRING_FLYWAY_ENABLED=false")
                .isInstanceOfSatisfying(OperationsContext.Refused.class, refused -> assertThat(refused.code())
                        .isEqualTo(OperationsContext.Refused.Code.SCHEMA_UNCHECKABLE));
    }

    @Test
    @DisplayName("in a deployed environment a checkout behind the database passes: migrations are kept rollback-compatible")
    void anOlderCheckoutPasses() {
        String schema = schema();
        OperationsContext.guardSchema(flyway(schema, "classpath:db/operations-guard"), "local");
        OperationsContext.guardSchema(flyway(schema, "classpath:db/operations-guard-none"), "production");
        assertThat(tableExists(schema)).isTrue();
    }

    @Test
    @DisplayName("in a deployed environment the checkout the database was built for passes, without migrating")
    void theMatchingCheckoutPasses() {
        String schema = schema();
        OperationsContext.guardSchema(flyway(schema, "classpath:db/operations-guard"), "local");
        OperationsContext.guardSchema(flyway(schema, "classpath:db/operations-guard"), "staging");
        assertThat(tableExists(schema)).isTrue();
    }

    @Test
    @DisplayName("locally it migrates, as the application does there")
    void aLocalRunMigrates() {
        String schema = schema();
        OperationsContext.guardSchema(flyway(schema, "classpath:db/operations-guard"), "local");
        assertThat(tableExists(schema)).isTrue();
    }

    private String schema() {
        String schema = "ops_guard_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        schemas.add(schema);
        return schema;
    }

    private Flyway flyway(String schema, String location) {
        return Flyway.configure().dataSource(dataSource).schemas(schema).createSchemas(true)
                .locations(location).load();
    }

    private boolean tableExists(String schema) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT to_regclass(? || '.operations_guard_probe') IS NOT NULL", Boolean.class, schema));
    }
}
