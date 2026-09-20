-- BA-082: the source a user-uploaded post cover belongs to, and the record of an upload we signed.
--
-- A-058 (2026-09-20) replaced A-053's owner approval gate with automatic technical validation:
-- bytes that fail it never become a MediaAsset. Quarantine itself stays - stripping EXIF rewrites
-- the file, so the server reads the uploaded object, validates it, writes a sanitised copy to the
-- public location and deletes the original. The window between those two is what quarantine is.
--
-- WHY A NEW SOURCE. A-024 limits post covers to assets the team made, and NULLNULL_FIRST_PARTY says
-- so in its own metric_definition ('팀이 직접 제작한 일러스트와 편집 자산'). Putting a visitor's
-- photograph under that code would record two false things: that the team made it, and - through
-- asset_licenses.redistribution_allowed - that the work was ours to license. The owner amended
-- A-024 rather than bend the column (2026-09-20).
--
-- WHAT THIS DOES NOT CREATE: a background job type. Abandoned uploads are cleaned by an S3 lifecycle
-- rule on the quarantine prefix, not by a worker. The reason is a budget, measured: JobConnectionBudget
-- refuses a context whose worst case (2*slots + types + 1) exceeds the pool minus the readiness
-- reserve, and at the shipped pool of 12 a third job type does not fit at any concurrency without
-- lowering another card's. The coordinator holds that budget at two types. A validation failure is
-- NOT left to the lifecycle rule - that object is deleted where it fails.
--
-- WHAT IS NOT IN THIS FILE: the size and dimension ceilings. They are config, for the reason V037
-- gives about its own derivation - an applied migration cannot be edited, so a ceiling written here
-- could never be raised. See nullnull.upload in application.yaml, which carries the derivation.

-- The source is us in the same sense NULLNULL_FIRST_PARTY is - we serve the bytes - but the rights
-- are not. license_review_state is RECORD_LEVEL_REVIEW_REQUIRED because each record's licence rests
-- on that record's uploader; NOT_APPLICABLE would claim there is no external grant to review, which
-- is exactly what V021 says about work that IS ours.
--
-- stale_after_seconds is NOT free here, and an earlier draft of this file got it wrong. The column
-- reads like a collection cadence, and nothing is collected from this source - bytes arrive by
-- upload, not by fetch - so that draft wrote NULL. V007's source_registry_enabled_check refused the
-- row: enabled is a DERIVED value there, `enabled = (approval_state IN (DEV_APPROVED, PROD_APPROVED)
-- AND stale_after_seconds IS NOT NULL)`, so a source with no staleness cannot be enabled at all.
-- V021 had already answered this for the other source that originates with us and is never
-- collected: NULLNULL_FIRST_PARTY carries 604800 and is enabled. This row mirrors it, and the number
-- means what it means there - the interval after which a stored record would be treated as stale if
-- anything did read it on a schedule. Nothing does.
--
-- quota_policy carries a perDay because the column is NOT NULL and SourceRegistration refuses
-- quotaPerDay < 1. The number mirrors V021's first-party row for the same reason it was chosen
-- there - no external quota binds a source we originate. IT IS NOT A MEASURED RATE LIMIT. What
-- actually limits uploads is the edge (A-050: WAF, 2,000 requests per IP per 5 minutes on /api/).
INSERT INTO source_registry
    (code, display_name, source_state, license_name, license_url, license_review_state,
     official_url, terms_url, default_scope, metric_definition, approval_state, quota_policy,
     attribution_template, retention_policy, refresh_expectation, provider_schema_version,
     current_revision, stale_after_seconds, enabled, contest_use, reviewed_at, updated_at)
VALUES
    ('USER_UPLOAD', '이용자 업로드 자산', 'QUALITATIVE',
     '업로더가 본인 권리를 밝힌 자산', NULL, 'RECORD_LEVEL_REVIEW_REQUIRED', NULL, NULL, 'PLACE',
     '이용자가 업로드하고 본인 권리를 밝힌 게시물 표지', 'PROD_APPROVED',
     '{"perDay":100000,"thresholds":[60,80,90]}', NULL,
     '자산은 게시물 수명 동안 보존한다. 격리 원본은 검증 직후 삭제한다',
     '업로드 시점에 고정되고 갱신하지 않는다', 'user-upload-v1', 1,
     604800, true, '{"required":false}',
     '2026-09-20T00:00:00Z', '2026-09-20T00:00:00Z');

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
       encode(sha256(convert_to(code || ':user-upload-v1', 'UTF8')), 'hex'),
       reviewed_at, reviewed_at
  FROM source_registry
 WHERE code = 'USER_UPLOAD';

-- One licence row for the whole source, like V021's.
--
-- attribution_template is NULL, and the reason is not V021's. There IS somebody outside the team
-- behind these bytes - but an anonymous session has no name to carry, so a template could only
-- ever render a label about the content ('uploaded by a visitor'), not a credit to anyone. The
-- contest requirement does not reach here either: the notice makes source text mandatory for
-- PUBLIC DATA (docs/contest/2026-관광데이터-활용-공모전-공지-심사기준.md section 8, examples
-- '출처: ⓒ한국관광공사'), and a visitor's photograph is not public data. If accounts ever land and
-- an uploader can be named, this is the column that changes.
--
-- redistribution_allowed is true because publishing is what the
-- uploader consents to, and it is what lets our CDN serve the file at all (the ERD's media rule
-- forbids proxying an asset whose redistribution is not allowed). derivative_allowed is false: the
-- consent is to publish the photograph, not to make new works from it.
--
-- WHERE THAT CONSENT LIVES: a notice beside the publish control, not a field in the request. A
-- checkbox was considered and dropped (owner, 2026-09-20) - an anonymous session has nobody to
-- record the consent against, and a flag every client always sends true is a constant dressed as a
-- user action. Do not go looking for a licenceAttested column or request field; its absence is the
-- decision, not an omission.
--
-- The id continues V021's seeded-licence namespace rather than starting a new one.
INSERT INTO asset_licenses
    (id, source_code, source_registry_version, external_license_code, license_name, license_url,
     attribution_template, redistribution_allowed, derivative_allowed, reviewed_at)
VALUES
    ('019321a4-0000-7000-8000-000000000002', 'USER_UPLOAD', 1, NULL,
     '이용자 업로드 (업로더 권리 확인)', NULL, NULL, true, false,
     '2026-09-20T00:00:00Z');

-- An upload we signed a URL for. The row exists so the second call can prove the first one happened
-- for THIS owner: createPost is handed an id, not a path, and a path it never chose cannot be walked
-- into somebody else's prefix (BA-082-T1).
CREATE TABLE upload_intents (
    id uuid PRIMARY KEY,
    -- CASCADE, not SET NULL: an intent with no owner is a signed path nobody can claim. Account
    -- deletion takes it with the owner, and the object it points at is the lifecycle rule's.
    owner_id uuid NOT NULL REFERENCES owners(id) ON DELETE CASCADE,
    status varchar(20) NOT NULL,
    content_type varchar(40) NOT NULL,
    content_length bigint NOT NULL,
    checksum_sha256 varchar(64) NOT NULL,
    quarantine_key varchar(400) NOT NULL,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    -- No EXPIRED state. Nothing would write it: we decided against a sweeping job, so a stored
    -- EXPIRED would be a value no code can produce - the kind of state this repo calls a guard that
    -- cannot fire. Expiry is read from expires_at at the moment of consumption instead.
    CONSTRAINT upload_intents_status_check CHECK (status IN ('PENDING', 'CONSUMED', 'REJECTED')),
    -- The two formats the contract accepts, and the list is short because of HOW metadata is
    -- removed, not because of taste: the server decodes the upload and encodes it again, so the
    -- original's EXIF cannot survive - and that needs both a reader and a writer. Measured on
    -- Temurin 21, ImageIO.getReaderMIMETypes() offers jpeg and png but NOT webp. A third format
    -- means adding a codec dependency, not widening this CHECK. io.nullnull.social.domain.ImageFormat
    -- holds the same two and the two move together.
    CONSTRAINT upload_intents_content_type_check
        CHECK (content_type IN ('image/jpeg', 'image/png')),
    -- Positive only. The CEILING is config (see the file header).
    CONSTRAINT upload_intents_length_check CHECK (content_length > 0),
    CONSTRAINT upload_intents_checksum_check CHECK (checksum_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT upload_intents_key_check CHECK (btrim(quarantine_key) <> ''),
    CONSTRAINT upload_intents_expiry_check CHECK (expires_at > created_at),
    -- consumed_at exists exactly while the intent is no longer PENDING, the shape posts uses for
    -- published_at: a CONSUMED row with no time would say the upload was used at no moment.
    CONSTRAINT upload_intents_consumed_shape_check CHECK
        ((status = 'PENDING' AND consumed_at IS NULL)
         OR (status <> 'PENDING' AND consumed_at IS NOT NULL)),
    -- One intent per signed path. Two rows sharing a key would let one owner's createPost consume
    -- the object another owner uploaded.
    CONSTRAINT upload_intents_key_unique UNIQUE (quarantine_key)
);

CREATE INDEX upload_intents_owner_created_idx ON upload_intents (owner_id, created_at DESC);
