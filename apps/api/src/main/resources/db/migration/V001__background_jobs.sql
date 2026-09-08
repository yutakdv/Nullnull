-- BA-002/BA-005 foundation: leased persistent job queue (docs/architecture/ERD.md §3, §4 "Deletion/background jobs").
-- Payload holds domain IDs only; never raw itinerary text, coordinates, or provider secrets.
CREATE TABLE background_jobs (
    id                 uuid PRIMARY KEY,
    type               varchar(64)  NOT NULL,
    deduplication_key  varchar(200) NOT NULL,
    status             varchar(32)  NOT NULL,
    payload_reference  jsonb        NOT NULL DEFAULT '{}'::jsonb,
    attempt_count      integer      NOT NULL DEFAULT 0,
    max_attempts       integer      NOT NULL,
    next_attempt_at    timestamptz  NOT NULL,
    locked_by          varchar(128),
    lease_until        timestamptz,
    heartbeat_at       timestamptz,
    last_error_code    varchar(64),
    created_at         timestamptz  NOT NULL,
    completed_at       timestamptz,
    CONSTRAINT background_jobs_deduplication_key_key UNIQUE (deduplication_key),
    CONSTRAINT background_jobs_status_check
        CHECK (status IN ('READY', 'RETRY', 'RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT background_jobs_attempt_count_check CHECK (attempt_count >= 0),
    CONSTRAINT background_jobs_max_attempts_check CHECK (max_attempts >= 1),
    CONSTRAINT background_jobs_lease_pair_check
        CHECK ((locked_by IS NULL) = (lease_until IS NULL))
);

CREATE INDEX background_jobs_claim_idx
    ON background_jobs (status, next_attempt_at)
    WHERE status IN ('READY', 'RETRY');
