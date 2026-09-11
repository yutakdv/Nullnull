-- BA-021 C2: KTO's current detailCommon2 contract accepts only its baseline and contentId.
-- Keep the normalized cache schema intact and version only the reviewed provider request contract.
UPDATE source_registry
   SET provider_schema_version = 'kto-kor-service2-detailcommon2-v2',
       current_revision = 3,
       refresh_expectation = 'detailCommon2 read-through; current request omits contentTypeId and legacy detail flags; maximum P7D cache age',
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
SELECT code, 3, canonical_contract,
       encode(sha256(convert_to(canonical_contract::text, 'UTF8')), 'hex'),
       reviewed_at, reviewed_at
  FROM contract;
