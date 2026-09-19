package io.nullnull;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import io.nullnull.testsupport.TestcontainersConfiguration;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * BA-070-T2 on the channel the other two cannot reach: the datasource URL, when it carries credentials.
 *
 * <p>RedactionAndDenylistIT and CookieParserLogRedactionIT plant a canary in a request. This one plants it in the
 * one place a request never reaches - {@code spring.datasource.url} - because PostgreSQL's driver accepts
 * {@code ?user=...&password=...} and a deployment may hand the credential over that way. Three libraries write that
 * URL down on their own (application.yaml turns each of them down and says what it loses): Hibernate and Flyway on
 * every start, the driver when it rejects the URL's shape.
 *
 * <p>The canary has to be a password the database actually accepts, or the context never connects and nothing is
 * logged - a sweep over an empty log passes for the wrong reason. So the class makes a login role whose password
 * is the canary, joined to the suite's own role so it can read the schema, and connects with {@code -c role=} set
 * to that role so anything the start-up creates is owned by it and the canary role can be dropped. A second canary
 * rides in {@code sslpassword}, which the driver ignores without a client key and Flyway's own mask does not cover.
 * The Testcontainers run takes a container of its own, because the suite's shared one does not exist before a
 * context does; the Compose gate ({@code nullnull.test.database} anything but {@code testcontainers}, as
 * TestcontainersConfiguration reads it) uses the database the whole suite shares.
 *
 * <p>Output is captured from before the context starts (OutputCaptureExtension begins at beforeAll, the context is
 * built when the test instance is prepared), and this context is its own: the dynamic property source is declared
 * on this class alone, so no other class's context is reused for it.
 *
 * <p>Not covered, and not closable by a log level: a {@code user:password@} URL fails the start, and the startup
 * error carries it - the pool's refusal masks {@code password=} parameters only, and with a port the host becomes
 * {@code user:password@host}, which the connection failure names. Only refusing credentials in the URL would close
 * that.
 */
@SpringBootTest(properties = "nullnull.jobs.enabled=false")
@ExtendWith(OutputCaptureExtension.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("BA-070 redaction of the datasource URL")
class DatasourceUrlLogRedactionIT {

    private static final String ROLE = "redact_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private static final String CANARY = "REDACT" + UUID.randomUUID().toString().replace("-", "");
    private static final String SSL_CANARY = "SSLREDACT" + UUID.randomUUID().toString().replace("-", "");

    private static Admin admin;
    private static PostgreSQLContainer container;

    @Autowired HikariDataSource dataSource;
    @Autowired JdbcTemplate jdbc;

    /** The suite's own connection: the database the gate shares, or a container of this class's own. */
    private record Admin(String url, String user, String password) {
    }

    @DynamicPropertySource
    static void aUrlThatCarriesThePassword(DynamicPropertyRegistry registry) {
        admin = admin();
        execute("CREATE ROLE " + ROLE + " LOGIN PASSWORD '" + CANARY + "'", "GRANT " + admin.user() + " TO " + ROLE);
        String options = URLEncoder.encode("-c role=" + admin.user(), StandardCharsets.UTF_8).replace("+", "%20");
        registry.add("spring.datasource.url", () -> admin.url() + (admin.url().contains("?") ? "&" : "?")
                + "user=" + ROLE + "&password=" + CANARY + "&sslpassword=" + SSL_CANARY + "&options=" + options);
        registry.add("spring.datasource.username", () -> "");
        registry.add("spring.datasource.password", () -> "");
    }

    @AfterAll
    static void dropTheCanaryRole() {
        if (admin != null) {
            // The case that closes the pool may not have run (a filtered run); its sessions go first either way.
            execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename = '" + ROLE + "'",
                    "DROP ROLE IF EXISTS " + ROLE);
        }
        if (container != null) {
            container.stop();
        }
    }

    @Test
    @DisplayName("BA-070-T2 a password carried in the datasource URL never reaches a log line")
    void thePasswordInTheUrlIsNotLogged(CapturedOutput output) {
        try {
            // The context logged in with the canary, so every startup line that names the URL had it to write.
            assertThat(jdbc.queryForObject("SELECT session_user", String.class)).isEqualTo(ROLE);
        } finally {
            // Before the role is dropped, so no session of it is left; @AfterAll drops it.
            dataSource.close();
        }
        // The capture covered this context's start, or the sweep below proves nothing.
        assertThat(output.getAll()).contains("Started " + getClass().getSimpleName());
        assertThat(output.getAll()).doesNotContain(CANARY).doesNotContain(SSL_CANARY);
    }

    @Test
    @DisplayName("BA-070-T2 a datasource URL the driver rejects is not echoed with its password")
    void aRejectedUrlIsNotEchoed(CapturedOutput output) {
        // Its own canary: the capture also holds this context's start, which the other case sweeps.
        String rejected = "REJECTED" + UUID.randomUUID().toString().replace("-", "");
        for (String url : List.of(
                "jdbc:postgresql://db.invalid:5432?user=ops&password=" + rejected,
                "jdbc:postgresql://db.invalid:5432/nullnull/extra?user=ops&password=" + rejected,
                "jdbc:postgresql://ops:" + rejected + "@db.invalid/nullnull")) {
            // Rejected by shape, before any connection is tried - the path a misconfigured deployment takes first.
            assertThat(DriverManager.drivers().anyMatch(driver -> accepts(driver, url))).as(url).isFalse();
        }
        assertThat(output.getAll()).doesNotContain(rejected);
    }

    private static boolean accepts(Driver driver, String url) {
        try {
            return driver.acceptsURL(url);
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static Admin admin() {
        String mode = System.getProperty("nullnull.test.database",
                System.getenv().getOrDefault("NULLNULL_TEST_DATABASE", "testcontainers"));
        if (!"testcontainers".equalsIgnoreCase(mode)) {
            return new Admin(setting("spring.datasource.url", "SPRING_DATASOURCE_URL"),
                    setting("spring.datasource.username", "SPRING_DATASOURCE_USERNAME"),
                    setting("spring.datasource.password", "SPRING_DATASOURCE_PASSWORD"));
        }
        container = new PostgreSQLContainer(DockerImageName
                .parse(TestcontainersConfiguration.POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres"));
        container.start();
        return new Admin(container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }

    private static String setting(String property, String variable) {
        return System.getProperty(property, System.getenv(variable));
    }

    private static void execute(String... statements) {
        try (Connection connection = DriverManager.getConnection(admin.url(), admin.user(), admin.password());
                Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("a statement on the canary role failed", failure);
        }
    }
}
