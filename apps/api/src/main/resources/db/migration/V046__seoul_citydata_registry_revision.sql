-- BA-090: the Seoul source becomes usable, and every value below has a document behind it.
--
-- V007 left this row as a placeholder ('B10 전 수집 금지', no licence, no stale threshold) because
-- nothing called the provider. An adapter exists now, so the row has to say what the provider is -
-- and a registry row is what every snapshot references for its licence, attribution and freshness
-- meaning, so a guessed value here would be reproduced in every reading forever.
--
-- WHERE EACH VALUE COMES FROM - docs/data/SOURCE_CATALOG.md section 5 unless noted:
--   licence, licence url, official url, attribution   the FCR-011 block, which fixes them verbatim
--   stale_after_seconds = 300                         "최소 약 5분 단위 갱신" in the same section
--   provider_schema_version = seoul-citydata-v8.5     the manual edition actually read (v8.5, 2026-04)
--   retention                                         공공누리 제1유형 permits storage with attribution
--
-- NOT CHANGED, DELIBERATELY: license_review_state stays RECORD_LEVEL_REVIEW_REQUIRED. The section
-- fixes the licence NAME and URL; it does not review per-record rights, and upgrading that flag here
-- would be a licence judgement no document in this repository makes.
--
-- approval_state is DEV_APPROVED, not PROD_APPROVED: the key in use is a development registration and
-- the contest profile is the only place this runs. enabled is NOT set by hand - V007's CHECK derives
-- it from (approval_state, stale_after_seconds), so setting those two is what turns it on.

UPDATE source_registry SET
    license_name = '공공누리 제1유형',
    license_url = 'https://www.kogl.or.kr/info/licenseType1.do',
    official_url = 'https://data.seoul.go.kr/dataList/OA-21285/F/1/datasetView.do',
    attribution_template = '출처: 서울특별시 「서울시 실시간 도시데이터」(2022년 공개, 공공누리 제1유형)',
    metric_definition = '서울 주요 장소의 실시간 인구 혼잡도 수준',
    approval_state = 'DEV_APPROVED',
    provider_schema_version = 'seoul-citydata-v8.5',
    retention_policy = '원본·정규화 snapshot 보존 허용 (공공누리 제1유형, 출처 표시 조건)',
    refresh_expectation = '공식 설명은 데이터별 주기가 다르며 최소 약 5분 단위 갱신',
    stale_after_seconds = 300,
    enabled = true,
    current_revision = 2,
    reviewed_at = TIMESTAMPTZ '2026-09-20T00:00:00Z',
    updated_at = TIMESTAMPTZ '2026-09-20T00:00:00Z'
 WHERE code = 'SEOUL_CITYDATA';

-- The revision is built from the row that was just written, with the same field set and the same
-- hash function V007 used. Copying the shape is the point: a snapshot that references revision 2 has
-- to be able to reproduce what revision 1 meant, and two differently-shaped contracts cannot be
-- compared. The current_revision FK is DEFERRABLE, so the update above may name a revision this
-- statement is about to create.
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
     WHERE code = 'SEOUL_CITYDATA'
)
SELECT code, 2, canonical_contract,
       encode(sha256(convert_to(canonical_contract::text, 'UTF8')), 'hex'),
       reviewed_at, reviewed_at
FROM contracts;
