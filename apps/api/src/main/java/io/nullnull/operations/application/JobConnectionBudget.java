package io.nullnull.operations.application;

/**
 * How many pooled database connections the job runtime can demand at once, and the startup check that
 * keeps that demand from starving the HTTP tier.
 *
 * <p>Per-type executors isolate <em>threads</em>. They do not isolate the connection pool: every
 * handler that opens a {@link JobContext#transactional} unit of work borrows from the same
 * {@code spring.datasource.hikari.maximum-pool-size} the request threads use. Measured on the shipped
 * pool of 10: ten in-flight handlers each holding a unit of work made {@code GET /health/ready} wait
 * out {@code spring.datasource.hikari.connection-timeout} and answer 503 after 10s, so the task would
 * be pulled out of the load balancer by a queue that was working exactly as configured.
 *
 * <p>The worst case is counted, not guessed. With {@code types} polled types and
 * {@code slots = the sum of nullnull.jobs.concurrency over those types}:
 * <ul>
 * <li>{@code slots} - each in-flight attempt can hold one connection inside its unit of work;</li>
 * <li>{@code + slots} - each in-flight attempt also has its own heartbeat transaction, which runs on a
 *     scheduler thread and therefore on a second connection while the unit of work holds the first;</li>
 * <li>{@code + types} - each type's poll loop holds a connection while its claim statement runs;</li>
 * <li>{@code + 1} - the retention sweep transaction.</li>
 * </ul>
 * so {@code worstCase = 2 * slots + types + 1}.
 *
 * <p>{@link #HTTP_CONNECTION_RESERVE} is what must be left over. One readiness response takes one
 * pooled connection at a time - the required database probe, then the jobs probe, in sequence - and the
 * ALB target health check on {@code /api/v1/health/ready}
 * (docs/operations/AWS_DEPLOYMENT.md §4) can overlap with a second caller, a scrape or an operator, so
 * two are reserved. Nothing is reserved here for ordinary request traffic: sizing the pool for traffic
 * is the operator's decision, while this check only guarantees that the worker alone can never make the
 * task fail its own health check.
 */
public final class JobConnectionBudget {

    /** Connections the worker must leave for readiness; see the class comment for the derivation. */
    public static final int HTTP_CONNECTION_RESERVE = 2;

    private JobConnectionBudget() {
    }

    /**
     * The most pooled connections the worker can hold at one instant.
     *
     * @param types how many job types are polled
     * @param slots the sum of the per-type concurrency over those types
     */
    public static int worstCaseConnections(int types, int slots) {
        if (types < 0 || slots < 0) {
            throw new IllegalArgumentException("types and slots are counts: " + types + ", " + slots);
        }
        return 2 * slots + types + 1;
    }

    /**
     * Fails the context start when the worker's worst case would leave readiness without a connection.
     *
     * <p>A startup failure rather than a warning for the same reason {@link JobProperties} floors its
     * durations: the alternative is a service that starts, looks configured, and takes itself out of
     * the load balancer the first time its queues fill up.
     *
     * @throws IllegalStateException naming the demand, the pool size and the two properties that move
     *         them
     */
    public static void requireHeadroom(int types, int slots, int maximumPoolSize) {
        int worstCase = worstCaseConnections(types, slots);
        int available = maximumPoolSize - HTTP_CONNECTION_RESERVE;
        if (worstCase <= available) {
            return;
        }
        throw new IllegalStateException("the job worker's worst case of " + worstCase
                + " pooled connections (2 x " + slots + " concurrency slots over " + types
                + " job types, + " + types + " poll claims, + 1 retention sweep) does not fit "
                + "spring.datasource.hikari.maximum-pool-size " + maximumPoolSize + " minus the "
                + HTTP_CONNECTION_RESERVE + " connections reserved for readiness; lower "
                + "nullnull.jobs.default-concurrency or nullnull.jobs.concurrency.<type>, or raise "
                + "NULLNULL_DB_POOL_MAX to at least " + (worstCase + HTTP_CONNECTION_RESERVE));
    }
}
