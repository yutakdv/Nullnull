-- BA-022 step 3, the "영업 확인 근거" half: somewhere to keep *the check*, not the value.
--
-- What a row here preserves is "at this moment, a reviewer established this POI's operating status
-- against this page". A value with no such row would be the LLM/guesswork case invariant 9 forbids,
-- and a reviewer who looked and could not settle the question still produced evidence - so an
-- observation with zero windows is a legal, meaningful state. Storing only windows would flatten
-- "checked, and it was closed", "checked, and could not tell" and "nobody checked" into one absence,
-- which is the distinction invariant 6 exists to keep.
--
-- Dates rather than weekday rules, deliberately. KTO's detailIntro2 restdate reads "매주 화요일 ※ 단,
-- 정기휴일이 공휴일 및 대체공휴일과 겹칠 경우에는 …" - a condition that can move a closure onto another
-- day. A weekday rule that blanks Tuesday would leave an OPEN on Wednesday that nobody observed, and
-- a P0 slot carries no start time, so one OPEN row is already an EXACT answer (filters.py: "a
-- date-only proposal only needs an open day"). Per-date rows make the curator resolve the condition
-- for the date, and no holiday calendar - which we do not have - is needed. The unit also matches
-- what the evaluator consumes: {date: OpeningWindowIn}, maxProperties 30, trip limit 30 days.
--
-- The registry row is seeded and the readings are not. A source with no staleness threshold is not
-- collected at all (A-023), and P30D is the owner's answer for how long a human reading of opening
-- hours stays trustworthy (A-032) - so the source can exist now. Which places get read, and by whom,
-- is the curation decision (A-031: an operations script, never a migration and never a write
-- operation), so no observation or window row is created here.
--
-- The readers arrive later, by name: BA-042 hydrates {date: OpeningWindowIn} for SLOT out of these
-- rows, BA-051 for ITEM. Written now rather than then so the first curated reading is entered
-- against a schema that already refuses a value with no evidence behind it, the same reason V021
-- gave for the post cover licence.
--
-- Note for whoever writes V026: FlywayMigrationIT's upgrade case asserts every table in the previous
-- schema holds a row, so populateEveryTable will need one observation and one window, and its exact
-- row count drops back to whatever V026 itself seeds.

-- The source is us reading a public page, so the licence columns are NULL and the review state is
-- NOT_APPLICABLE for the reason NULLNULL_FIRST_PARTY's are: there is no external grant to review when
-- the act being recorded is our own reading. official_url stays NULL on purpose - the page that
-- matters is per POI and lives on the observation row, which is the whole reason this slice exists.
-- attribution_template is NULL because P0 publishes no opening hours; the day a screen shows them,
-- the cited page needs a credit and this row is where that decision lands. The fixed reviewed_at
-- records the decision date rather than pretending deploy time was review time (V007's convention).
INSERT INTO source_registry
    (code, display_name, source_state, license_name, license_url, license_review_state,
     official_url, terms_url, default_scope, metric_definition, approval_state, quota_policy,
     attribution_template, retention_policy, refresh_expectation, provider_schema_version,
     current_revision, stale_after_seconds, enabled, contest_use, reviewed_at, updated_at)
VALUES
    ('NULLNULL_CURATED_HOURS', '널널 큐레이션 영업시간', 'QUALITATIVE', NULL, NULL,
     'NOT_APPLICABLE', NULL, NULL, 'PLACE', '사람이 공식 안내를 읽어 확인한 영업 상태',
     'PROD_APPROVED', '{"perDay":100000,"thresholds":[60,80,90]}', NULL,
     '관측은 supersede로 대체하고 지난 관측도 지우지 않는다', '재확인 주기 P30D (A-032)',
     'curated-hours-v1', 1, 2592000, true, '{"required":false}',
     '2026-09-13T00:00:00Z', '2026-09-13T00:00:00Z');

-- Hashed the way V007 hashes: over the canonical contract itself, so the digest changes when the
-- contract does. V021 hashed a literal string instead, which cannot notice a contract edit.
INSERT INTO source_registry_revisions
    (source_code, version, canonical_contract, contract_hash, reviewed_at, created_at)
WITH contract AS (
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
     WHERE code = 'NULLNULL_CURATED_HOURS'
)
SELECT code, 1, canonical_contract,
       encode(sha256(convert_to(canonical_contract::text, 'UTF8')), 'hex'),
       reviewed_at, reviewed_at
  FROM contract;

CREATE TABLE place_hours_observations (
    id uuid PRIMARY KEY,
    place_id uuid NOT NULL REFERENCES places(id),
    source_code varchar(64) NOT NULL REFERENCES source_registry(code),
    source_registry_version bigint NOT NULL,
    outcome varchar(20) NOT NULL,
    observed_at timestamptz NOT NULL,
    evidence_url varchar(500) NOT NULL,
    stale_at timestamptz NOT NULL,
    superseded_at timestamptz,
    created_at timestamptz NOT NULL,
    CONSTRAINT place_hours_observations_outcome_check CHECK
        (outcome IN ('OBSERVED', 'NO_INFORMATION', 'AMBIGUOUS')),
    -- The page the reviewer actually read, per place. source_registry.official_url cannot carry this:
    -- code is its primary key, so a source has exactly one URL and a POI-level page has nowhere to go.
    CONSTRAINT place_hours_observations_evidence_url_check CHECK
        (evidence_url ~ '^https://[^[:space:]]+$'),
    -- A reading that never goes stale is a permanent claim about a fact that changes seasonally.
    CONSTRAINT place_hours_observations_staleness_check CHECK (stale_at > observed_at),
    CONSTRAINT place_hours_observations_version_check CHECK (source_registry_version > 0),
    CONSTRAINT place_hours_observations_revision_fk
        FOREIGN KEY (source_code, source_registry_version)
        REFERENCES source_registry_revisions(source_code, version)
);

-- One current reading per place. Two sources curating the same POI would need a precedence rule and
-- there is none, so this refuses the second row rather than letting a hydrator pick a winner that
-- nobody decided. Superseding the old row frees the slot, which is also the merge rule: when a
-- duplicate place is folded into its canonical one, the disappearing ID's reading loses.
CREATE UNIQUE INDEX place_hours_observations_current_idx
    ON place_hours_observations(place_id) WHERE superseded_at IS NULL;

CREATE TABLE place_hours_windows (
    id uuid PRIMARY KEY,
    -- "A value needs evidence behind it" is held here twice: by this foreign key, and by the
    -- catalog_require_observed_hours_evidence trigger below, whose NOT FOUND branch rejects the same
    -- row. Measured, not assumed: removing either one alone leaves BA-022-T4 green, and removing both
    -- turns it red. So the clause is guarded either way, and neither guard is proven on its own -
    -- do not read a passing T4 as evidence that this foreign key is doing the work.
    observation_id uuid NOT NULL REFERENCES place_hours_observations(id),
    effective_on date NOT NULL,
    state varchar(10) NOT NULL,
    opens_at time,
    closes_at time,
    -- There is no UNKNOWN here on purpose: an absent row is the unverified one. A storable UNKNOWN
    -- would let a curator write "don't know" and a later hydrator read a full map as authoritative,
    -- which is what ProposalRevalidatorTest.anAbsentOpeningWindowIsUnverifiedRatherThanOpen fixes at
    -- the other end of the same pipe.
    CONSTRAINT place_hours_windows_state_check CHECK (state IN ('OPEN', 'CLOSED')),
    -- The same rule OpeningWindowIn's canonical constructor enforces (D-REC-18: P0 has no overnight
    -- window). The table refuses what the record refuses, so no hydrator can ship a window that no
    -- filter is able to evaluate.
    CONSTRAINT place_hours_windows_shape_check CHECK
        ((state = 'CLOSED' AND opens_at IS NULL AND closes_at IS NULL)
         OR (state = 'OPEN' AND opens_at IS NOT NULL AND closes_at IS NOT NULL
             AND closes_at > opens_at)),
    CONSTRAINT place_hours_windows_date_unique UNIQUE (observation_id, effective_on)
);

-- Evidence is catalog content, so it follows the V010 rule the other catalog tables follow: it
-- attaches to an active canonical place, never to a deprecated duplicate.
CREATE TRIGGER place_hours_observations_active_place_guard
BEFORE INSERT OR UPDATE OF place_id ON place_hours_observations
FOR EACH ROW EXECUTE FUNCTION catalog_require_active_place();

-- A window may only hang off a reading that actually settled the question. This cannot be a CHECK
-- because it reads the parent row, the same reason V010 uses triggers for its cross-row rules.
CREATE FUNCTION catalog_require_observed_hours_evidence()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    parent_outcome varchar(20);
BEGIN
    SELECT outcome
      INTO parent_outcome
      FROM place_hours_observations
     WHERE id = NEW.observation_id
     FOR SHARE;
    IF NOT FOUND OR parent_outcome <> 'OBSERVED' THEN
        RAISE EXCEPTION 'an opening-hours window requires an OBSERVED evidence row';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER place_hours_windows_observed_evidence_guard
BEFORE INSERT OR UPDATE OF observation_id ON place_hours_windows
FOR EACH ROW EXECUTE FUNCTION catalog_require_observed_hours_evidence();

-- The back door the insert guard alone leaves open: store windows under OBSERVED, then downgrade the
-- outcome underneath them, leaving values whose evidence no longer claims to have settled anything.
-- Two steps, in the order V010 already requires for revoking a media licence: remove the values
-- first, then weaken the record.
CREATE FUNCTION catalog_prevent_hours_evidence_downgrade()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.outcome = 'OBSERVED' AND NEW.outcome <> 'OBSERVED'
       AND EXISTS (SELECT 1 FROM place_hours_windows window_row
                    WHERE window_row.observation_id = OLD.id) THEN
        RAISE EXCEPTION 'remove opening-hours windows before downgrading their evidence';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER place_hours_observations_downgrade_guard
BEFORE UPDATE OF outcome ON place_hours_observations
FOR EACH ROW EXECUTE FUNCTION catalog_prevent_hours_evidence_downgrade();

-- V010's deprecation guard lists the dependent records that must be moved before a canonical place
-- is retired. Opening-hours evidence is one of them now; without this line a merge would orphan the
-- reading and the place would quietly lose its hours. Body is V010's, with the fifth EXISTS added.
CREATE OR REPLACE FUNCTION catalog_require_active_canonical_target()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status = 'ACTIVE' THEN
        IF NEW.canonical_place_id IS NOT NULL THEN
            RAISE EXCEPTION 'an active place cannot have a canonical target';
        END IF;
    ELSIF NEW.status = 'DEPRECATED' THEN
        IF NEW.canonical_place_id IS NULL OR NEW.canonical_place_id = NEW.id THEN
            RAISE EXCEPTION 'a deprecated place must name a different canonical target';
        END IF;
        PERFORM 1
          FROM places target
         WHERE target.id = NEW.canonical_place_id
           AND target.status = 'ACTIVE'
         FOR SHARE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'a deprecated place must target an active canonical place';
        END IF;
    ELSE
        RAISE EXCEPTION 'unknown place status';
    END IF;

    IF TG_OP = 'UPDATE' AND OLD.status = 'ACTIVE' AND NEW.status <> 'ACTIVE'
       AND (EXISTS (SELECT 1 FROM places child WHERE child.canonical_place_id = OLD.id)
            OR EXISTS (SELECT 1 FROM place_localizations localization WHERE localization.place_id = OLD.id)
            OR EXISTS (SELECT 1 FROM place_external_refs external_ref WHERE external_ref.place_id = OLD.id)
            OR EXISTS (SELECT 1 FROM place_media_assets place_media WHERE place_media.place_id = OLD.id)
            OR EXISTS (SELECT 1 FROM place_hours_observations hours WHERE hours.place_id = OLD.id)) THEN
        RAISE EXCEPTION 'move dependent catalog records before deprecating a canonical place';
    END IF;
    RETURN NEW;
END;
$$;

COMMENT ON TABLE place_hours_observations IS
    'BA-022 step 3: when, and against which page, a reviewer established a POI''s operating status. '
    'An observation with no window rows is "checked, could not settle it", which is not the same '
    'fact as no observation at all.';
COMMENT ON TABLE place_hours_windows IS
    'BA-022 step 3: one verified OPEN or CLOSED window per date, only under OBSERVED evidence. An '
    'absent date is unverified - never treat it as open (BA-042/BA-051 hydration).';
