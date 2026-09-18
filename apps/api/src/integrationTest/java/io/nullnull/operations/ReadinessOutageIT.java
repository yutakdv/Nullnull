package io.nullnull.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import java.io.IOException;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * #258: a database the application cannot reach is 503 NOT_READY on getReadiness, never a 500.
 *
 * <p>The staging rehearsal reproduced one shape - the database's name stopped resolving - and saw a
 * 500 INTERNAL_ERROR. Two more shapes an RDS failure can take were not observed there, so all three
 * are driven here: the name does not resolve, the port refuses the connection, and the port accepts
 * and then never answers.
 *
 * <p>The database the suite shares cannot be stopped - the required gate runs every context against
 * one PostgreSQL (AGENTS.md rule 6). Instead the application's own DataSource is wrapped, and during
 * an outage every connection it hands out comes from a real Hikari pool pointed at an address that
 * fails in that shape: the driver, the pool and the exceptions are the ones production meets, and
 * every probe and filter reaches the database the way it always does. Each case first proves its
 * shape by asking the broken pool directly, so a case whose outage failed in some other way cannot
 * pass by accident.
 */
@SpringBootTest(properties = "nullnull.jobs.enabled=false")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class,
        ReadinessOutageIT.Outage.class})
@DisplayName("getReadiness while the database cannot be reached")
class ReadinessOutageIT {

    /** Wraps the application's DataSource so a case can send its connections elsewhere. */
    @TestConfiguration
    static class Outage {
        @Bean
        static BeanPostProcessor switchableDataSource() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    return bean instanceof DataSource source && !(bean instanceof SwitchableDataSource)
                            ? new SwitchableDataSource(source)
                            : bean;
                }
            };
        }
    }

    /** The real pool until a case switches; then the outage pool, counted. */
    static final class SwitchableDataSource extends DelegatingDataSource {

        private volatile DataSource outage;
        private final AtomicInteger attempts = new AtomicInteger();

        SwitchableDataSource(DataSource delegate) {
            super(delegate);
        }

        @Override
        public Connection getConnection() throws SQLException {
            DataSource broken = outage;
            if (broken == null) {
                return super.getConnection();
            }
            attempts.incrementAndGet();
            return broken.getConnection();
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            DataSource broken = outage;
            if (broken == null) {
                return super.getConnection(username, password);
            }
            attempts.incrementAndGet();
            return broken.getConnection(username, password);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;

    private final List<AutoCloseable> opened = new ArrayList<>();

    @AfterEach
    void endTheOutage() throws Exception {
        switchable().outage = null;
        for (AutoCloseable resource : opened) {
            resource.close();
        }
        opened.clear();
    }

    @Test
    @DisplayName("a database whose name no longer resolves is 503, not 500")
    void anUnresolvableDatabaseIsNotReady() throws Exception {
        // .invalid is reserved never to resolve (RFC 2606) - the shape of a stopped container's name.
        // A longer wait than the other two: resolving the name has to finish failing, or the pool
        // times out first and the chain holds no UnknownHostException to prove the shape with.
        HikariDataSource broken = pool("jdbc:postgresql://nullnull-db-gone.invalid:5432/nullnull", 5_000);
        assertThatThrownBy(broken::getConnection).satisfies(failure ->
                assertThat(chain(failure)).anyMatch(UnknownHostException.class::isInstance));

        assertNotReadyWhile(broken);
    }

    @Test
    @DisplayName("a database that refuses the connection is 503, not 500")
    void aRefusingDatabaseIsNotReady() throws Exception {
        int closedPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            closedPort = probe.getLocalPort();
        }
        HikariDataSource broken = pool("jdbc:postgresql://127.0.0.1:" + closedPort + "/nullnull", 250);
        assertThatThrownBy(broken::getConnection).satisfies(failure ->
                assertThat(chain(failure)).anyMatch(ConnectException.class::isInstance));

        assertNotReadyWhile(broken);
    }

    @Test
    @DisplayName("a database that accepts and never answers is 503 within the pool's timeout, not a hang")
    void aSilentDatabaseIsNotReady() throws Exception {
        ServerSocket silent = new ServerSocket(0);
        opened.add(silent);
        List<Socket> held = new ArrayList<>();
        Thread acceptor = new Thread(() -> {
            try {
                while (!silent.isClosed()) {
                    // Accepted and kept, never answered: the driver's startup packet gets no reply.
                    held.add(silent.accept());
                }
            } catch (IOException closed) {
                // The case is over.
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();
        opened.add(() -> {
            for (Socket socket : held) {
                socket.close();
            }
        });
        HikariDataSource broken = pool("jdbc:postgresql://127.0.0.1:" + silent.getLocalPort()
                + "/nullnull?connectTimeout=1&socketTimeout=1&loginTimeout=1", 250);
        assertThatThrownBy(broken::getConnection).isInstanceOf(SQLTransientConnectionException.class);

        assertNotReadyWhile(broken);
    }

    private void assertNotReadyWhile(DataSource broken) throws Exception {
        // The control: the same context, the same request, before the outage.
        mvc.perform(get("/api/v1/health/ready")).andExpect(status().isOk());

        // The wrapper is one bean for the whole class, so its count starts again with each outage.
        switchable().attempts.set(0);
        switchable().outage = broken;
        mvc.perform(get("/api/v1/health/ready"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SOURCE_UNAVAILABLE"))
                .andExpect(header().string("Retry-After", "5"));
        // Exactly one: the database probe met the outage, and nothing else on the request path did - the
        // optional probes that read the database too were not run once the required one was down. Each
        // would otherwise have waited out the pool's timeout in turn.
        assertThat(switchable().attempts.get())
                .as("connections asked of the unreachable database while answering")
                .isEqualTo(1);
    }

    private SwitchableDataSource switchable() {
        return (SwitchableDataSource) dataSource;
    }

    /** A real pool that fails within {@code waitMillis}: one connection and no failure at creation. */
    private HikariDataSource pool(String url, long waitMillis) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername("nullnull");
        config.setPassword("unused-outage-pool");
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(waitMillis);
        config.setValidationTimeout(250);
        config.setInitializationFailTimeout(-1);
        HikariDataSource pool = new HikariDataSource(config);
        opened.add(pool);
        return pool;
    }

    private static List<Throwable> chain(Throwable failure) {
        List<Throwable> causes = new ArrayList<>();
        for (Throwable cause = failure; cause != null && !causes.contains(cause); cause = cause.getCause()) {
            causes.add(cause);
        }
        return causes;
    }
}
