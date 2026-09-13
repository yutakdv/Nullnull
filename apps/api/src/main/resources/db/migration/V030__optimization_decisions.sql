-- BA-052: what the traveller decided about a run, and what it did to the trip.
--
-- The last of the three tables V024 deferred. A decision is not a status on the run: the run says
-- what was proposed, and this says what a person chose, which is why an APPLY and the REVERT that
-- undoes it are two rows rather than one row that changed its mind.

CREATE TABLE optimization_decisions (
    id uuid PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES optimization_runs(id) ON DELETE CASCADE,
    proposal_id uuid NOT NULL REFERENCES optimization_proposals(id) ON DELETE CASCADE,
    owner_id uuid NOT NULL REFERENCES owners(id),
    decision varchar(10) NOT NULL,
    -- The trip version the decision was made against - the If-Match the caller sent. Kept for KEEP
    -- as well as APPLY: a KEEP is also an answer about a particular trip, and without this there is
    -- no way to say which one it answered.
    expected_trip_version bigint NOT NULL,
    -- What the trip became. Null for KEEP, because a KEEP changes nothing and a version recorded
    -- there would claim it did.
    resulting_trip_version bigint,
    before_revision_id uuid REFERENCES trip_revisions(id),
    after_revision_id uuid REFERENCES trip_revisions(id),
    -- The APPLY this row undoes. Only a REVERT has one, and it points at another decision rather
    -- than at the run: what is undone is a choice, not a computation.
    reverted_decision_id uuid REFERENCES optimization_decisions(id),
    -- How long the APPLY can still be taken back. Only an APPLY has one - a REVERT cannot itself be
    -- reverted, which the contract says by omitting revertUntil from RevertOptimizationDecision.
    revert_until timestamptz,
    decided_at timestamptz NOT NULL,
    CONSTRAINT optimization_decisions_decision_check CHECK (decision IN ('APPLY', 'KEEP', 'REVERT')),
    CONSTRAINT optimization_decisions_version_check CHECK (expected_trip_version >= 1),
    -- One CHECK per decision, in the shape of the contract's three response variants, because
    -- "exactly these fields" is the part that matters. Written together rather than as separate
    -- nullability rules so that a row cannot satisfy all the individual rules while describing
    -- nothing the API can serialise.
    CONSTRAINT optimization_decisions_shape_check CHECK (
        (decision = 'KEEP' AND resulting_trip_version IS NULL AND before_revision_id IS NULL
            AND after_revision_id IS NULL AND reverted_decision_id IS NULL AND revert_until IS NULL)
        OR (decision = 'APPLY' AND resulting_trip_version IS NOT NULL
            AND before_revision_id IS NOT NULL AND after_revision_id IS NOT NULL
            AND reverted_decision_id IS NULL AND revert_until IS NOT NULL)
        OR (decision = 'REVERT' AND resulting_trip_version IS NOT NULL
            AND before_revision_id IS NOT NULL AND after_revision_id IS NOT NULL
            AND reverted_decision_id IS NOT NULL AND revert_until IS NULL)
    )
);

-- "한 run 최초 결정은 최대 하나다" as a constraint rather than as a rule the application remembers.
-- A partial unique index, because a REVERT is a second decision on the same run and must not be
-- refused by the guard that stops a second APPLY.
--
-- This is the second line under BA-052-T1: two callers racing to APPLY the same run produce one row
-- and one rejection even if the application check is removed, because the index decides it in the
-- database rather than in whichever transaction read first.
CREATE UNIQUE INDEX optimization_decisions_one_initial_per_run
    ON optimization_decisions (run_id) WHERE decision <> 'REVERT';

-- A decision belongs to the owner who made it, and owner erasure sweeps by this column.
CREATE INDEX optimization_decisions_owner_idx ON optimization_decisions (owner_id, decided_at DESC);
-- listOptimizationHistory reads an owner's decisions newest first; the index above serves it, and
-- this one serves "what happened to this proposal" without scanning the owner's history.
CREATE INDEX optimization_decisions_proposal_idx ON optimization_decisions (proposal_id);

-- Immutable, like the proposals they answer (V029) and the snapshots those were judged against
-- (V011). A decision is a record of what a person chose; a REVERT is a new row that points at the
-- APPLY, never an edit of it, which is also why the history can be read back as a sequence.
--
-- What this cannot check is that reverted_decision_id names an APPLY rather than a KEEP or another
-- REVERT: that is a different row, and a CHECK sees only its own. BA-053 owns that assertion.
CREATE TRIGGER optimization_decisions_immutable_guard
BEFORE UPDATE ON optimization_decisions
FOR EACH ROW EXECUTE FUNCTION optimization_prevent_proposal_mutation();
