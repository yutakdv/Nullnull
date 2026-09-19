-- BA-053: deleting a trip that had an APPLY or a REVERT answered 500.
--
-- trips cascades into trip_revisions, and three columns pointed at those revisions with NO ACTION:
-- optimization_decisions.before_revision_id, optimization_decisions.after_revision_id and
-- optimization_runs.input_revision_id. The decision and run rows do go away with the trip - both
-- cascade from optimization_runs.trip_id - but PostgreSQL runs the NO ACTION check for the deleted
-- revisions before that cascade has removed them, so the statement failed:
--
--   update or delete on table "trip_revisions" violates foreign key constraint
--   "optimization_decisions_before_revision_id_fkey" on table "optimization_decisions"
--
-- Seen on staging rc.6 (the optimize-item flow's own cleanup, DELETE /trips/{tripId} = 500) and
-- reproduced locally before this migration (BA-053-T12). A run with no decision never hit it, which
-- is why BA-053-T3's cascade case stayed green: a KEEP stores no revision ids at all (V030's shape
-- CHECK), and a READY run that was never decided has no decision row.
--
-- An owner's erasure deletes their trips with the same statement and did NOT fail: its erasers run in
-- name order and "optimization-runs" sorts before "trip-owned-aggregates". Measured both ways; what
-- that ordering was holding up, and what this migration takes off it, is written in the BA-053 card
-- (docs/roles/BACKEND_AI_PLAYBOOK.md#ba-053), which can still be corrected - this file cannot.
--
-- Deferred, not CASCADE, and not SET NULL. SET NULL is impossible: V030's shape CHECK requires both
-- revision ids on an APPLY and on a REVERT, so nulling them breaks the row it kept. CASCADE looked
-- right and is worse than the defect: nothing in the schema says a run's revisions belong to that
-- run's trip, so one misaligned row - a hand-written insert, a future bug - would make deleting trip B
-- delete trip A's run, proposals and decisions. Today that case fails closed, and it should stay that
-- way. DEFERRABLE INITIALLY DEFERRED keeps the check and moves it to COMMIT, by which point a
-- whole-trip delete has removed the referencing rows through their own cascade - and a misaligned
-- reference still refuses, because at COMMIT that row is still there.
ALTER TABLE optimization_decisions
    DROP CONSTRAINT optimization_decisions_before_revision_id_fkey,
    ADD CONSTRAINT optimization_decisions_before_revision_id_fkey
        FOREIGN KEY (before_revision_id) REFERENCES trip_revisions(id)
        DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE optimization_decisions
    DROP CONSTRAINT optimization_decisions_after_revision_id_fkey,
    ADD CONSTRAINT optimization_decisions_after_revision_id_fkey
        FOREIGN KEY (after_revision_id) REFERENCES trip_revisions(id)
        DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE optimization_runs
    DROP CONSTRAINT optimization_runs_input_revision_id_fkey,
    ADD CONSTRAINT optimization_runs_input_revision_id_fkey
        FOREIGN KEY (input_revision_id) REFERENCES trip_revisions(id)
        DEFERRABLE INITIALLY DEFERRED;
