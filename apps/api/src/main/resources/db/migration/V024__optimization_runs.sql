-- BA-050: the preview-only optimization run, its job, and the evidence it froze.
--
-- A run is a record of a question asked at one instant: this trip, at this version, judged against
-- these snapshots. Every column here exists so that the answer can be re-derived later from what the
-- run actually used rather than from whatever the world looks like when someone reads it.
--
-- Two tables from ERD §2 are deliberately NOT created here.
--
--   optimization_proposals/changes/decisions belong to the slices that produce them (BA-051, BA-052).
--   A run can reach READY with no proposal stored, and that is the honest intermediate state: the
--   capability flag is OFF, so nothing user-facing can reach it.
--
--   optimization_run_route_snapshots would reference route_matrix_snapshots, which does not exist -
--   there is no route provider in P0. A junction to a table with no rows and no producer would be a
--   shape with nothing behind it, and the slice that adds the provider is the one that can say what a
--   route snapshot is.

CREATE TABLE optimization_runs (
    id uuid PRIMARY KEY,
    -- ON DELETE CASCADE: retention says a run lives for as long as its trip (ERD §6). The owner
    -- erasure job deletes trips, and a run that outlived its trip would be a record of an itinerary
    -- nobody can read any more.
    trip_id uuid NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
    requested_by_owner_id uuid NOT NULL REFERENCES owners(id),
    scope varchar(20) NOT NULL,
    -- The target, whose shape depends on the scope; see optimization_runs_target_check.
    target_item_id uuid REFERENCES trip_items(id) ON DELETE CASCADE,
    target_date date,
    include_candidates boolean NOT NULL,
    status varchar(20) NOT NULL,
    -- What the run was asked about, frozen at preflight. The revision is nullable because a trip that
    -- has never been revised has none to point at.
    input_trip_version bigint NOT NULL,
    input_revision_id uuid REFERENCES trip_revisions(id),
    data_fingerprint varchar(64),
    algorithm_version varchar(100),
    failure_code varchar(40),
    failure_message text,
    queued_at timestamptz NOT NULL,
    started_at timestamptz,
    completed_at timestamptz,
    expires_at timestamptz,
    CONSTRAINT optimization_runs_scope_check CHECK (scope IN ('ITEM', 'DAY', 'TRIP')),
    CONSTRAINT optimization_runs_status_check CHECK (status IN
        ('QUEUED', 'RUNNING', 'READY', 'APPLIED', 'KEPT', 'REVERTED', 'FAILED', 'EXPIRED')),
    -- createOptimization says it outright: ITEM requires exactly targetItemId, DAY requires exactly
    -- targetDate, and TRIP accepts neither. Written as one CHECK per scope rather than two nullability
    -- rules, because "exactly" is the part that matters - a DAY run carrying an item id would be a
    -- request nobody made.
    CONSTRAINT optimization_runs_target_check CHECK (
        (scope = 'ITEM' AND target_item_id IS NOT NULL AND target_date IS NULL)
        OR (scope = 'DAY' AND target_date IS NOT NULL AND target_item_id IS NULL)
        OR (scope = 'TRIP' AND target_item_id IS NULL AND target_date IS NULL)),
    CONSTRAINT optimization_runs_version_check CHECK (input_trip_version >= 1),
    -- The same shape media_assets.checksum uses: lowercase hex SHA-256.
    CONSTRAINT optimization_runs_fingerprint_check CHECK
        (data_fingerprint IS NULL OR data_fingerprint ~ '^[0-9a-f]{64}$'),
    -- OptimizationFailure.code in docs/api/openapi.yaml. APPLY_FAILED is in the contract's enum but
    -- cannot be a RUN failure: it describes a decision that could not be applied, which is recorded on
    -- the decision in BA-052. A run that stored it would report a failure of something the run never
    -- attempted.
    CONSTRAINT optimization_runs_failure_code_check CHECK (failure_code IS NULL OR failure_code IN
        ('TRIP_CHANGED', 'DATA_CHANGED', 'LOCK_CONFLICT', 'ROUTE_UNAVAILABLE', 'NO_IMPROVEMENT')),
    -- A failure is a code AND the sentence that goes with it; the contract requires both. Storing one
    -- without the other would publish a failure object that cannot be built.
    CONSTRAINT optimization_runs_failure_pair_check CHECK
        ((failure_code IS NULL) = (failure_message IS NULL)),
    -- FAILED is the only status that carries a failure code, and it is the only one that must.
    CONSTRAINT optimization_runs_failed_has_code_check CHECK
        ((status = 'FAILED') = (failure_code IS NOT NULL)),
    -- A run that never started has no startedAt, and every terminal status has a completedAt. EXPIRED
    -- is terminal for a run that was never decided, so it is completed too.
    CONSTRAINT optimization_runs_started_check CHECK
        ((status = 'QUEUED') = (started_at IS NULL)),
    CONSTRAINT optimization_runs_completed_check CHECK
        ((status IN ('QUEUED', 'RUNNING')) = (completed_at IS NULL)),
    -- PM-015 names this as missing: "READY의 fingerprint/expiry 조건도 상태별로 강제되지 않는다". The
    -- contract types both as nullable because they are absent while a run is queued, so only the
    -- server can say when they stop being optional. A READY preview without the evidence hash has
    -- nothing for APPLY to revalidate against, and one without an expiry never stops being offerable.
    --
    -- Nothing can trip this constraint yet, and that is expected rather than an oversight: BA-050
    -- closes the gate in front of READY but writes no preview, so the first row that has to satisfy
    -- it is the first READY BA-051 stores. It is written now so that the rule is already in force
    -- when that happens, instead of being retrofitted around rows that were let in without it.
    CONSTRAINT optimization_runs_ready_evidence_check CHECK
        (status <> 'READY' OR (data_fingerprint IS NOT NULL AND expires_at IS NOT NULL)),
    CONSTRAINT optimization_runs_time_order_check CHECK
        ((started_at IS NULL OR started_at >= queued_at)
        AND (completed_at IS NULL OR started_at IS NULL OR completed_at >= started_at))
);

COMMENT ON COLUMN optimization_runs.input_trip_version IS
    'BA-050: the trip version the run was asked about. Re-checked before READY is stored, so a trip '
    'edited while the run was in flight fails the run instead of proposing against a trip that moved.';

COMMENT ON COLUMN optimization_runs.data_fingerprint IS
    'BA-050: hash of the evidence the run actually used. Null until the worker has frozen it; APPLY '
    'revalidates against it so a preview built on withdrawn data cannot be applied.';

-- ERD §8.
CREATE INDEX optimization_runs_trip_queued_idx ON optimization_runs (trip_id, queued_at DESC);
CREATE INDEX optimization_runs_active_idx ON optimization_runs (status, queued_at)
    WHERE status IN ('QUEUED', 'RUNNING');
-- listOptimizationHistory reads the owner's runs newest-first across trips.
CREATE INDEX optimization_runs_owner_queued_idx
    ON optimization_runs (requested_by_owner_id, queued_at DESC);

-- Every snapshot set the run used, by the part it played. A junction rather than a column on the run:
-- a run reads more than one set (ERD §2), and collapsing them into one FK would record which evidence
-- was last rather than which evidence was used.
CREATE TABLE optimization_run_snapshot_sets (
    run_id uuid NOT NULL REFERENCES optimization_runs(id) ON DELETE CASCADE,
    snapshot_set_id uuid NOT NULL REFERENCES snapshot_sets(id),
    purpose varchar(20) NOT NULL,
    sequence integer NOT NULL,
    PRIMARY KEY (run_id, snapshot_set_id, purpose),
    CONSTRAINT optimization_run_snapshot_sets_purpose_check CHECK
        (purpose IN ('BEFORE', 'AFTER', 'CANDIDATE')),
    CONSTRAINT optimization_run_snapshot_sets_sequence_check CHECK (sequence >= 0),
    -- The order within a purpose is an ordering, so two sets cannot claim the same place in it.
    CONSTRAINT optimization_run_snapshot_sets_sequence_unique UNIQUE (run_id, purpose, sequence)
);

-- No ON DELETE CASCADE towards snapshot_sets on purpose: a snapshot set a run froze must not be
-- removable while the run that used it still exists, or the run would claim evidence nobody can read.
CREATE INDEX optimization_run_snapshot_sets_set_idx
    ON optimization_run_snapshot_sets (snapshot_set_id);
