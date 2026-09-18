package io.nullnull.optimization;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.crowd.domain.ComparisonReasonCode;
import io.nullnull.testsupport.TestcontainersConfiguration;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * BA-051, the V029 half: the guards a proposal cannot be stored without satisfying.
 *
 * <p>V029 put two product rules into the schema, and a migration that applies is not a guard that
 * fires. These write the rows the CHECKs are meant to refuse and watch them be refused - and each
 * refusal is followed by the same row made legal, so the rejection is the constraint under test
 * rather than something else about the insert. That negative control is the part that turns "it
 * threw" into "it threw for this reason".
 *
 * <p>Raw SQL on purpose. The point is what the database will accept from anything at all, including
 * a future writer that forgets whatever the application layer checks first.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@DisplayName("BA-051 optimization proposal schema guards")
class OptimizationProposalSchemaIT {

    private static final String STATE = "{\"placeId\":\"p\",\"date\":\"2026-10-05\",\"position\":0}";

    @Autowired
    JdbcTemplate jdbc;

    private UUID ownerId;
    private UUID tripId;
    private UUID runId;

    @AfterEach
    void removeOnlyOwnFixtures() {
        if (runId != null) {
            jdbc.update("DELETE FROM optimization_changes WHERE proposal_id IN"
                    + " (SELECT id FROM optimization_proposals WHERE run_id = ?)", runId);
            jdbc.update("DELETE FROM optimization_proposals WHERE run_id = ?", runId);
            jdbc.update("DELETE FROM optimization_runs WHERE id = ?", runId);
        }
        if (tripId != null) {
            jdbc.update("DELETE FROM trips WHERE id = ?", tripId);
        }
        if (ownerId != null) {
            jdbc.update("DELETE FROM owners WHERE id = ?", ownerId);
        }
    }

    @Test
    @DisplayName("BA-051-T6 a proposal that was not eligible to be compared cannot carry a crowd delta")
    void anIneligibleProposalCannotCarryADelta() {
        UUID run = run();

        // Invariant 8: a number comparing this proposal's crowd against the baseline is a claim that
        // the pair was comparable. Storing one anyway is how an ineligible comparison reaches a
        // screen looking like a measurement.
        assertThatThrownBy(() -> proposal(run, 1, false, ComparisonReasonCode.DIFFERENT_SOURCE, new BigDecimal("1.2500")))
                .isInstanceOf(DataAccessException.class);

        // The same row without the delta is accepted, so what was refused is the delta and not the
        // ineligibility.
        assertThatCode(() -> proposal(run, 1, false, ComparisonReasonCode.DIFFERENT_SOURCE, null))
                .doesNotThrowAnyException();
        // And an eligible proposal may carry one, so the column is not simply unusable.
        assertThatCode(() -> proposal(run, 2, true, null, new BigDecimal("1.2500")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("BA-051-T7 a proposal that was not eligible to be compared cannot omit the reason")
    void anIneligibleProposalMustNameItsReason() {
        UUID run = run();

        assertThatThrownBy(() -> proposal(run, 1, false, null, null))
                .isInstanceOf(DataAccessException.class);

        // The mirror of the same biconditional, asserted here because one CHECK decides both: an
        // eligible proposal carrying a reason code would be a refusal nobody issued.
        assertThatThrownBy(() -> proposal(run, 2, true, ComparisonReasonCode.DIFFERENT_SOURCE, null))
                .isInstanceOf(DataAccessException.class);

        assertThatCode(() -> proposal(run, 3, false, ComparisonReasonCode.DIFFERENT_SOURCE, null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("BA-051-T8 a change missing the half its operation requires cannot be stored")
    void aChangeCannotBeStoredWithHalfItsState() {
        UUID proposal = proposal(run(), 1, true, null, null);

        // A MOVE with no before is a diff whose left side is missing - the traveller would be asked
        // to approve a change from nothing.
        assertThatThrownBy(() -> change(proposal, 0, "MOVE", null, STATE))
                .isInstanceOf(DataAccessException.class);
        // An ADD that claims a before is describing an item that did not exist.
        assertThatThrownBy(() -> change(proposal, 1, "ADD", STATE, STATE))
                .isInstanceOf(DataAccessException.class);
        // A REMOVE that claims an after is describing an item it just removed.
        assertThatThrownBy(() -> change(proposal, 2, "REMOVE", STATE, STATE))
                .isInstanceOf(DataAccessException.class);

        assertThatCode(() -> change(proposal, 3, "MOVE", STATE, STATE)).doesNotThrowAnyException();
        assertThatCode(() -> change(proposal, 4, "ADD", null, STATE)).doesNotThrowAnyException();
        assertThatCode(() -> change(proposal, 5, "REMOVE", STATE, null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("BA-051-T9 a stored proposal and its changes cannot be edited afterwards")
    void aStoredProposalIsImmutable() {
        UUID proposal = proposal(run(), 1, true, null, null);
        UUID change = change(proposal, 0, "MOVE", STATE, STATE);

        // A proposal is what the traveller was shown before saying yes or no. Editing one after the
        // fact rewrites what they agreed to, and no later reader could tell that it had happened.
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE optimization_proposals SET summary = 'edited' WHERE id = ?", proposal))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE optimization_changes SET sequence = 9 WHERE id = ?", change))
                .isInstanceOf(DataAccessException.class);

        // Deleting is not what this guard is about: a run's proposals go when the run does, which is
        // what the cascade from optimization_runs is for.
        assertThatCode(() -> jdbc.update("DELETE FROM optimization_changes WHERE id = ?", change))
                .doesNotThrowAnyException();
    }

    private UUID run() {
        ownerId = UUID.randomUUID();
        tripId = UUID.randomUUID();
        runId = UUID.randomUUID();
        OffsetDateTime at = OffsetDateTime.now();
        jdbc.update("INSERT INTO owners (id, kind, account_id, locale, timezone, created_at)"
                + " VALUES (?, 'ANONYMOUS', NULL, 'ko-KR', 'Asia/Seoul', ?)", ownerId, at);
        jdbc.update("INSERT INTO trips (id, owner_id, title, start_date, end_date, timezone,"
                + " planning_level, status, version, created_at, updated_at)"
                + " VALUES (?, ?, 'proposal schema fixture', ?::date, ?::date, 'Asia/Seoul',"
                + " 'NOTHING', 'DRAFT', 1, ?, ?)", tripId, ownerId, "2026-10-05", "2026-10-08", at, at);
        // TRIP scope carries neither a target item nor a target date, which is the only shape that
        // needs no trip_items row of its own.
        jdbc.update("INSERT INTO optimization_runs (id, trip_id, requested_by_owner_id, scope,"
                + " include_candidates, status, input_trip_version, queued_at)"
                + " VALUES (?, ?, ?, 'TRIP', false, 'QUEUED', 1, ?)", runId, tripId, ownerId, at);
        return runId;
    }

    private UUID proposal(UUID run, int rank, boolean eligible, String reasonCode, BigDecimal delta) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO optimization_proposals (id, run_id, rank, summary,"
                + " comparison_eligible, comparison_reason_code, crowd_delta, validation_summary,"
                + " created_at) VALUES (?, ?, ?, 'a proposal', ?, ?, ?, '{}'::jsonb, ?)",
                id, run, rank, eligible, reasonCode, delta, OffsetDateTime.now());
        return id;
    }

    private UUID change(UUID proposal, int sequence, String operation, String before, String after) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO optimization_changes (id, proposal_id, trip_item_id, operation,"
                + " before_value, after_value, sequence)"
                + " VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)",
                id, proposal, UUID.randomUUID(), operation, before, after, sequence);
        return id;
    }
}
