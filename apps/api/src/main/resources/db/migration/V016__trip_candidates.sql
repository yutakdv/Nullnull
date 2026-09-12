-- BA-034: places saved to a trip without a date or a time.
--
-- A candidate is NOT a TripItem and NOT a SavedPost (invariant 1). Saving one creates no item and
-- does not raise the trip's schedule version (invariant 2) - there is no trigger or column here
-- that touches trips, and that absence is the point.
CREATE TABLE trip_candidates (
    id uuid PRIMARY KEY,
    trip_id uuid NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
    place_id uuid NOT NULL REFERENCES places(id),
    status varchar(20) NOT NULL,
    scheduled_trip_item_id uuid REFERENCES trip_items(id) ON DELETE SET NULL,
    note varchar(500),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT trip_candidates_status_check CHECK (status IN ('ACTIVE', 'SCHEDULED', 'DISMISSED')),
    CONSTRAINT trip_candidates_timestamps_check CHECK (updated_at >= created_at),
    -- ERD §11: the scheduled item exists exactly while the candidate is SCHEDULED. A DISMISSED row
    -- pointing at an item, or a SCHEDULED row pointing at nothing, are both states no transition
    -- produces and neither should be storable.
    CONSTRAINT trip_candidates_scheduled_shape_check CHECK
        ((status = 'SCHEDULED' AND scheduled_trip_item_id IS NOT NULL)
         OR (status <> 'SCHEDULED' AND scheduled_trip_item_id IS NULL))
);

-- ERD §11: partial unique (trip_id, place_id) WHERE status <> 'DISMISSED'.
--
-- This is what makes "the same POI from a different post converges on ONE active candidate" a
-- storage fact rather than a service convention: two concurrent saves cannot both insert, so the
-- loser reads the winner's row and answers 200 duplicate. DISMISSED rows are excluded so a place
-- the user dismissed can be saved again later - that re-save is a NEW row, and the dismissed one
-- stays as the record that they once said no.
CREATE UNIQUE INDEX trip_candidates_active_place_idx ON trip_candidates (trip_id, place_id)
    WHERE status <> 'DISMISSED';
CREATE INDEX trip_candidates_listing_idx ON trip_candidates (trip_id, status, created_at DESC, id ASC);

-- Where a candidate came from. A candidate can be saved from more than one place over time, so
-- this is a list rather than a column: saving the same POI from a second post adds a source to the
-- existing candidate instead of creating a second one.
CREATE TABLE candidate_sources (
    id uuid PRIMARY KEY,
    candidate_id uuid NOT NULL REFERENCES trip_candidates(id) ON DELETE CASCADE,
    source_type varchar(20) NOT NULL,
    post_id uuid REFERENCES posts(id) ON DELETE SET NULL,
    context jsonb,
    created_at timestamptz NOT NULL,
    CONSTRAINT candidate_sources_type_check CHECK (source_type IN ('POST', 'SEARCH', 'LIVE', 'IMPORT')),
    -- A POST source names its post; the others carry none. post_id survives the post being deleted
    -- as NULL rather than taking the candidate with it: the user's saved place is theirs, not the
    -- curator's.
    CONSTRAINT candidate_sources_post_shape_check CHECK
        ((source_type = 'POST') OR post_id IS NULL),
    CONSTRAINT candidate_sources_context_check CHECK
        (context IS NULL OR jsonb_typeof(context) = 'object'),
    -- One row per (candidate, type, post): saving from the same post twice records one source.
    CONSTRAINT candidate_sources_unique UNIQUE (candidate_id, source_type, post_id)
);
CREATE INDEX candidate_sources_candidate_idx ON candidate_sources (candidate_id, created_at);
