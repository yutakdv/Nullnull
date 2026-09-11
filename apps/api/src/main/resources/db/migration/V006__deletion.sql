-- BA-012 additive receipt and restore manifest. V001-V005 remain unchanged.
-- Old applications can run against these extra tables; rollback leaves receipts intact.
CREATE TABLE deletion_requests (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES owners(id),
    status_token_hash bytea UNIQUE,
    status varchar(20) NOT NULL,
    attempt_count integer NOT NULL DEFAULT 0,
    failure_code varchar(100),
    status_token_expires_at timestamptz NOT NULL,
    requested_at timestamptz NOT NULL,
    started_at timestamptz,
    completed_at timestamptz,
    updated_at timestamptz NOT NULL,
    CONSTRAINT deletion_status_check CHECK
        (status IN ('ACCEPTED', 'RUNNING', 'COMPLETED', 'PARTIAL_FAILED', 'FAILED')),
    CONSTRAINT deletion_token_hash_check CHECK (octet_length(status_token_hash) = 32),
    CONSTRAINT deletion_attempt_count_check CHECK (attempt_count >= 0)
);
CREATE INDEX deletion_requests_owner_idx ON deletion_requests(owner_id);
CREATE INDEX deletion_requests_pending_idx ON deletion_requests(status, requested_at)
    WHERE status IN ('ACCEPTED', 'RUNNING', 'PARTIAL_FAILED');

CREATE TABLE deletion_tombstones (
    id uuid PRIMARY KEY,
    deletion_request_id uuid NOT NULL REFERENCES deletion_requests(id),
    owner_id uuid NOT NULL UNIQUE REFERENCES owners(id),
    delete_before timestamptz NOT NULL,
    retain_until timestamptz NOT NULL,
    scope_hash varchar(64) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT deletion_scope_hash_check CHECK (scope_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT deletion_retention_check CHECK (retain_until > delete_before)
);
CREATE INDEX deletion_tombstones_request_idx ON deletion_tombstones(deletion_request_id);
CREATE INDEX deletion_tombstones_retention_idx ON deletion_tombstones(retain_until);
