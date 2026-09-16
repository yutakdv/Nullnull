-- BA-052: the run freezes the inputs its fingerprint was computed from, not only the output.
--
-- V024 stored data_fingerprint and algorithm_version. RunFingerprint computes that digest over TEN
-- inputs, and three of them were kept nowhere: policyVersion, policyHash, and catalogVersion. Seven
-- are recoverable at decide time because they live on immutable rows - the frozen snapshot set's
-- crowd_snapshots carry source_registry_version and normalization_version (V011), and the run row
-- carries input_trip_version, input_revision_id and expires_at - but a digest cannot be recomputed
-- from seven tenths of its input.
--
-- The consequence was not "a test cannot be written". catalog_version is derived from the source
-- registry's CURRENT revision (CatalogVersion.current()), so recomputing at decide time would read
-- today's value against a digest built from the run's. One ordinary catalog revision would then
-- refuse every preview waiting to be applied, with DATA_CHANGED, for a reason that is not the
-- evidence changing. That is the same defect shape as a column narrower than its source: a value
-- that rejects real measurements to honour one nobody took.
--
-- policy_hash is also the only thing that can answer BA-052-T7. algorithm_version holds the
-- PIPELINE version, and a withdrawn policy is not a changed pipeline.

ALTER TABLE optimization_runs
    ADD COLUMN policy_version varchar(50),
    ADD COLUMN policy_hash char(64),
    ADD COLUMN catalog_version varchar(100);

-- Nullable, because a run that never reached READY has no fingerprint and therefore no inputs to
-- freeze - the same condition data_fingerprint already carries. The CHECK ties the four together in
-- one direction only: having a fingerprint means having the inputs it was computed from. It says
-- nothing about a run without one, which is every QUEUED, RUNNING and FAILED row.
--
-- This is the second line, not the first. That a READY run carries all four is written by
-- markReady's single statement; a CHECK can only refuse the rows it is shown.
ALTER TABLE optimization_runs ADD CONSTRAINT optimization_runs_fingerprint_inputs_check CHECK (
    data_fingerprint IS NULL
    OR (policy_version IS NOT NULL AND policy_hash IS NOT NULL AND catalog_version IS NOT NULL)
);

-- The same shape PolicyDescriptor refuses to construct without: a SHA-256 hex digest. Checked here
-- as well because a row can arrive from anything, including a future writer that skips the record.
ALTER TABLE optimization_runs ADD CONSTRAINT optimization_runs_policy_hash_check CHECK (
    policy_hash IS NULL OR policy_hash ~ '^[0-9a-f]{64}$'
);
