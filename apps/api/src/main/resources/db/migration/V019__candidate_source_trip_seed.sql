-- BA-040: removeTripItem with disposition=RESTORE_CANDIDATE puts the place back as a candidate, and
-- a candidate must say where it came from - candidate_sources is NOT NULL and the contract requires
-- at least one entry.
--
-- An item that arrived through createTrip's seedItems came from none of the four stored words. It was
-- not saved from a post, not found through search, not seen on the Live tab, and not pasted; it
-- arrived with the trip. Writing SEARCH for it would record a provenance the user never produced,
-- which is the same rule this repository applies to external records applied to our own data.
--
-- The vocabulary is declared in three places - CandidateSourceType, this CHECK, and the contract's
-- CandidateSource.type - and only the Java enum and this constraint can reject a value. Adding the
-- word to one of them alone compiles and passes every test until a row is actually written, which is
-- the shape ProviderOutcomeVocabularyIT exists to catch elsewhere; CandidateSourceVocabularyIT now
-- compares these two here.
--
-- The contract publishes the list as x-extensible-enum rather than enum, for the reason
-- x-nullnull-interest-codes does: a closed response enum makes every new word a breaking change
-- (measured: adding TRIP_SEED as an enum value reports 15 response-property-enum-value-added
-- findings), and this vocabulary is expected to grow - LIVE belongs to a P1 tab that does not exist
-- yet.
--
-- Widening a CHECK cannot invalidate a stored row, and no row has ever carried the new value.
ALTER TABLE candidate_sources DROP CONSTRAINT candidate_sources_type_check;
ALTER TABLE candidate_sources ADD CONSTRAINT candidate_sources_type_check CHECK
    (source_type IN ('POST', 'SEARCH', 'LIVE', 'IMPORT', 'TRIP_SEED'));
