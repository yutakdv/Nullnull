-- BA-033: client analytics events, privacy-minimised.
--
-- A client event is NOT evidence. It records that a screen believed something happened; the server
-- transaction is what actually happened. Nothing here is read back into a product decision, and
-- nothing joins to it - the table exists to measure the product, and that is the whole of it.
CREATE TABLE analytics_events (
    -- The client's eventId is the primary key, not a server id. Dedup is the point: a retry after a
    -- timeout resends the same batch, and the second arrival must be counted as a duplicate rather
    -- than stored twice. Making it the key puts that in the database instead of a read-then-write
    -- in the service, which two concurrent retries would both pass.
    event_id uuid PRIMARY KEY,
    -- Bound from the authenticated cookie, never from the payload (invariant 11). The contract's
    -- ClientEvent has no owner field at all; a client that invents one is simply ignored.
    owner_id uuid NOT NULL REFERENCES owners(id) ON DELETE CASCADE,
    -- ERD §"analytics_events": bound from the cookie like owner_id, and nulled rather than
    -- cascaded when the session is hard-deleted. The distinction matters: a deleted session must
    -- stop being identifiable, but the event still happened and the owner-level measurement it
    -- feeds is not the session's to withdraw. When the OWNER is deleted the row goes entirely.
    session_id uuid REFERENCES demo_sessions(id) ON DELETE SET NULL,
    name varchar(60) NOT NULL,
    -- When the screen says it happened, and when we actually received it. Both are kept because
    -- they differ: a batch can be queued offline, and occurredAt alone cannot be trusted for
    -- ordering since it comes from a device clock.
    occurred_at timestamptz NOT NULL,
    received_at timestamptz NOT NULL,
    -- Route TEMPLATE only. docs/contracts/events.schema.json allowlists the fifteen templates and
    -- refuses a concrete path, so this column cannot hold an identifier or anything a user typed
    -- (PM-016). The length bound matches the schema's maxLength.
    route varchar(200) NOT NULL,
    locale varchar(35) NOT NULL,
    timezone varchar(100) NOT NULL,
    app_version varchar(100),
    -- Validated against the canonical schema before it is written: each event name has a closed
    -- property allowlist there, so free text and coordinates cannot arrive in this column.
    properties jsonb NOT NULL,
    CONSTRAINT analytics_events_received_check CHECK (received_at >= occurred_at - interval '7 days')
);

-- Retention is 90 days (ERD §6). The sweep deletes by received_at rather than occurred_at: a device
-- clock set far in the past would otherwise make a row arrive already expired, and one set in the
-- future would keep it past its retention.
CREATE INDEX analytics_events_retention_idx ON analytics_events (received_at);

-- Owner erasure walks this directly; the FK cascade covers the scrub stage, and
-- AnalyticsOwnerDataEraser covers the soft-delete stage where the owner row still exists.
CREATE INDEX analytics_events_owner_idx ON analytics_events (owner_id);
