-- BA-092: an approved replay is an immutable ordered selection of existing normalized snapshots.
-- No capture producer or scrub vocabulary exists yet, so this schema records neither raw payloads
-- nor a guessed scrub_method. A reader must check the ordered-entry checksum before using it.

CREATE TABLE replay_manifests (
    id uuid PRIMARY KEY,
    name varchar(200) NOT NULL UNIQUE,
    schema_version varchar(100) NOT NULL,
    source_code varchar(64) NOT NULL,
    source_registry_version bigint NOT NULL,
    checksum varchar(64) NOT NULL,
    source_license_snapshot text NOT NULL,
    captured_from timestamptz NOT NULL,
    captured_to timestamptz NOT NULL,
    approved_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT replay_manifests_source_revision_fk
        FOREIGN KEY (source_code, source_registry_version)
        REFERENCES source_registry_revisions(source_code, version),
    CONSTRAINT replay_manifests_name_check CHECK (btrim(name) <> ''),
    CONSTRAINT replay_manifests_schema_check CHECK (btrim(schema_version) <> ''),
    CONSTRAINT replay_manifests_source_version_check CHECK (source_registry_version > 0),
    CONSTRAINT replay_manifests_license_check CHECK (btrim(source_license_snapshot) <> ''),
    CONSTRAINT replay_manifests_window_check CHECK
        (captured_from <= captured_to AND approved_at >= captured_to AND created_at <= approved_at),
    CONSTRAINT replay_manifests_checksum_check CHECK (checksum ~ '^[0-9a-f]{64}$')
);

CREATE TABLE replay_manifest_entries (
    manifest_id uuid NOT NULL REFERENCES replay_manifests(id),
    crowd_snapshot_id uuid NOT NULL REFERENCES crowd_snapshots(id),
    sequence integer NOT NULL,
    PRIMARY KEY (manifest_id, crowd_snapshot_id),
    CONSTRAINT replay_manifest_entries_sequence_unique UNIQUE (manifest_id, sequence),
    CONSTRAINT replay_manifest_entries_sequence_check CHECK (sequence >= 0)
);

CREATE INDEX replay_manifests_source_approved_idx
    ON replay_manifests (source_code, approved_at DESC, id DESC);

-- A manifest is single-source and its capture window is about the observation, not the request
-- time. This also prevents nesting a replay inside a replay, or laundering a forecast into Live.
CREATE FUNCTION replay_require_entry_contract()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    approved replay_manifests%ROWTYPE;
    point crowd_snapshots%ROWTYPE;
BEGIN
    SELECT * INTO approved FROM replay_manifests WHERE id = NEW.manifest_id FOR SHARE;
    SELECT * INTO point FROM crowd_snapshots WHERE id = NEW.crowd_snapshot_id FOR SHARE;
    IF NOT FOUND OR approved.id IS NULL THEN
        RAISE EXCEPTION 'replay entry requires a manifest and snapshot';
    END IF;
    IF point.source_code <> approved.source_code
       OR point.source_registry_version <> approved.source_registry_version
       OR point.scope <> 'LIVE_AREA'
       OR point.source_state NOT IN ('LIVE', 'STALE')
       OR point.observed_at IS NULL
       OR point.observed_at < approved.captured_from
       OR point.observed_at > approved.captured_to THEN
        RAISE EXCEPTION 'replay entry is outside its approved source or capture window';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER replay_manifest_entries_contract_guard
BEFORE INSERT ON replay_manifest_entries
FOR EACH ROW EXECUTE FUNCTION replay_require_entry_contract();

CREATE TRIGGER replay_manifests_immutable_guard
BEFORE UPDATE OR DELETE ON replay_manifests
FOR EACH ROW EXECUTE FUNCTION crowd_prevent_snapshot_mutation();

CREATE TRIGGER replay_manifest_entries_immutable_guard
BEFORE UPDATE OR DELETE ON replay_manifest_entries
FOR EACH ROW EXECUTE FUNCTION crowd_prevent_snapshot_mutation();
