-- BA-002 replay records for retryable commands (docs/architecture/ERD.md §1 IDEMPOTENCY_RECORDS,
-- §4 "Idempotency/Analytics", §6; docs/api/README.md §5).
-- Only the canonical request hash and the stored response projection are kept. The raw request body,
-- cookies, headers and Idempotency-Key secrets of other planes are never persisted here
-- (docs/architecture/SYSTEM_ARCHITECTURE.md §19.1).
CREATE TABLE idempotency_records (
    id              uuid         PRIMARY KEY,
    -- ERD §4: owner-scoped only. Bootstrap (POST /demo/sessions) runs before an owner exists and
    -- does not use this table, so no nullable-owner exception is created for it.
    owner_id        uuid         NOT NULL,
    route_key       varchar(200) NOT NULL,
    idempotency_key varchar(100) NOT NULL,
    request_hash    varchar(64)  NOT NULL,
    -- Null while the command is reserved and still running; both columns are filled together on
    -- completion, so "completed" is exactly response_status IS NOT NULL.
    response_status integer,
    response_body   jsonb,
    created_at      timestamptz  NOT NULL,
    expires_at      timestamptz  NOT NULL,
    -- No ON DELETE action: owner deletion is a tracked background job that removes rows in the
    -- documented order (ERD §4 "Deletion/background jobs"), not a silent database cascade.
    CONSTRAINT idempotency_records_owner_id_fkey FOREIGN KEY (owner_id) REFERENCES owners (id),
    CONSTRAINT idempotency_records_scope_key UNIQUE (owner_id, route_key, idempotency_key),
    CONSTRAINT idempotency_records_route_key_check CHECK (char_length(route_key) BETWEEN 1 AND 200),
    -- Bounds match the Idempotency-Key header schema in docs/api/openapi.yaml.
    CONSTRAINT idempotency_records_idempotency_key_check
        CHECK (char_length(idempotency_key) BETWEEN 16 AND 100),
    -- SHA-256 of the canonical request identity, lowercase hex.
    CONSTRAINT idempotency_records_request_hash_check CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT idempotency_records_response_pair_check
        CHECK ((response_status IS NULL) = (response_body IS NULL)),
    CONSTRAINT idempotency_records_response_status_check
        CHECK (response_status IS NULL OR response_status BETWEEN 100 AND 599),
    CONSTRAINT idempotency_records_expiry_check CHECK (expires_at > created_at),
    -- Every other variable-length column here is bounded, so the stored projection is too. The
    -- application-facing figure is 64 KiB (65536) of COMPACT JSON text, an engineering proposal
    -- (ERD §4) and not a documented one: the largest response shape in docs/api/README.md §14 is a
    -- 100-item trip, an order of magnitude below it. IdempotencyGuard refuses anything above that
    -- with a named error before the UPDATE runs.
    --
    -- This check cannot measure the same bytes: response_body::text is what PostgreSQL re-serialises
    -- out of jsonb, which writes a space after every ':' and every ','. So the column bound must be
    -- LOOSER than the application bound, or a value the application accepted would fail here as a
    -- constraint violation instead of the named error. How much looser is derived, not guessed:
    -- every inserted space follows a ':' or a ',' of the re-serialised value, jsonb never adds a
    -- member or element (duplicate keys are dropped, so it can only remove), and in the compact text
    -- each such separator owns two distinct bytes - itself and the byte after it, which is a key
    -- quote or a value start and therefore never another separator. At most one space per two
    -- compact bytes gives a structural ceiling of 1.5x, reached in the limit by an array of
    -- one-character elements; 2x (131072) clears it with margin.
    --
    -- That covers structure, not arithmetic: jsonb re-renders numbers in plain decimal notation, and
    -- no fixed multiple survives that. It is not only pathological exponents - an ordinary finite
    -- double is enough, because Jackson writes 1.0E18 in six characters and this image returns it as
    -- nineteen digits. So a projection well inside the application bound can still exceed this one.
    -- The column is therefore the authoritative bound, and JdbcIdempotencyRecordStore translates a
    -- violation of THIS constraint into the same named error the pre-check raises, so a caller never
    -- meets a raw driver failure. 65536 compact bytes stays the only limit callers are promised.
    CONSTRAINT idempotency_records_response_body_size_check
        CHECK (response_body IS NULL OR octet_length(response_body::text) <= 131072)
);

-- ERD §6: 24 hour retention, swept by a scheduled hard delete
-- (DELETE FROM idempotency_records WHERE expires_at <= :now). Every row expires, so the sweep
-- index is total rather than partial. IdempotencyGuard also treats a row past expires_at as an
-- absent slot, so retention holds between sweeps instead of depending on one.
CREATE INDEX idempotency_records_expires_at_idx ON idempotency_records (expires_at);
