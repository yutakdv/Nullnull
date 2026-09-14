package io.nullnull.testsupport;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * A DataSource that counts what passes through it, so a cost can be asserted instead of timed.
 *
 * <p>BA-070's safety boundary forbids recording a target as a measurement and says to keep a noisy
 * CI runner's numbers away from an SLO. A p95 measured here would be exactly that. What CI can state
 * honestly is structural: how many statements and how many pooled connections a request of a known
 * size costs, and - the part that matters - whether those numbers grow with the size of the data.
 *
 * <p>Counting at the DataSource rather than inside a framework is deliberate: the application reaches
 * the database through JdbcClient, JdbcTemplate and JPA, and a counter attached to any one of them
 * would miss the others. Everything that runs SQL takes a Connection from here first.
 */
public final class CountingDataSource extends DelegatingDataSource {

    private static final Set<String> STATEMENT_METHODS =
            Set.of("prepareStatement", "createStatement", "prepareCall");

    private final AtomicInteger statements = new AtomicInteger();
    private final AtomicInteger connections = new AtomicInteger();

    public CountingDataSource(DataSource delegate) {
        super(delegate);
    }

    @Override
    public Connection getConnection() throws java.sql.SQLException {
        return counting(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws java.sql.SQLException {
        return counting(super.getConnection(username, password));
    }

    /** Zeroes both counters and returns what they held, so a test measures one call at a time. */
    public Counts reset() {
        return new Counts(statements.getAndSet(0), connections.getAndSet(0));
    }

    public record Counts(int statements, int connections) {
    }

    private Connection counting(Connection connection) {
        connections.incrementAndGet();
        InvocationHandler handler = (proxy, method, arguments) -> {
            if (STATEMENT_METHODS.contains(method.getName())) {
                statements.incrementAndGet();
            }
            try {
                return method.invoke(connection, arguments);
            } catch (java.lang.reflect.InvocationTargetException thrown) {
                throw thrown.getCause();
            }
        };
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, handler);
    }
}
