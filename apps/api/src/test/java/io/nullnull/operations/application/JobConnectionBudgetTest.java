package io.nullnull.operations.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BA-005: per-type executors isolate threads, not the connection pool, so the worker's worst case is
 * counted and checked instead of assumed.
 *
 * <p>The measured failure this stands in front of: ten in-flight handlers, each holding one unit of
 * work, took the whole shipped pool of ten, and {@code /health/ready} answered 503 after ten seconds
 * because both of its probes waited out the Hikari connection timeout - a task pulled out of the load
 * balancer by a queue doing exactly what it was configured to do.
 */
@DisplayName("BA-005 job worker connection budget")
class JobConnectionBudgetTest {

    @Test
    void theWorstCaseCountsUnitsOfWorkHeartbeatsPollsAndTheSweep() {
        // Three types at a concurrency of two: 6 units of work + 6 heartbeats + 3 claims + 1 sweep.
        assertThat(JobConnectionBudget.worstCaseConnections(3, 6)).isEqualTo(16);
        // No handler yet: only the retention sweep can hold a connection.
        assertThat(JobConnectionBudget.worstCaseConnections(0, 0)).isOne();
    }

    @Test
    void aWorkerThatCouldStarveReadinessIsRefusedWithBothNumbers() {
        // The measured configuration: one type at concurrency 10 against the shipped pool of 10.
        assertThatThrownBy(() -> JobConnectionBudget.requireHeadroom(1, 10, 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("22")
                .hasMessageContaining("spring.datasource.hikari.maximum-pool-size 10")
                .hasMessageContaining("nullnull.jobs.concurrency")
                .hasMessageContaining("24");
    }

    @Test
    void theEdgeIsWhereReadinessStillHasItsReserve() {
        // 1 type, 3 slots: 3 + 3 + 1 + 1 = 8, which fits 10 - 2 exactly.
        assertThatCode(() -> JobConnectionBudget.requireHeadroom(1, 3, 10)).doesNotThrowAnyException();
        // One slot more is 10, which would leave readiness nothing.
        assertThatThrownBy(() -> JobConnectionBudget.requireHeadroom(1, 4, 10))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aWorkerWithNoHandlersStillNeedsTheSweepToFit() {
        assertThatCode(() -> JobConnectionBudget.requireHeadroom(0, 0, 3)).doesNotThrowAnyException();
        // A pool of two is the reserve and nothing else, so even the retention sweep does not fit.
        assertThatThrownBy(() -> JobConnectionBudget.requireHeadroom(0, 0, 2))
                .isInstanceOf(IllegalStateException.class);
    }
}
