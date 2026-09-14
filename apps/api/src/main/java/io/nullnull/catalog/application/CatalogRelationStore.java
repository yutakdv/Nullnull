package io.nullnull.catalog.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The write side of {@code place_relations} for the internal rule that derives SIMILAR evidence. */
public interface CatalogRelationStore {

    /**
     * Every ordered pair of active canonical places sharing a category and a region.
     *
     * <p>Ordered, not unordered: relatedness is asked from one place, so A→B and B→A are two rows and
     * each is what {@code listRelatedPlaces} reads when asked about that end.
     */
    List<RulePair> ruleCandidates();

    /** The revision a derived row pins, read rather than assumed - a new revision changes the terms. */
    long currentRevision(String sourceCode);

    /** Inserts or refreshes the window of each row, keeping {@code created_at} of the ones that exist. */
    int upsert(List<DerivedRelation> rows, String reason);

    /**
     * Closes the window of every rule-derived row whose pair no longer satisfies the rule.
     *
     * <p>Closes, not deletes. A relation that stood yesterday is evidence of what the catalog said
     * yesterday, and the one thing that must not happen is for it to keep being offered - which
     * {@code expires_at} settles without destroying the record (the same reason BA-025 supersedes a
     * reading instead of replacing it).
     *
     * @return how many rows this closed
     */
    int expireUnmatched(Instant now, String sourceCode);

    record RulePair(UUID sourcePlaceId, UUID targetPlaceId) { }

    record DerivedRelation(UUID id, UUID sourcePlaceId, UUID targetPlaceId, String sourceCode,
            long registryVersion, Instant effectiveAt, Instant expiresAt, Instant createdAt) { }
}
