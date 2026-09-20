-- BA-086: a localization row says which source published its text, under which reviewed revision,
-- in which language, and when it was observed. Until now the row carried only the text, and the
-- only credit the read path could reach was place_external_refs - the PLACE's provenance. That is
-- the right answer for "where did this place record come from" and the wrong one for "who wrote
-- this sentence": a place can carry several external refs, so crediting the text by picking one of
-- them is not projecting provenance, it is choosing one and calling it the source.
--
-- WHY NULLABLE HERE WHEN place_external_refs (V010) MAKES THE SAME TWO COLUMNS NOT NULL.
-- That table's rows were all written with a source in hand; this table's were not. The ko-KR rows
-- already in place_localizations predate these columns and WE DO NOT KNOW what produced each one.
-- Deriving it from the place's external ref is the very act the paragraph above rules out, and a
-- NOT NULL column would force exactly that derivation to make the migration apply. So the columns
-- are nullable, nothing is backfilled, and "we do not know" stays distinguishable from a claim.
-- The difference between the two tables is the difference between their existing rows, not a
-- weaker rule here - do not "fix" the inconsistency by tightening this side.
--
-- All four or none. A row with a source code and no revision would be a source claim with no
-- reviewed contract behind it, and one with a revision and no code would reference nothing. The
-- composite FK cannot catch either on its own: it is MATCH SIMPLE, so it is not enforced at all
-- when any referencing column is NULL. num_nonnulls is what makes the FK reachable.
--
-- NO translated FLAG. Whether a row is a translation is source_locale <> locale. A stored flag can
-- disagree with the columns it summarises; a derived one cannot.
--
-- NO SEPARATE TRANSLATION-REVISION TABLE. source_registry_version IS the revision axis. A second
-- history table would create a second answer to "which version of this text is current", and the
-- two would drift.
--
-- WHAT THE PIN MEANS FOR READS, AND ITS BLAST RADIUS. The read path serves a provenance-bearing
-- localization only while the revision it was collected under is still the source's current one
-- and the source is still enabled; otherwise the text falls back down the existing locale chain.
-- place_external_refs deliberately does NOT work this way - it keeps projecting its pinned
-- revision forever - and the asymmetry is intended: a ref is an IDENTITY ("this place is KTO
-- content 12345"), which does not expire when terms change, while a localization is PROVIDER PROSE
-- WE REPUBLISH, and republishing prose is exactly what a licence governs.
--
-- HOW FAR THE PIN ACTUALLY REACHES - measured, because the first draft of this paragraph asserted
-- something broader and was wrong. Bumping KTO_KOR_SERVICE_2 withdraws the ADDRESS and the
-- DESCRIPTION of Korean text collected under the old revision. It does NOT withdraw the NAME, and
-- the place stays searchable by it: KtoSnapshotCatalogIngest writes snapshot.title() into BOTH
-- places.canonical_name and the localization, and canonical_name is the ungated last resort of the
-- locale chain as well as the ungated search term. So a pin on the localization cannot reach a
-- second copy of the same provider string living on another table. That source has already been
-- bumped three times in this repository's life (V008, V009, V012), so this is a path the project
-- has walked, not a hypothetical.
--
-- And withdrawal is ONE-WAY today. KtoSnapshotCatalogIngest returns the existing place as soon as
-- the external reference is claimed, so nothing afterwards updates a localization's text or its
-- revision pin - there is no re-ingest that brings the address back. Do not read the paragraphs
-- above as promising one.
--
-- This paragraph is a DATED OBSERVATION, not a rule: a migration comment cannot be corrected once
-- applied. The rule lives where it can be edited - the BA-086 card, and
-- JdbcCatalogPlaceQuery.SERVABLE_LOCALIZATION - and PlaceLocalizationProvenanceIT is what measures
-- the part that is enforced.
--
-- PlaceLocalizationProvenanceIT is what measures all of the above. A migration comment cannot be
-- corrected once applied, so it names the test rather than restating the rule: the rule lives
-- where it can be edited, and the name is something a checker can verify still exists.
ALTER TABLE place_localizations
    ADD COLUMN source_code varchar(64) REFERENCES source_registry(code),
    ADD COLUMN source_registry_version bigint,
    ADD COLUMN source_locale varchar(35),
    ADD COLUMN observed_at timestamptz;

ALTER TABLE place_localizations
    ADD CONSTRAINT place_localizations_provenance_complete_check CHECK
        (num_nonnulls(source_code, source_registry_version, source_locale, observed_at) IN (0, 4)),
    ADD CONSTRAINT place_localizations_source_version_check CHECK
        (source_registry_version IS NULL OR source_registry_version > 0),
    ADD CONSTRAINT place_localizations_source_locale_check CHECK
        (source_locale IS NULL OR char_length(btrim(source_locale)) BETWEEN 2 AND 35),
    ADD CONSTRAINT place_localizations_source_revision_fk
        FOREIGN KEY (source_code, source_registry_version)
        REFERENCES source_registry_revisions(source_code, version);

COMMENT ON COLUMN place_localizations.source_code IS
    'BA-086: the source that published this text, not the place. NULL means the row predates these '
    'columns and its origin is unknown - it is never derived from place_external_refs.';
COMMENT ON COLUMN place_localizations.source_registry_version IS
    'BA-086: the reviewed source_registry revision this text was collected under. The read path '
    'stops serving the text when the source moves past it; re-ingest is what brings it back.';
COMMENT ON COLUMN place_localizations.source_locale IS
    'BA-086: the language the source published in. source_locale <> locale is what "translated" '
    'means here; there is deliberately no stored flag saying so.';
COMMENT ON COLUMN place_localizations.observed_at IS
    'BA-086: when this text was observed at the source, which is the provider timeline, not the '
    'row timeline that updated_at records.';
