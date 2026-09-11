-- BA-010 additive identity tables. Older applications ignore these tables on rollback.
CREATE TABLE demo_sessions (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES owners(id),
    token_hash bytea NOT NULL UNIQUE CHECK (octet_length(token_hash) = 32),
    last_seen_at timestamptz,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL
);
CREATE INDEX demo_sessions_owner_idx ON demo_sessions(owner_id);
CREATE INDEX demo_sessions_active_expiry_idx ON demo_sessions(expires_at) WHERE revoked_at IS NULL;
CREATE TABLE demo_session_csrf_tokens (
    id uuid PRIMARY KEY,
    demo_session_id uuid NOT NULL REFERENCES demo_sessions(id) ON DELETE CASCADE,
    token_hash bytea NOT NULL UNIQUE CHECK (octet_length(token_hash) = 32),
    expires_at timestamptz NOT NULL,
    last_used_at timestamptz,
    created_at timestamptz NOT NULL
);
CREATE INDEX demo_session_csrf_expiry_idx ON demo_session_csrf_tokens(demo_session_id, expires_at);
