-- BA-090: the Seoul live area, the AREA->PLACE mapping, and the foreign key crowd_snapshots has been
-- waiting for since V011.
--
-- V011 created crowd_snapshots.live_area_id as a bare uuid with no REFERENCES, because the table it
-- would have pointed at did not exist and a junction to nothing is a shape with nothing behind it
-- (V024 says the same thing about route snapshots). This migration creates that table, so the
-- reference stops being a promise and becomes a constraint.
--
-- WHAT THIS DOES NOT CREATE: source_quality_incidents. It already exists (V007) and the ERD rule at
-- docs/architecture/ERD.md "Snapshot/Source" is a PROJECTION rule, not a storage one - a row whose
-- observation falls inside an incident window gets PROVIDER_INCIDENT at read time. Nothing is stored
-- per snapshot, which is the same reason comparison eligibility has no column.

CREATE TABLE live_areas (
    id uuid PRIMARY KEY,
    source_code varchar(64) NOT NULL REFERENCES source_registry(code),
    external_id varchar(200) NOT NULL,
    name varchar(200) NOT NULL,
    -- GeoJSON, not a geometry: this database has no PostGIS (no CREATE EXTENSION anywhere in
    -- db/migration), and the contract's LiveArea.boundaryGeoJson is `[object, "null"]`. Nullable
    -- because the provider publishes area names without polygons and a made-up boundary would be a
    -- fabricated observation of where an area ends.
    boundary_geojson jsonb,
    status varchar(20) NOT NULL,
    updated_at timestamptz NOT NULL,
    -- An area is identified by what the provider calls it, scoped to the provider. Two sources may
    -- legitimately use the same external id for different places.
    UNIQUE (source_code, external_id),
    -- RETIRED exists because the provider's own list moves and snapshots outlive it. The 2026-03
    -- notice (SOURCE_CATALOG.md section 5) added, removed and renamed areas; deleting a removed area
    -- would orphan every snapshot that measured it, and the honest record is that the area existed
    -- and no longer reports. THE COUNT IS NOT AN INVARIANT ANYWHERE: that notice said 122, the
    -- dataset detail page says 121 and the data.go.kr listing says 120. Three official numbers, so a
    -- CHECK or a seeded row count would pin the wrong one.
    CONSTRAINT live_areas_status_check CHECK (status IN ('ACTIVE', 'RETIRED')),
    CONSTRAINT live_areas_external_id_check CHECK (btrim(external_id) <> ''),
    CONSTRAINT live_areas_name_check CHECK (btrim(name) <> '')
);

CREATE INDEX live_areas_source_status_idx ON live_areas (source_code, status);

-- Now the column V011 declared can say what it means.
ALTER TABLE crowd_snapshots
    ADD CONSTRAINT crowd_snapshots_live_area_fk
    FOREIGN KEY (live_area_id) REFERENCES live_areas(id);

CREATE TABLE seoul_live_area_maps (
    id uuid PRIMARY KEY,
    -- No ON DELETE CASCADE, deliberately, and for the same reason places has none anywhere else: a
    -- place must not disappear from under the records that reference it.
    place_id uuid NOT NULL REFERENCES places(id),
    live_area_id uuid NOT NULL REFERENCES live_areas(id),
    mapping_type varchar(40) NOT NULL,
    -- numeric(5, 4) is NOT chosen here. It is crowd_snapshots.confidence (V011:97), which is where
    -- this value is copied to when a snapshot is attached to a place, and the contract bounds
    -- DataProvenance.confidence to [0, 1]. Picking a narrower precision here would silently truncate
    -- at the copy.
    confidence numeric(5, 4) NOT NULL,
    fallback_used boolean NOT NULL,
    verified_at timestamptz NOT NULL,
    UNIQUE (place_id, live_area_id),
    -- THE ABSENCE OF A ROW IS THE THIRD STATE. The contract's LivePlace.mappingType carries NONE for
    -- a place no area covers, and that is expressed by having no row here rather than by a row that
    -- says "no mapping" - otherwise "is this place covered" has two answers that can disagree.
    -- DIRECT and AREA_FALLBACK are the only two a row can hold.
    --
    -- AREA_FALLBACK IS NEW VOCABULARY. Before this migration the only value that appears anywhere in
    -- the repository is DIRECT (21 occurrences, all in examples and fixtures; no server enum, no ERD
    -- constraint). The contract types mappingType as a bare string, so this CHECK is currently the
    -- only place the vocabulary exists. It needs an x-extensible-enum on the contract side and a test
    -- pinning the two lists against each other, the way BA-023-T21~T23 pins unavailableReason.
    CONSTRAINT seoul_live_area_maps_type_check CHECK (mapping_type IN ('DIRECT', 'AREA_FALLBACK')),
    CONSTRAINT seoul_live_area_maps_confidence_check CHECK (confidence BETWEEN 0 AND 1),
    -- The two columns are not independent and nothing else says so. A row claiming DIRECT while
    -- reporting fallback_used would put two different answers into one DataProvenance, and the
    -- projection reads them from different places.
    CONSTRAINT seoul_live_area_maps_fallback_check CHECK ((mapping_type = 'DIRECT') = (fallback_used IS FALSE))
);

CREATE INDEX seoul_live_area_maps_place_idx ON seoul_live_area_maps (place_id);
CREATE INDEX seoul_live_area_maps_area_idx ON seoul_live_area_maps (live_area_id);
