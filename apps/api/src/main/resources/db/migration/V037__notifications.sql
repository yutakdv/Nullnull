-- BA-085: the P1 notification centre.
--
-- docs/architecture/ERD.md §"NOTIFICATIONS" is the canonical column list and
-- docs/architecture/SYSTEM_ARCHITECTURE.md §4 puts this table in the social module, beside
-- posts/saved_posts/feed_feedback. It is not a new module.
CREATE TABLE notifications (
    id uuid PRIMARY KEY,
    -- Invariant 11: bound from the authenticated cookie, never from a payload. The contract's
    -- Notification has no owner field at all. This foreign key is also what enrols the table in
    -- DeletionIT's coverage sweep, which finds owner-owned tables by their FK to owners rather
    -- than by the column's name - so the eraser and this migration land together.
    owner_id uuid NOT NULL REFERENCES owners(id) ON DELETE CASCADE,
    -- Bound by the CHECK below, not by this length; varchar(20) follows the convention the other
    -- enum-like columns in this directory use and fits the longest value (OPTIMIZATION_FAILED, 19).
    type varchar(20) NOT NULL,
    -- Lengths come from the contract's Notification schema (title 120, body 500, deepLink 300),
    -- which is where a reviewer should look when one of them has to change.
    title varchar(120) NOT NULL,
    body varchar(500) NOT NULL,
    deep_link varchar(300) NOT NULL,
    created_at timestamptz NOT NULL,
    read_at timestamptz,
    -- Computed by the application and stored, NOT derived here. Two measurements forced that and
    -- both are recorded in NotificationRetentionIT, which is where the rule actually lives:
    -- timestamptz + interval is STABLE, so a generated column is refused outright, and a CHECK
    -- spelling the derivation is ACCEPTED by PostgreSQL yet answers differently per session
    -- TimeZone - a row written under UTC fails the same constraint re-validated under a DST zone.
    -- This file cannot be edited once applied, so the derivation is not written into it.
    expires_at timestamptz NOT NULL,
    CONSTRAINT notifications_type_check CHECK (type IN (
        'OPTIMIZATION_READY', 'OPTIMIZATION_FAILED', 'CROWD_ALERT',
        'TRIP_REMINDER', 'TRIP_CONFLICT', 'SOURCE_DEGRADED')),
    -- ERD §"notifications.deep_link" asks for a database check AND an application parser. Only the
    -- part that does not move is written here: an absolute path with no scheme, host, query or
    -- fragment. The route allowlist itself is moving right now (trips->trip, and the Live routes
    -- are still open), and a list pinned in an unfixable file would go stale on the next change;
    -- the contract's deepLink pattern and the application parser are its canon.
    --
    -- The second character may not be a slash. Without that clause "//intranet/admin" satisfies
    -- every other rule here and a browser resolves it as an absolute URL onto another host, which
    -- is the exact escape this constraint exists to refuse.
    CONSTRAINT notifications_deep_link_check CHECK (deep_link ~ '^/[A-Za-z0-9_-][A-Za-z0-9/_-]*$'),
    CONSTRAINT notifications_read_after_created_check CHECK (read_at IS NULL OR read_at >= created_at),
    CONSTRAINT notifications_expires_after_created_check CHECK (expires_at > created_at)
);

-- ERD §8 verbatim. Serves the unread count, which filters on read_at IS NULL.
CREATE INDEX notifications_owner_unread_idx ON notifications (owner_id, read_at, created_at DESC);

-- The list page's own order. ERD §8's index leads with read_at, which the listing does not filter
-- on, so it cannot serve this ordering. id is the tie-break: two notifications written in the same
-- transaction share created_at, and a cursor that cannot separate them re-serves or skips a row -
-- the defect CursorSurfaceMatrixIT exists to catch.
CREATE INDEX notifications_owner_created_idx ON notifications (owner_id, created_at DESC, id DESC);

-- The retention sweep's access path. NotificationTtlEraser deletes by expires_at, which already
-- carries "90 days, or 30 days after it was read, whichever is earlier" (ERD §6) because the
-- application resolved that to an instant on write and again when the row is marked read.
CREATE INDEX notifications_retention_idx ON notifications (expires_at);
