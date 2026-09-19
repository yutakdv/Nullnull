package io.nullnull;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;

@DisplayName("#183 how an operations tool starts")
class OperationsContextTest {

    private static final String TARGET = "postgresql://db.internal:5432/nullnull";
    private static final String URL = "jdbc:postgresql://db.internal:5432/nullnull";

    @Test
    @DisplayName("start() runs the tool with the job worker off and refuses an unnamed deployed database before refreshing")
    void startTurnsTheWorkerOffAndRefusesBeforeRefreshing() {
        Map<String, String> seen = new HashMap<>();
        ApplicationContextInitializer<ConfigurableApplicationContext> deployedSetting = context -> {
            // A deployed environment says NULLNULL_JOBS_ENABLED=true. It sits right below the command line here,
            // above the system property Gradle's test task sets to false, so only the command line can win.
            MutablePropertySources sources = context.getEnvironment().getPropertySources();
            MapPropertySource environment = new MapPropertySource("deployed-environment",
                    Map.of("nullnull.jobs.enabled", "true"));
            if (sources.contains("commandLineArgs")) {
                sources.addAfter("commandLineArgs", environment);
            } else {
                sources.addFirst(environment);
            }
            seen.put("jobs", context.getEnvironment().getProperty("nullnull.jobs.enabled"));
            sources.addFirst(new MapPropertySource("deployed",
                    Map.of("nullnull.env", "staging", "spring.datasource.url", URL)));
        };
        // No database exists for this test: getting past the check would fail on a connection, not with this code.
        assertThatThrownBy(() -> OperationsContext.start(OperationsContext.Access.WRITE, deployedSetting))
                .isInstanceOfSatisfying(OperationsContext.Refused.class, refused -> assertThat(refused.code())
                        .isEqualTo(OperationsContext.Refused.Code.OPERATIONS_TARGET_NOT_CONFIRMED));
        assertThat(seen.get("jobs")).as("the command line reached the environment the tool starts in").isEqualTo("false");
    }

    @Test
    @DisplayName("the target names the database without its user, password or query")
    void theTargetCarriesNoCredential() {
        assertThat(OperationsContext.target(
                "jdbc:postgresql://reader:hunter2@db.internal:5432/nullnull?password=hunter2&sslmode=require"))
                .isEqualTo(TARGET);
        assertThat(OperationsContext.target("jdbc:postgresql://localhost/nullnull"))
                .isEqualTo("postgresql://localhost/nullnull");
        assertThat(OperationsContext.target("")).isEqualTo("unknown");
        assertThat(OperationsContext.target("jdbc:h2:mem:x")).isEqualTo("unknown");
        assertThat(OperationsContext.target("jdbc:postgresql://h1:5432,h2:5432/nullnull")).isEqualTo("unknown");
    }

    @Test
    @DisplayName("a URL whose query moves the connection elsewhere has no target, so it cannot be confirmed as another")
    void aQueryThatReplacesTheAddressLeavesNoTarget() {
        for (String query : List.of("host=prod.internal", "PGHOST=prod.internal", "Port=6543", "dbname=other",
                "pgdbname=other", "sslmode=require&host=prod.internal")) {
            assertThat(OperationsContext.target(URL + "?" + query)).as(query).isEqualTo("unknown");
        }
        assertThat(OperationsContext.target(URL + "?sslmode=require&service=x")).isEqualTo(TARGET);
        // The driver reads past a '#', and a URL naming no database takes one from somewhere else.
        assertThat(OperationsContext.target(URL + "?ApplicationName=x#&host=prod.internal")).isEqualTo("unknown");
        assertThat(OperationsContext.target(URL + "#x?host=prod.internal")).isEqualTo("unknown");
        assertThat(OperationsContext.target("jdbc:postgresql://db.internal:5432/?service=x")).isEqualTo("unknown");
        assertThat(OperationsContext.target("jdbc:postgresql://db.internal:5432")).isEqualTo("unknown");
    }

    @Test
    @DisplayName("a pool or Flyway URL of its own leaves no target, so a deployed write cannot be confirmed past it")
    void aSecondUrlLeavesNoTarget() {
        for (String other : List.of("spring.datasource.hikari.jdbc-url", "spring.flyway.url")) {
            GenericApplicationContext context = context("staging", URL, true);
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("other",
                    Map.of(other, "jdbc:postgresql://prod.internal:5432/nullnull")));
            assertThatThrownBy(() -> OperationsContext.guarded(OperationsContext.Access.WRITE, TARGET, List.of())
                    .initialize(context)).as(other)
                    .isInstanceOfSatisfying(OperationsContext.Refused.class, refused -> assertThat(refused.code())
                            .isEqualTo(OperationsContext.Refused.Code.OPERATIONS_TARGET_UNREADABLE));
        }
    }

    @Test
    @DisplayName("a writing tool in a deployed environment runs only against the database the shell named")
    void aDeployedWriteNeedsTheTargetNamed() {
        for (String deployed : List.of("staging", "production")) {
            assertThatThrownBy(() -> OperationsContext.requireConfirmedTarget(deployed, TARGET, null))
                    .as(deployed).hasMessageContaining("NULLNULL_OPERATIONS_TARGET=" + TARGET)
                    .hasMessageContaining("unset")
                    .isInstanceOfSatisfying(OperationsContext.Refused.class, refused -> assertThat(refused.code())
                            .isEqualTo(OperationsContext.Refused.Code.OPERATIONS_TARGET_NOT_CONFIRMED));
            // What the shell said is not echoed: a pasted URL may carry a password.
            assertThatThrownBy(() -> OperationsContext.requireConfirmedTarget(deployed, TARGET,
                    "postgresql://other:5432/nullnull?password=hunter2")).as(deployed)
                    .hasMessageContaining("another value").hasMessageNotContaining("hunter2");
            assertThatCode(() -> OperationsContext.requireConfirmedTarget(deployed, TARGET, " " + TARGET + " "))
                    .as(deployed).doesNotThrowAnyException();
        }
        assertThatThrownBy(() -> OperationsContext.requireConfirmedTarget("staging", "unknown", "unknown"))
                .hasMessageContaining("could not be read")
                .isInstanceOfSatisfying(OperationsContext.Refused.class, refused -> assertThat(refused.code())
                        .isEqualTo(OperationsContext.Refused.Code.OPERATIONS_TARGET_UNREADABLE));
    }

    @Test
    @DisplayName("locally nothing needs naming, as before")
    void aLocalWriteNeedsNoTarget() {
        for (String local : List.of("local", "test")) {
            assertThatCode(() -> OperationsContext.requireConfirmedTarget(local, TARGET, null))
                    .as(local).doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("a label outside the application's four is refused, not read as local or as deployed")
    void anUnknownLabelIsRefused() {
        for (String label : List.of("STAGING", "Local", "prod", "")) {
            GenericApplicationContext context = context(label, URL, true);
            assertThatThrownBy(() -> OperationsContext.guarded(OperationsContext.Access.READ, null, List.of())
                    .initialize(context)).as(label)
                    .isInstanceOfSatisfying(OperationsContext.Refused.class, refused -> assertThat(refused.code())
                            .isEqualTo(OperationsContext.Refused.Code.ENVIRONMENT_UNKNOWN));
            assertThat(context.containsBeanDefinition(FlywayMigrationStrategy.class.getName())).as(label).isFalse();
        }
    }

    @Test
    @DisplayName("an unnamed database is refused before the context refreshes, so no bean ever touches it")
    void theTargetIsRefusedBeforeAnythingStarts() {
        GenericApplicationContext context = context("staging", URL, true);
        assertThatThrownBy(() -> OperationsContext.guarded(OperationsContext.Access.WRITE, null, List.of())
                .initialize(context)).hasMessageContaining("unset");
        assertThat(context.containsBeanDefinition(FlywayMigrationStrategy.class.getName()))
                .as("the schema guard is installed only past the check").isFalse();
    }

    @Test
    @DisplayName("the tool's own settings are applied before the target is read, so the named target is the one it uses")
    void theToolsSettingsComeBeforeTheCheck() {
        GenericApplicationContext context = context("staging", "jdbc:postgresql://yaml-default:5432/nullnull", true);
        ApplicationContextInitializer<ConfigurableApplicationContext> fromEnvFile = ctx -> ctx.getEnvironment()
                .getPropertySources().addFirst(new MapPropertySource("env-file", Map.of("spring.datasource.url", URL)));
        OperationsContext.guarded(OperationsContext.Access.WRITE, TARGET, List.of(fromEnvFile)).initialize(context);
        assertThat(context.containsBeanDefinition(FlywayMigrationStrategy.class.getName())).isTrue();
    }

    @Test
    @DisplayName("a reading tool in a deployed environment needs nothing named")
    void aDeployedReadNeedsNoTarget() {
        GenericApplicationContext context = context("staging", URL, true);
        OperationsContext.guarded(OperationsContext.Access.READ, null, List.of()).initialize(context);
        assertThat(context.containsBeanDefinition(FlywayMigrationStrategy.class.getName())).isTrue();
    }

    @Test
    @DisplayName("the target line says which schema check runs, including none when the environment turns Flyway off")
    void theTargetLineNamesTheSchemaCheck() {
        assertThat(targetLine("local", true)).endsWith("environment=local access=read schema=migrate");
        assertThat(targetLine("staging", true)).endsWith("environment=staging access=read schema=validate");
        assertThat(targetLine("staging", false)).isEqualTo(
                "operations target=" + TARGET + " environment=staging access=read schema=unchecked");
    }

    private static String targetLine(String environment, boolean flyway) {
        PrintStream original = System.out;
        ByteArrayOutputStream printed = new ByteArrayOutputStream();
        System.setOut(new PrintStream(printed, true, StandardCharsets.UTF_8));
        try {
            OperationsContext.guarded(OperationsContext.Access.READ, null, List.of())
                    .initialize(context(environment, URL, flyway));
        } finally {
            System.setOut(original);
        }
        return printed.toString(StandardCharsets.UTF_8).strip();
    }

    private static GenericApplicationContext context(String environment, String url, boolean flyway) {
        GenericApplicationContext context = new GenericApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("deployed", Map.of(
                "nullnull.env", environment, "spring.datasource.url", url, "spring.flyway.enabled", flyway)));
        return context;
    }
}
