-- BA-023 C4: immutable, normalized crowd forecast snapshots. Provider request URLs, credentials and
-- raw response bodies deliberately have no column in this model. A forecast set is one provider batch;
-- individual rows are never updated in place, so a saved preview can keep its original provenance.

-- The official operation and schema version are now known, so this revision supersedes the C1
-- placeholder without changing snapshots that may already refer to revision 1.
UPDATE source_registry
   SET official_url = 'https://www.data.go.kr/data/15128555/openapi.do',
       metric_definition = '가장 붐비는 시기를 100으로 둔 날짜 단위 상대 집중률 예측; 인원·수용률·시간대 예측 아님',
       provider_schema_version = 'kto-tats-cnctr-rate-v4.1',
       current_revision = 2,
       contest_use = '{"required":true,"evidence":"EV-KTO-03","operations":["tatsCnctrRatedList"],"provenance":"collector-run"}'::jsonb,
       refresh_expectation = 'tatsCnctrRatedList read-through; maximum PT24H cache age',
       reviewed_at = '2026-09-10T00:00:00Z',
       updated_at = '2026-09-10T00:00:00Z'
 WHERE code = 'KTO_CONCENTRATION_FORECAST';

INSERT INTO source_registry_revisions
    (source_code, version, canonical_contract, contract_hash, reviewed_at, created_at)
WITH contract AS (
    SELECT code,
           jsonb_build_object(
               'approvalState', approval_state,
               'attributionTemplate', attribution_template,
               'code', code,
               'contestUse', contest_use,
               'defaultScope', default_scope,
               'displayName', display_name,
               'enabled', enabled,
               'license', jsonb_build_object(
                   'name', license_name,
                   'reviewState', license_review_state,
                   'url', license_url),
               'metricDefinition', metric_definition,
               'officialUrl', official_url,
               'providerSchemaVersion', provider_schema_version,
               'quotaPolicy', quota_policy,
               'refreshExpectation', refresh_expectation,
               'retentionPolicy', retention_policy,
               'sourceState', source_state,
               'staleAfterSeconds', stale_after_seconds,
               'termsUrl', terms_url) AS canonical_contract,
           reviewed_at
      FROM source_registry
     WHERE code = 'KTO_CONCENTRATION_FORECAST'
)
SELECT code, 2, canonical_contract,
       encode(sha256(convert_to(canonical_contract::text, 'UTF8')), 'hex'),
       reviewed_at, reviewed_at
  FROM contract;

CREATE TABLE snapshot_sets (
    id uuid PRIMARY KEY,
    source_code varchar(64) NOT NULL REFERENCES source_registry(code),
    source_registry_version bigint NOT NULL,
    collector_run_id uuid NOT NULL REFERENCES collector_runs(id),
    source_state varchar(20) NOT NULL,
    forecast_issue_id varchar(200),
    comparison_group_id varchar(200),
    observed_at timestamptz,
    fetched_at timestamptz NOT NULL,
    stale_at timestamptz,
    normalization_version varchar(100) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT snapshot_sets_source_version_check CHECK (source_registry_version > 0),
    CONSTRAINT snapshot_sets_source_revision_fk
        FOREIGN KEY (source_code, source_registry_version)
        REFERENCES source_registry_revisions(source_code, version),
    CONSTRAINT snapshot_sets_state_check CHECK
        (source_state IN ('LIVE', 'FORECAST', 'REPLAY', 'QUALITATIVE', 'STALE', 'UNAVAILABLE')),
    CONSTRAINT snapshot_sets_issue_check CHECK
        ((source_state = 'FORECAST' AND forecast_issue_id IS NOT NULL AND btrim(forecast_issue_id) <> ''
          AND comparison_group_id IS NOT NULL AND btrim(comparison_group_id) <> '')
         OR source_state <> 'FORECAST'),
    CONSTRAINT snapshot_sets_staleness_check CHECK (stale_at IS NULL OR stale_at > fetched_at),
    CONSTRAINT snapshot_sets_normalization_check CHECK (btrim(normalization_version) <> '')
);
CREATE INDEX snapshot_sets_source_fetched_idx
    ON snapshot_sets(source_code, fetched_at DESC, id DESC);

CREATE TABLE crowd_snapshots (
    id uuid PRIMARY KEY,
    snapshot_set_id uuid NOT NULL REFERENCES snapshot_sets(id),
    source_code varchar(64) NOT NULL REFERENCES source_registry(code),
    source_registry_version bigint NOT NULL,
    place_id uuid REFERENCES places(id),
    live_area_id uuid,
    source_state varchar(20) NOT NULL,
    observed_at timestamptz,
    target_at timestamptz,
    fetched_at timestamptz NOT NULL,
    stale_at timestamptz,
    metric_code varchar(100) NOT NULL,
    value numeric(14, 4),
    unit varchar(100),
    ordinal_level varchar(100),
    confidence numeric(5, 4),
    quality_flags jsonb NOT NULL DEFAULT '[]'::jsonb,
    forecast_issue_id varchar(200),
    comparison_group_id varchar(200),
    normalization_version varchar(100) NOT NULL,
    observed_at_skew_seconds integer,
    scope varchar(40) NOT NULL,
    scope_label varchar(300) NOT NULL,
    mapping_type varchar(40) NOT NULL,
    fallback_used boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL,
    CONSTRAINT crowd_snapshots_source_version_check CHECK (source_registry_version > 0),
    CONSTRAINT crowd_snapshots_source_revision_fk
        FOREIGN KEY (source_code, source_registry_version)
        REFERENCES source_registry_revisions(source_code, version),
    CONSTRAINT crowd_snapshots_subject_xor_check CHECK
        ((place_id IS NOT NULL AND live_area_id IS NULL) OR (place_id IS NULL AND live_area_id IS NOT NULL)),
    CONSTRAINT crowd_snapshots_state_check CHECK
        (source_state IN ('LIVE', 'FORECAST', 'REPLAY', 'QUALITATIVE', 'STALE', 'UNAVAILABLE')),
    CONSTRAINT crowd_snapshots_live_observed_check CHECK
        (source_state <> 'LIVE' OR observed_at IS NOT NULL),
    CONSTRAINT crowd_snapshots_forecast_check CHECK
        ((source_state = 'FORECAST' AND target_at IS NOT NULL AND forecast_issue_id IS NOT NULL
          AND btrim(forecast_issue_id) <> '' AND comparison_group_id IS NOT NULL
          AND btrim(comparison_group_id) <> '')
         OR source_state <> 'FORECAST'),
    CONSTRAINT crowd_snapshots_staleness_check CHECK (stale_at IS NULL OR stale_at > fetched_at),
    CONSTRAINT crowd_snapshots_metric_check CHECK (btrim(metric_code) <> ''),
    CONSTRAINT crowd_snapshots_confidence_check CHECK (confidence IS NULL OR confidence BETWEEN 0 AND 1),
    CONSTRAINT crowd_snapshots_quality_flags_check CHECK
        (jsonb_typeof(quality_flags) = 'array'
         AND quality_flags <@ '["PROVIDER_INCIDENT","SCHEMA_DRIFT","MAPPING_UNCERTAIN","OBSERVED_AT_SKEW","PARTIAL_PAYLOAD"]'::jsonb),
    CONSTRAINT crowd_snapshots_skew_check CHECK
        (observed_at_skew_seconds IS NULL OR observed_at_skew_seconds >= 0),
    CONSTRAINT crowd_snapshots_scope_check CHECK
        ((scope = 'PLACE' AND place_id IS NOT NULL AND live_area_id IS NULL)
         OR (scope = 'LIVE_AREA' AND live_area_id IS NOT NULL AND place_id IS NULL)),
    CONSTRAINT crowd_snapshots_scope_label_check CHECK (btrim(scope_label) <> ''),
    CONSTRAINT crowd_snapshots_mapping_check CHECK (btrim(mapping_type) <> ''),
    CONSTRAINT crowd_snapshots_normalization_check CHECK (btrim(normalization_version) <> '')
);
CREATE INDEX crowd_snapshots_place_target_source_idx
    ON crowd_snapshots(place_id, target_at DESC, source_code)
    WHERE place_id IS NOT NULL;
CREATE INDEX crowd_snapshots_set_target_idx
    ON crowd_snapshots(snapshot_set_id, target_at ASC, id ASC);

-- The snapshot row repeats the set's source/time identity for an efficient immutable read model. A
-- trigger makes that denormalization checked rather than advisory.
CREATE FUNCTION crowd_require_snapshot_set_contract()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    parent snapshot_sets%ROWTYPE;
BEGIN
    SELECT * INTO parent FROM snapshot_sets WHERE id = NEW.snapshot_set_id FOR SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'crowd snapshot requires an existing snapshot set';
    END IF;
    IF NEW.source_code <> parent.source_code
       OR NEW.source_registry_version <> parent.source_registry_version
       OR NEW.source_state <> parent.source_state
       OR NEW.fetched_at <> parent.fetched_at
       OR NEW.stale_at IS DISTINCT FROM parent.stale_at
       OR NEW.observed_at IS DISTINCT FROM parent.observed_at
       OR NEW.forecast_issue_id IS DISTINCT FROM parent.forecast_issue_id
       OR NEW.comparison_group_id IS DISTINCT FROM parent.comparison_group_id
       OR NEW.normalization_version <> parent.normalization_version THEN
        RAISE EXCEPTION 'crowd snapshot must retain its snapshot set provenance';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION crowd_prevent_snapshot_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'normalized crowd snapshots are immutable';
END;
$$;

CREATE TRIGGER crowd_snapshots_snapshot_set_contract_guard
BEFORE INSERT OR UPDATE ON crowd_snapshots
FOR EACH ROW EXECUTE FUNCTION crowd_require_snapshot_set_contract();

CREATE TRIGGER crowd_snapshots_immutable_guard
BEFORE UPDATE ON crowd_snapshots
FOR EACH ROW EXECUTE FUNCTION crowd_prevent_snapshot_mutation();

CREATE TRIGGER snapshot_sets_immutable_guard
BEFORE UPDATE ON snapshot_sets
FOR EACH ROW EXECUTE FUNCTION crowd_prevent_snapshot_mutation();
