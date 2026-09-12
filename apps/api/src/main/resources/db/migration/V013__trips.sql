-- BA-030 Phase A: the trip aggregate's metadata, interests and revision log.
--
-- Deliberately NOT in this migration: trip_items, trip_candidates and trip_constraints. Those need
-- SeedTripItem.startTime, whose wire format is still contradictory across the three canon documents
-- (openapi.yaml says format: time, which REQUIRES an offset; ERD.md says trip_items.start_time is a
-- PostgreSQL `time`, which cannot hold one; README.md says offset-less local time). The BA-030 card
-- forbids fixing that boundary while PM-008 is open, so the tables that would store it wait.
--
-- Days are not a table. TripDetail.days is derived from [start_date, end_date] in the trip timezone,
-- which is what makes the created trip's shape deterministic rather than stored state that can drift
-- out of step with the range.
CREATE TABLE trips (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES owners(id) ON DELETE CASCADE,
    title varchar(100) NOT NULL,
    start_date date NOT NULL,
    end_date date NOT NULL,
    timezone varchar(100) NOT NULL,
    planning_level varchar(20) NOT NULL,
    status varchar(20) NOT NULL,
    version bigint NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    archived_at timestamptz,
    -- Bounds mirror TripDetail.title/timezone in docs/api/openapi.yaml so the database can never hold
    -- a value the schema forbids; the lower bounds also reject the empty string and whitespace.
    CONSTRAINT trips_title_check CHECK (char_length(title) BETWEEN 1 AND 100 AND btrim(title) <> ''),
    CONSTRAINT trips_timezone_check CHECK (char_length(timezone) BETWEEN 1 AND 100 AND btrim(timezone) <> ''),
    -- ERD §4: start_date <= end_date, version >= 1.
    CONSTRAINT trips_range_check CHECK (start_date <= end_date),
    -- 30 calendar days inclusive, the same bound createTrip documents and TripDetail.days caps at.
    CONSTRAINT trips_range_length_check CHECK (end_date - start_date <= 29),
    CONSTRAINT trips_version_check CHECK (version >= 1),
    CONSTRAINT trips_status_check CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED')),
    CONSTRAINT trips_planning_level_check CHECK
        (planning_level IN ('NOTHING', 'MUST_VISIT_ONLY', 'MOSTLY_PLANNED')),
    CONSTRAINT trips_timestamps_check CHECK (updated_at >= created_at),
    -- archived_at exists exactly while the trip is ARCHIVED, so the column and the status cannot
    -- disagree about whether the trip is archived.
    CONSTRAINT trips_archived_shape_check CHECK
        ((status = 'ARCHIVED' AND archived_at IS NOT NULL)
         OR (status <> 'ARCHIVED' AND archived_at IS NULL))
);
-- ERD §11: every owner query carries owner_id. This is the listTrips ordering, most recent first.
CREATE INDEX trips_owner_idx ON trips (owner_id, status, start_date DESC);

-- ERD PK (trip_id, interest_code): one weight per code. The OpenAPI array's uniqueItems compares
-- whole objects, so {code:"food",weight:1} and {code:"food",weight:5} both pass there; this rejects
-- them. Which codes are SUPPORTED is a different question and is still open (FCR-020 / PM-006), so
-- no allowlist is encoded here - inventing one would freeze a boundary the BA-030 card protects.
CREATE TABLE trip_interests (
    trip_id uuid NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
    interest_code varchar(100) NOT NULL,
    weight int NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (trip_id, interest_code),
    CONSTRAINT trip_interests_code_check CHECK (btrim(interest_code) <> ''),
    CONSTRAINT trip_interests_weight_check CHECK (weight BETWEEN 1 AND 5)
);

-- ERD §11: unique (trip_id, version); a recorded revision is never modified. The snapshot is
-- canonical JSON with its own schema version and hash so a later reader can tell which shape it is
-- looking at instead of guessing from the contents.
CREATE TABLE trip_revisions (
    id uuid PRIMARY KEY,
    trip_id uuid NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
    version bigint NOT NULL,
    snapshot_schema_version varchar(50) NOT NULL,
    snapshot_hash char(64) NOT NULL,
    aggregate_snapshot jsonb NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT trip_revisions_version_check CHECK (version >= 1),
    CONSTRAINT trip_revisions_hash_check CHECK (snapshot_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT trip_revisions_unique UNIQUE (trip_id, version)
);

-- V002 left this column without its foreign key because trips did not exist yet, and said the
-- application enforced the rule meanwhile. The table exists now, so the database takes it over.
--
-- ON DELETE SET NULL is the "cleared when that trip is deleted" half of ERD §4. The same-owner half
-- cannot be a foreign key - it compares two tables' columns - so it is a trigger.
ALTER TABLE owners
    ADD CONSTRAINT owners_active_trip_fk FOREIGN KEY (active_trip_id) REFERENCES trips(id)
        ON DELETE SET NULL;

CREATE FUNCTION owners_require_own_active_trip()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.active_trip_id IS NOT NULL
        AND NOT EXISTS (SELECT 1 FROM trips WHERE id = NEW.active_trip_id AND owner_id = NEW.id) THEN
        RAISE EXCEPTION 'owners.active_trip_id must reference a trip owned by the same owner';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER owners_active_trip_same_owner
    BEFORE INSERT OR UPDATE OF active_trip_id ON owners
    FOR EACH ROW
    EXECUTE FUNCTION owners_require_own_active_trip();
