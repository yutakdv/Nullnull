-- BA-086 (#60): the approved English text source, and the owner-reviewed links that say which English
-- record supplies a canonical place's English text. The evidence for every value below is recorded in
-- docs/data/SOURCE_CATALOG.md (KTO_ENG_SERVICE); this comment points there instead of restating it,
-- because a migration's text cannot be corrected once it has been applied.
--
-- The fixed timestamp records when the values were assembled, rather than pretending deploy time was
-- review time (the V007 convention).
INSERT INTO source_registry
    (code, display_name, source_state, license_name, license_url, license_review_state,
     official_url, terms_url, default_scope, metric_definition, approval_state, quota_policy,
     attribution_template, retention_policy, refresh_expectation, provider_schema_version,
     current_revision, stale_after_seconds, enabled, contest_use, reviewed_at, updated_at)
VALUES
    ('KTO_ENG_SERVICE', '한국관광공사 영문 관광정보', 'QUALITATIVE',
     '이용허락범위 제한 없음 (관광정보 텍스트; 이미지 별도 심사)',
     'https://www.data.go.kr/ugs/selectPortalPolicyView.do', 'RECORD_LEVEL_REVIEW_REQUIRED',
     'https://www.data.go.kr/data/15101753/openapi.do',
     'https://www.data.go.kr/ugs/selectPortalPolicyView.do', 'PLACE',
     '한국관광공사 관광지 영문 이름과 주소', 'DEV_APPROVED',
     '{"perDay":1000,"thresholds":[60,80,90]}', '출처: ⓒ한국관광공사',
     '정규화한 영문 이름과 주소만 보존; 개요와 이미지는 보존하지 않음',
     'detailCommon2 for owner-reviewed links only, refreshed by an operator command; text is withdrawn when the record is gone or fails the link rule; target cadence P7D',
     'kto-eng-service2-detailcommon2-v1', 1, 604800, true,
     '{"required":false,"operations":["detailCommon2"],"provenance":"collector-run"}',
     '2026-09-23T00:00:00Z', '2026-09-23T00:00:00Z');

-- Revision 1 is the canonical contract of the row above, built and hashed the way V012 builds the
-- Korean source's revisions, so textProvenance credits English text from the same reviewed shape.
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
     WHERE code = 'KTO_ENG_SERVICE'
)
SELECT code, 1, canonical_contract,
       encode(sha256(convert_to(canonical_contract::text, 'UTF8')), 'hex'),
       reviewed_at, reviewed_at
  FROM contract;

-- One reviewed link per place and locale: the owner's decision that this external record is that
-- place, made with the review time and evidence page recorded in the operator plan. It is not a
-- place_external_refs row because those are read without a source filter (the place credit, the
-- public externalRefs list) and are the place record's own identity, not a text source.
CREATE TABLE place_localization_sources (
    id uuid PRIMARY KEY,
    place_id uuid NOT NULL REFERENCES places(id),
    locale varchar(35) NOT NULL,
    source_code varchar(64) NOT NULL REFERENCES source_registry(code),
    external_id varchar(200) NOT NULL,
    external_type varchar(100) NOT NULL,
    reviewed_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT place_localization_sources_place_locale_unique UNIQUE (place_id, locale),
    CONSTRAINT place_localization_sources_external_unique UNIQUE (source_code, external_id, external_type),
    CONSTRAINT place_localization_sources_locale_check CHECK (char_length(btrim(locale)) BETWEEN 2 AND 35),
    CONSTRAINT place_localization_sources_external_id_check CHECK (btrim(external_id) <> ''),
    CONSTRAINT place_localization_sources_external_type_check CHECK (btrim(external_type) <> ''),
    CONSTRAINT place_localization_sources_timestamps_check CHECK
        (updated_at >= created_at AND reviewed_at <= updated_at)
);
