package io.nullnull.optimization;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.crowd.domain.ComparisonReasonCode;
import io.nullnull.optimization.application.OptimizationProposalStore;
import io.nullnull.optimization.domain.OptimizationChange;
import io.nullnull.optimization.domain.OptimizationChangeOperation;
import io.nullnull.optimization.domain.OptimizationProposal;
import io.nullnull.testsupport.TestcontainersConfiguration;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * What a run proposed, written and read back unchanged.
 *
 * <p>The ordering assertions are the substance. Proposals are ranked against each other and changes
 * are sequenced within a proposal, so a read that returned them in arrival order would hand a
 * traveller a different answer from the one the optimizer gave - the same set, differently ordered,
 * is a different recommendation.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@DisplayName("optimization proposal storage")
class OptimizationProposalStoreIT {

    private static final String BEFORE = "{\"position\":0}";
    private static final String AFTER = "{\"position\":1}";

    @Autowired
    OptimizationProposalStore proposals;

    @Autowired
    JdbcTemplate jdbc;

    private UUID ownerId;
    private UUID tripId;
    private UUID runId;

    @AfterEach
    void removeOnlyOwnFixtures() {
        jdbc.update("DELETE FROM optimization_changes WHERE proposal_id IN"
                + " (SELECT id FROM optimization_proposals WHERE run_id = ?)", runId);
        jdbc.update("DELETE FROM optimization_proposals WHERE run_id = ?", runId);
        jdbc.update("DELETE FROM optimization_runs WHERE id = ?", runId);
        jdbc.update("DELETE FROM trips WHERE id = ?", tripId);
        jdbc.update("DELETE FROM owners WHERE id = ?", ownerId);
    }

    @Test
    @DisplayName("proposals come back in rank order with their changes in sequence order")
    void aRunReadsBackTheAnswerItWasGiven() {
        UUID run = seedRun();
        Instant at = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        // Inserted out of order on purpose: rank 2 first, and the changes of each proposal reversed.
        OptimizationProposal second = new OptimizationProposal(UUID.randomUUID(), run, 2,
                "the ineligible one", false, ComparisonReasonCode.DIFFERENT_SOURCE, null, null, "{\"checks\":[]}", at,
                List.of(change(1, OptimizationChangeOperation.REMOVE, BEFORE, null),
                        change(0, OptimizationChangeOperation.MOVE, BEFORE, AFTER)));
        OptimizationProposal first = new OptimizationProposal(UUID.randomUUID(), run, 1,
                "the eligible one", true, null, new BigDecimal("-1.2500"), 12, "{\"checks\":[]}", at,
                List.of(change(0, OptimizationChangeOperation.ADD, null, AFTER)));

        proposals.insertAll(List.of(second, first));

        List<OptimizationProposal> read = proposals.findByRun(run);
        assertThat(read).extracting(OptimizationProposal::rank).containsExactly(1, 2);
        assertThat(read.get(0).summary()).isEqualTo("the eligible one");
        // numeric(14,4) round-trips as scale 4, which is the column's promise rather than the
        // literal's - comparing by value keeps the assertion about the number and not its spelling.
        assertThat(read.get(0).crowdDelta()).isEqualByComparingTo(new BigDecimal("-1.2500"));
        assertThat(read.get(0).travelMinutesDelta()).isEqualTo(12);
        assertThat(read.get(1).comparisonEligible()).isFalse();
        assertThat(read.get(1).comparisonReasonCode()).isEqualTo(ComparisonReasonCode.DIFFERENT_SOURCE);
        assertThat(read.get(1).crowdDelta()).isNull();
        assertThat(read.get(1).changes()).extracting(OptimizationChange::sequence)
                .containsExactly(0, 1);
        assertThat(read.get(1).changes()).extracting(OptimizationChange::operation)
                .containsExactly(OptimizationChangeOperation.MOVE, OptimizationChangeOperation.REMOVE);
        assertThat(read.get(1).changes().get(1).afterValue()).isNull();
        assertThat(read.get(0).changes().get(0).beforeValue()).isNull();
    }

    @Test
    @DisplayName("a run with no proposals reads back empty rather than failing")
    void aRunMayHaveProposedNothing() {
        // BA-050 states it: a run can reach READY with no proposal stored. The read has to say that
        // plainly, because "no answer yet" and "an answer with nothing in it" are different states
        // and only one of them is a bug.
        assertThat(proposals.findByRun(seedRun())).isEmpty();
    }

    private static OptimizationChange change(int sequence, OptimizationChangeOperation operation,
            String before, String after) {
        return new OptimizationChange(UUID.randomUUID(), UUID.randomUUID(), operation, before, after,
                sequence);
    }

    private UUID seedRun() {
        ownerId = UUID.randomUUID();
        tripId = UUID.randomUUID();
        runId = UUID.randomUUID();
        OffsetDateTime at = OffsetDateTime.now();
        jdbc.update("INSERT INTO owners (id, kind, account_id, locale, timezone, created_at)"
                + " VALUES (?, 'ANONYMOUS', NULL, 'ko-KR', 'Asia/Seoul', ?)", ownerId, at);
        jdbc.update("INSERT INTO trips (id, owner_id, title, start_date, end_date, timezone,"
                + " planning_level, status, version, created_at, updated_at)"
                + " VALUES (?, ?, 'proposal store fixture', ?::date, ?::date, 'Asia/Seoul',"
                + " 'NOTHING', 'DRAFT', 1, ?, ?)", tripId, ownerId, "2026-10-05", "2026-10-08", at, at);
        jdbc.update("INSERT INTO optimization_runs (id, trip_id, requested_by_owner_id, scope,"
                + " include_candidates, status, input_trip_version, queued_at)"
                + " VALUES (?, ?, ?, 'TRIP', false, 'QUEUED', 1, ?)", runId, tripId, ownerId, at);
        return runId;
    }
}
