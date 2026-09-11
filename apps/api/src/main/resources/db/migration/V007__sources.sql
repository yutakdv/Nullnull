-- BA-020 source registry, immutable contract revisions, quality incidents and safe ingest audit.
-- Provider credentials, full URLs, query strings, bodies and user input have no column to land in.
CREATE TABLE source_registry (
    code varchar(64) PRIMARY KEY,
    display_name varchar(160) NOT NULL,
    source_state varchar(20) NOT NULL,
    license_name varchar(300),
    license_url varchar(500),
    license_review_state varchar(40) NOT NULL,
    official_url varchar(500),
    terms_url varchar(500),
    default_scope varchar(40) NOT NULL,
    metric_definition varchar(500),
    approval_state varchar(20) NOT NULL,
    quota_policy jsonb NOT NULL,
    attribution_template varchar(300),
    retention_policy varchar(300) NOT NULL,
    refresh_expectation varchar(300),
    provider_schema_version varchar(80) NOT NULL,
    current_revision bigint NOT NULL,
    stale_after_seconds integer,
    enabled boolean NOT NULL,
    contest_use jsonb NOT NULL,
    reviewed_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT source_registry_code_check CHECK (code ~ '^[A-Z][A-Z0-9_]{2,63}$'),
    CONSTRAINT source_registry_state_check CHECK
        (source_state IN ('LIVE', 'FORECAST', 'REPLAY', 'QUALITATIVE', 'STALE', 'UNAVAILABLE')),
    CONSTRAINT source_registry_approval_check CHECK
        (approval_state IN ('DEV_APPROVED', 'PROD_PENDING', 'PROD_APPROVED', 'DISABLED')),
    CONSTRAINT source_registry_license_review_check CHECK
        (license_review_state IN ('APPROVED', 'RECORD_LEVEL_REVIEW_REQUIRED', 'NOT_APPLICABLE')),
    CONSTRAINT source_registry_revision_check CHECK (current_revision > 0),
    CONSTRAINT source_registry_stale_check CHECK (stale_after_seconds IS NULL OR stale_after_seconds > 0),
    CONSTRAINT source_registry_enabled_check CHECK
        (enabled = (approval_state IN ('DEV_APPROVED', 'PROD_APPROVED')
                    AND stale_after_seconds IS NOT NULL)),
    CONSTRAINT source_registry_quota_check CHECK
        (jsonb_typeof(quota_policy) = 'object'
         AND (quota_policy ? 'perDay')
         AND (quota_policy->>'perDay')::integer > 0
         AND quota_policy->'thresholds' = '[60, 80, 90]'::jsonb)
);

CREATE TABLE source_registry_revisions (
    source_code varchar(64) NOT NULL REFERENCES source_registry(code),
    version bigint NOT NULL,
    canonical_contract jsonb NOT NULL,
    contract_hash varchar(64) NOT NULL,
    reviewed_by_owner_id uuid REFERENCES owners(id),
    reviewed_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (source_code, version),
    CONSTRAINT source_registry_revision_version_check CHECK (version > 0),
    CONSTRAINT source_registry_contract_check CHECK (jsonb_typeof(canonical_contract) = 'object'),
    CONSTRAINT source_registry_contract_hash_check CHECK (contract_hash ~ '^[0-9a-f]{64}$')
);

ALTER TABLE source_registry
    ADD CONSTRAINT source_registry_current_revision_fk
    FOREIGN KEY (code, current_revision) REFERENCES source_registry_revisions(source_code, version)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE source_quality_incidents (
    id uuid PRIMARY KEY,
    source_code varchar(64) NOT NULL REFERENCES source_registry(code),
    incident_code varchar(100) NOT NULL,
    affected_from timestamptz NOT NULL,
    affected_to timestamptz,
    scope varchar(120) NOT NULL,
    official_notice_url varchar(500),
    disposition varchar(40) NOT NULL,
    reviewed_at timestamptz NOT NULL,
    UNIQUE (source_code, incident_code),
    CONSTRAINT source_incident_window_check CHECK
        (affected_to IS NULL OR affected_to > affected_from),
    CONSTRAINT source_incident_disposition_check CHECK
        (disposition IN ('QUARANTINE', 'RESOLVED', 'IGNORED'))
);
CREATE INDEX source_quality_incidents_window_idx
    ON source_quality_incidents(source_code, affected_from, affected_to);

CREATE TABLE collector_runs (
    id uuid PRIMARY KEY,
    source_code varchar(64) NOT NULL REFERENCES source_registry(code),
    status varchar(20) NOT NULL,
    trigger_type varchar(20) NOT NULL,
    records_received integer NOT NULL DEFAULT 0,
    records_accepted integer NOT NULL DEFAULT 0,
    records_rejected integer NOT NULL DEFAULT 0,
    schema_version varchar(80) NOT NULL,
    error_code varchar(100),
    started_at timestamptz NOT NULL,
    finished_at timestamptz,
    CONSTRAINT collector_run_status_check CHECK
        (status IN ('STARTED', 'COMPLETED', 'FAILED', 'QUARANTINED')),
    CONSTRAINT collector_trigger_check CHECK
        (trigger_type IN ('SCHEDULED', 'READ_THROUGH', 'MANUAL', 'REPLAY')),
    CONSTRAINT collector_counts_check CHECK
        (records_received >= 0 AND records_accepted >= 0 AND records_rejected >= 0
         AND records_accepted + records_rejected <= records_received),
    CONSTRAINT collector_finished_check CHECK
        ((status = 'STARTED' AND finished_at IS NULL)
         OR (status <> 'STARTED' AND finished_at IS NOT NULL AND finished_at >= started_at))
);
CREATE INDEX collector_runs_source_started_idx ON collector_runs(source_code, started_at DESC);

CREATE TABLE api_ingest_logs (
    id uuid PRIMARY KEY,
    collector_run_id uuid NOT NULL REFERENCES collector_runs(id),
    endpoint_key varchar(100) NOT NULL,
    outcome varchar(30) NOT NULL,
    http_status integer,
    duration_ms integer,
    response_count integer,
    release_version varchar(100) NOT NULL,
    request_id varchar(64) NOT NULL,
    payload_hash varchar(64),
    validation_result varchar(30) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT api_ingest_endpoint_check CHECK (endpoint_key ~ '^[A-Z0-9_:-]{2,100}$'),
    CONSTRAINT api_ingest_outcome_check CHECK
        (outcome IN ('STARTED', 'OK', 'HTTP_ERROR', 'TIMEOUT', 'IO_ERROR',
                     'QUOTA_EXHAUSTED', 'CIRCUIT_OPEN', 'VALIDATION_FAILED')),
    CONSTRAINT api_ingest_http_status_check CHECK
        (http_status IS NULL OR http_status BETWEEN 100 AND 599),
    CONSTRAINT api_ingest_duration_check CHECK (duration_ms IS NULL OR duration_ms >= 0),
    CONSTRAINT api_ingest_response_count_check CHECK (response_count IS NULL OR response_count >= 0),
    CONSTRAINT api_ingest_payload_hash_check CHECK
        (payload_hash IS NULL OR payload_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT api_ingest_validation_check CHECK
        (validation_result IN ('PENDING', 'OK', 'SCHEMA_DRIFT', 'ENUM_DRIFT',
                               'RANGE', 'TIME_SKEW', 'PROVIDER_ERROR'))
);
CREATE INDEX api_ingest_logs_run_created_idx ON api_ingest_logs(collector_run_id, created_at);

-- The fixed timestamp records the team decision, rather than pretending deploy time was review time.
INSERT INTO source_registry
    (code, display_name, source_state, license_name, license_url, license_review_state,
     official_url, terms_url, default_scope, metric_definition, approval_state, quota_policy,
     attribution_template, retention_policy, refresh_expectation, provider_schema_version,
     current_revision, stale_after_seconds, enabled, contest_use, reviewed_at, updated_at)
VALUES
    ('KTO_KOR_SERVICE_2', '한국관광공사 국문 관광정보', 'QUALITATIVE',
     '이용허락범위 제한 없음 (관광정보 텍스트; 이미지 별도 심사)',
     'https://www.data.go.kr/ugs/selectPortalPolicyView.do', 'RECORD_LEVEL_REVIEW_REQUIRED',
     'https://www.data.go.kr/data/15101578/openapi.do',
     'https://www.data.go.kr/ugs/selectPortalPolicyView.do', 'PLACE',
     '한국관광공사 관광지 텍스트와 분류 정보', 'DEV_APPROVED',
     '{"perDay":1000,"thresholds":[60,80,90]}', '출처: ⓒ한국관광공사',
     '정규화 텍스트만 보존; 이미지 별도 심사', '상세 갱신 주기는 실측 전이며 최대 P7D',
     'pending-c2', 1, 604800, true, '{"required":true,"evidence":"EV-KTO-02"}',
     '2026-09-07T00:00:00Z', '2026-09-07T00:00:00Z'),
    ('KTO_CONCENTRATION_FORECAST', '한국관광공사 관광지 집중률 예측', 'FORECAST',
     '이용허락범위 제한 없음', 'https://www.data.go.kr/ugs/selectPortalPolicyView.do', 'APPROVED',
     'https://www.data.go.kr/', 'https://www.data.go.kr/ugs/selectPortalPolicyView.do', 'PLACE',
     'KTO_RELATIVE_CONCENTRATION_INDEX', 'DEV_APPROVED',
     '{"perDay":1000,"thresholds":[60,80,90]}', '출처: ⓒ한국관광공사',
     '정규화 예측 snapshot 30일', '예측은 최대 PT24H 뒤 stale',
     'pending-c2', 1, 86400, true, '{"required":true,"evidence":"EV-KTO-02"}',
     '2026-09-07T00:00:00Z', '2026-09-07T00:00:00Z'),
    ('KTO_RELATED_PLACES', '한국관광공사 연관 관광지', 'QUALITATIVE',
     '이용허락범위 제한 없음', 'https://www.data.go.kr/ugs/selectPortalPolicyView.do', 'APPROVED',
     'https://www.data.go.kr/', 'https://www.data.go.kr/ugs/selectPortalPolicyView.do', 'PLACE',
     '연관 관광지 관계', 'DISABLED', '{"perDay":1000,"thresholds":[60,80,90]}',
     '출처: ⓒ한국관광공사', '수집 전', '미신청 source', 'disabled', 1, NULL, false,
     '{"required":false}', '2026-09-07T00:00:00Z', '2026-09-07T00:00:00Z'),
    ('SEOUL_CITYDATA', '서울 실시간 도시데이터', 'LIVE', NULL, NULL,
     'RECORD_LEVEL_REVIEW_REQUIRED', NULL, NULL, 'LIVE_AREA', '실시간 혼잡도', 'DISABLED',
     '{"perDay":1000,"thresholds":[60,80,90]}', NULL, 'B10 전 수집 금지', 'B10 범위',
     'disabled', 1, NULL, false, '{"required":false}',
     '2026-09-07T00:00:00Z', '2026-09-07T00:00:00Z'),
    ('DEMO_REPLAY', '널널 승인 replay', 'REPLAY', NULL, NULL, 'NOT_APPLICABLE', NULL, NULL,
     'PLACE', '승인된 manifest의 재생 값', 'DISABLED',
     '{"perDay":100000,"thresholds":[60,80,90]}', '재생 데이터', 'B10 전 사용 금지',
     'B10 범위', 'disabled', 1, NULL, false, '{"required":false}',
     '2026-09-07T00:00:00Z', '2026-09-07T00:00:00Z'),
    ('NULLNULL_CATALOG_RULE', '널널 카탈로그 규칙', 'QUALITATIVE', NULL, NULL,
     'NOT_APPLICABLE', NULL, NULL, 'PLACE', '동일 taxonomy·region 기반 SIMILAR 규칙',
     'PROD_APPROVED', '{"perDay":100000,"thresholds":[60,80,90]}', '널널 내부 규칙',
     '규칙 결과는 원본 place 보존기간을 따름', '카탈로그 변경 시 재평가', 'rule-v1', 1,
     604800, true, '{"required":false}', '2026-09-07T00:00:00Z', '2026-09-07T00:00:00Z');

INSERT INTO source_registry_revisions
    (source_code, version, canonical_contract, contract_hash, reviewed_at, created_at)
WITH contracts AS (
    SELECT code, reviewed_at,
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
               'termsUrl', terms_url) AS canonical_contract
      FROM source_registry
)
SELECT code, 1, canonical_contract,
       encode(sha256(convert_to(canonical_contract::text, 'UTF8')), 'hex'),
       reviewed_at, reviewed_at
FROM contracts;
