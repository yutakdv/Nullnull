-- BA-021 C2: normalized, immutable KTO detailCommon2 evidence only.
-- This table has no credential, request URL/query, raw provider body, overview or image field.
CREATE TABLE kto_place_snapshots (
    id uuid PRIMARY KEY,
    source_code varchar(64) NOT NULL REFERENCES source_registry(code),
    source_registry_version bigint NOT NULL,
    collector_run_id uuid NOT NULL REFERENCES collector_runs(id),
    content_id varchar(30) NOT NULL,
    content_type_id varchar(30) NOT NULL,
    title varchar(300) NOT NULL,
    category_code varchar(30),
    area_code varchar(20),
    sigungu_code varchar(20),
    address varchar(500),
    latitude numeric(9, 6),
    longitude numeric(9, 6),
    payload_hash varchar(64) NOT NULL,
    fetched_at timestamptz NOT NULL,
    stale_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT kto_place_snapshots_source_check CHECK (source_code = 'KTO_KOR_SERVICE_2'),
    CONSTRAINT kto_place_snapshots_version_check CHECK (source_registry_version > 0),
    CONSTRAINT kto_place_snapshots_content_id_check CHECK (content_id ~ '^[1-9][0-9]{0,29}$'),
    CONSTRAINT kto_place_snapshots_content_type_id_check CHECK (content_type_id ~ '^[1-9][0-9]{0,29}$'),
    CONSTRAINT kto_place_snapshots_title_check CHECK (btrim(title) <> ''),
    CONSTRAINT kto_place_snapshots_category_check CHECK
        (category_code IS NULL OR category_code ~ '^[A-Za-z0-9_:-]{1,30}$'),
    CONSTRAINT kto_place_snapshots_area_check CHECK
        ((area_code IS NULL OR area_code ~ '^[A-Za-z0-9_:-]{1,20}$')
         AND (sigungu_code IS NULL OR sigungu_code ~ '^[A-Za-z0-9_:-]{1,20}$')),
    CONSTRAINT kto_place_snapshots_address_check CHECK (address IS NULL OR btrim(address) <> ''),
    CONSTRAINT kto_place_snapshots_coordinates_check CHECK
        ((latitude IS NULL AND longitude IS NULL)
         OR (latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180)),
    CONSTRAINT kto_place_snapshots_hash_check CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT kto_place_snapshots_staleness_check CHECK (stale_at > fetched_at),
    CONSTRAINT kto_place_snapshots_collector_run_unique UNIQUE (collector_run_id),
    CONSTRAINT kto_place_snapshots_revision_fk
        FOREIGN KEY (source_code, source_registry_version)
        REFERENCES source_registry_revisions(source_code, version)
);
CREATE INDEX kto_place_snapshots_fresh_idx
    ON kto_place_snapshots(source_code, content_id, content_type_id, stale_at DESC, fetched_at DESC);

-- Step 0 of C2: only this reviewed provider operation becomes callable. Forecast and related-place
-- sources retain their pending/disabled revisions and cannot be invoked by the adapter above.
UPDATE source_registry
   SET provider_schema_version = 'kto-kor-service2-detailcommon2-v1',
       current_revision = 2,
       contest_use = '{"required":true,"evidence":"EV-KTO-02","operations":["detailCommon2"],"provenance":"collector-run"}'::jsonb,
       refresh_expectation = 'detailCommon2 read-through; maximum P7D cache age',
       reviewed_at = '2026-09-10T00:00:00Z',
       updated_at = '2026-09-10T00:00:00Z'
 WHERE code = 'KTO_KOR_SERVICE_2';

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
     WHERE code = 'KTO_KOR_SERVICE_2'
)
SELECT code, 2, canonical_contract,
       encode(sha256(convert_to(canonical_contract::text, 'UTF8')), 'hex'),
       reviewed_at, reviewed_at
  FROM contract;
