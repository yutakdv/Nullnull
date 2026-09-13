package io.nullnull.catalog.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Candidate selection for related places: which stored relations still stand at a given instant.
 *
 * <p>Selection needs canonical ids and the evidence around them, not place content, so it sits in
 * front of the hydration the catalog publication gate guards. That is also why BA-024-T4 could be
 * settled before the response existed: its clause is that expired evidence is not offered as a
 * candidate, and offering candidates is what this does.
 *
 * <p><b>The place id is taken as canonical, and nothing resolves an alias for you.</b> Safe only
 * while no production path can produce a deprecated place, which is today's measured state - the one
 * writer of {@code places} passes a null {@code canonical_place_id} and every DEPRECATED row is
 * written by a test. {@code CatalogRelationProjectionService} resolves before calling, so a merge
 * path would find the resolution already in the caller rather than here.
 */
public interface CatalogRelationQuery {

    /**
     * Relations from this place whose evidence window contains {@code at}, in a fixed order that
     * does not depend on the order they were written.
     *
     * <p>{@code expires_at} is the moment the evidence stops standing, so a window is open while
     * {@code effective_at <= at < expires_at}. An open-ended relation - what an internal rule
     * produces, since a taxonomy similarity does not lapse on a date - has no {@code expires_at} and
     * is always inside its window.
     */
    List<CatalogRelationCandidate> candidatesFor(UUID sourcePlaceId, Instant at);

    /**
     * One stored relation with the provenance the response projects. No place content: hydration
     * happens after selection, behind the publication gate.
     *
     * <p>{@code id} is the row's own id, which the response publishes as {@code provenanceId} - the
     * evidence has an identity of its own, separate from either place.
     */
    record CatalogRelationCandidate(UUID id, UUID targetPlaceId, String relationType,
            String relationReason, String derivation, String mappingCertainty, Instant effectiveAt,
            Instant expiresAt, Instant recordedAt, CatalogRelationSource source) {}

    /**
     * The registry values as they stood in the revision this relation pinned, never as they stand
     * now. A source's licence or credit can change, and a relation recorded under the old terms must
     * keep being described by them (ERD §4, the rule place_external_refs and asset_licenses follow).
     */
    record CatalogRelationSource(String code, long registryVersion, String displayName,
            String sourceState, String license, String licenseUrl, String officialUrl,
            String attribution, String metricDefinition, String normalizationVersion, String scope) {}
}
