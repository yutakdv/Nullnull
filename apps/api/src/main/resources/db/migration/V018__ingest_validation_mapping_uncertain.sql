-- BA-023: a rejected KTO forecast batch could only be recorded as SCHEMA_DRIFT, which names the
-- provider as the cause. Two of the conditions that reach that branch are ours, not theirs.
--
-- tatsCnctrRatedList is requested with a fixed numOfRows=100 and tAtsNm as a FILTER, not a key
-- (docs/data/SOURCE_CATALOG.md, measured 2026-09-11: one sigungu without tAtsNm returns 3390 rows).
-- A canonical touristSiteName that matches more than one site therefore answers with more rows than
-- one site's window, or with a page smaller than its own totalCount. The envelope is intact and the
-- provider changed nothing - our mapping did not identify a single place. Recording that as drift
-- sends the next reader to the provider's documentation for a defect that is in our data.
--
-- MAPPING_UNCERTAIN is the word the crowd module already uses for this (V011's quality_flags
-- vocabulary, ComparisonReasonCode.MAPPING_UNCERTAIN), so the audit plane and the comparison plane
-- name the same condition the same way instead of inventing a second term for it.
--
-- Widening a CHECK cannot invalidate a stored row, and no row has ever carried the new value.
ALTER TABLE api_ingest_logs DROP CONSTRAINT api_ingest_validation_check;
ALTER TABLE api_ingest_logs ADD CONSTRAINT api_ingest_validation_check CHECK
    (validation_result IN ('PENDING', 'OK', 'SCHEMA_DRIFT', 'ENUM_DRIFT',
                           'RANGE', 'TIME_SKEW', 'PROVIDER_ERROR', 'MAPPING_UNCERTAIN'));
