-- A-024: a post cover is a 1st-party asset the team made, never a provider photograph.
--
-- The decision (2026-09-13, D-007's post half) was taken because a provider photograph needs a
-- per-record 공공누리 review and PostSummary has no credit field to carry the result, so publishing
-- one would either break the contract or drop an attribution the licence requires. It also limits
-- covers to explicit illustration rather than photorealistic rendering, because a synthetic image of
-- a real place presented as a photograph breaks the synthesised/observed distinction (invariant 6).
--
-- Today that decision lives only in a decision table. posts.cover_url is free text with no link to a
-- licence, so nothing stops a row whose cover is a provider photograph, and nothing would report it.
-- This makes the rule storage.

-- The source these assets come from is us. licence fields are NULL and the review state is
-- NOT_APPLICABLE for the reason NULLNULL_CATALOG_RULE's are: there is no external grant to review
-- when the work is our own. The fixed reviewed_at records the decision date rather than pretending
-- deploy time was review time, which is the convention V007 established.
INSERT INTO source_registry
    (code, display_name, source_state, license_name, license_url, license_review_state,
     official_url, terms_url, default_scope, metric_definition, approval_state, quota_policy,
     attribution_template, retention_policy, refresh_expectation, provider_schema_version,
     current_revision, stale_after_seconds, enabled, contest_use, reviewed_at, updated_at)
VALUES
    ('NULLNULL_FIRST_PARTY', '널널 1st-party 자산', 'QUALITATIVE', NULL, NULL,
     'NOT_APPLICABLE', NULL, NULL, 'PLACE', '팀이 직접 제작한 일러스트와 편집 자산',
     'PROD_APPROVED', '{"perDay":100000,"thresholds":[60,80,90]}', NULL,
     '자산은 게시물 수명 동안 보존한다', '자산 교체 시에만 갱신', 'first-party-v1', 1,
     604800, true, '{"required":false}',
     '2026-09-13T00:00:00Z', '2026-09-13T00:00:00Z');

INSERT INTO source_registry_revisions
    (source_code, version, canonical_contract, contract_hash, reviewed_at, created_at)
SELECT code, 1,
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
           'providerSchemaVersion', provider_schema_version,
           'quotaPolicy', quota_policy,
           'refreshExpectation', refresh_expectation,
           'retentionPolicy', retention_policy,
           'sourceState', source_state,
           'staleAfterSeconds', stale_after_seconds),
       encode(sha256(convert_to(code || ':first-party-v1', 'UTF8')), 'hex'),
       reviewed_at, reviewed_at
  FROM source_registry
 WHERE code = 'NULLNULL_FIRST_PARTY';

-- A-024's two flags, in the columns this schema has for them. attribution_template NULL is
-- attributionRequired=false: there is nobody outside the team to credit. redistribution and
-- derivatives are allowed because the work is ours to allow.
INSERT INTO asset_licenses
    (id, source_code, source_registry_version, external_license_code, license_name, license_url,
     attribution_template, redistribution_allowed, derivative_allowed, reviewed_at)
VALUES
    ('019321a4-0000-7000-8000-000000000001', 'NULLNULL_FIRST_PARTY', 1, NULL,
     '널널 1st-party 자산 (내부 제작)', NULL, NULL, true, true, '2026-09-13T00:00:00Z');

-- The link that makes the decision checkable. Nullable, because a DRAFT is written before its cover
-- exists; required on PUBLISHED, because that is the moment the cover reaches a reader.
ALTER TABLE posts ADD COLUMN cover_asset_id uuid REFERENCES media_assets(id);

-- NOT VALID, which is not a weakening of what the rule does but a statement about when it began.
-- PostgreSQL enforces a NOT VALID CHECK on every INSERT and every UPDATE; what it skips is the scan
-- of rows that were already there. A published post written before this migration therefore keeps
-- its free-text cover until something touches it, and everything written from now on must name an
-- asset.
--
-- A plain CHECK was tried first and FlywayMigrationIT refused the upgrade: its previous-to-latest
-- case seeds a PUBLISHED post with a cover_url and no asset, which is exactly the row a real
-- deployment of the old schema could hold. The comment this replaces claimed no such row could exist
-- because no production writer creates posts - true of the writer, and not of the data.
--
-- Making it valid is a follow-up (ALTER TABLE posts VALIDATE CONSTRAINT), possible once every
-- published post has a cover asset, and it will fail loudly while one does not.
ALTER TABLE posts ADD CONSTRAINT posts_published_cover_asset_check CHECK
    (status <> 'PUBLISHED' OR cover_asset_id IS NOT NULL) NOT VALID;

COMMENT ON COLUMN posts.cover_asset_id IS
    'A-024: the 1st-party MediaAsset this cover is. Required to publish, so a post cannot reach a '
    'reader with a cover that has no licence row behind it. cover_url stays as the rendered URL.';

-- Added now rather than later so the first published post is written against a schema that already
-- refuses a cover with no licence behind it, instead of one retrofitted around rows that were
-- allowed in without one.
