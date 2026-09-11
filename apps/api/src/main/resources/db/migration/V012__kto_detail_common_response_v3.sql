-- BA-022 C3: an actual detailCommon2 call proved the response field mapping wrong. KorService2
-- returns areacode, sigungucode and cat1/cat2/cat3 as empty strings; the populated identifiers are
-- lDongRegnCd, lDongSignguCd and lclsSystm1/2/3. Only the reviewed provider contract is versioned
-- here; the normalized cache columns keep their generic names and their existing rows keep the
-- meaning of the revision they were collected under.
UPDATE source_registry
   SET provider_schema_version = 'kto-kor-service2-detailcommon2-v3',
       current_revision = 4,
       refresh_expectation = 'detailCommon2 read-through; category from lclsSystm1 and area from lDongRegnCd/lDongSignguCd, legacy cat1/areacode/sigungucode are no longer populated; maximum P7D cache age',
       reviewed_at = '2026-09-11T00:00:00Z',
       updated_at = '2026-09-11T00:00:00Z'
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
SELECT code, 4, canonical_contract,
       encode(sha256(convert_to(canonical_contract::text, 'UTF8')), 'hex'),
       reviewed_at, reviewed_at
  FROM contract;
