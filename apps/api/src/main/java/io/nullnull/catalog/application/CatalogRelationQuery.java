package io.nullnull.catalog.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Candidate selection for related places: which stored relations still stand at a given instant.
 *
 * <p>Written before the response stage on purpose. Selecting candidates needs canonical ids and the
 * evidence around them, not a {@code PlaceSummary}, so the catalog publication gate that stops
 * {@code listRelatedPlaces} from carrying items does not reach this far. That is also why it can be
 * proven now: BA-024-T4's clause is that expired evidence is not offered as a candidate, and
 * offering candidates is exactly what this does.
 *
 * <p>No production caller yet - the BA-024 response stage adds one, and the apps/ai
 * {@code related/rank} gateway is what it hands these to. {@code ArchitectureRulesTest}'s
 * AWAITING_THEIR_SLICE register does not cover this: it scans only
 * {@code io.nullnull.recommendation.application} and skips interfaces, and a read-model port is an
 * interface here by the pattern {@link CatalogPlaceQuery} sets.
 *
 * <p>The id is taken as canonical. Resolving a deprecated alias is the response stage's job, the
 * same split {@link CatalogPlaceQuery#find} uses, and the deprecation guard in V027 is what keeps a
 * stored relation from naming a retired place in the first place.
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
     * One stored relation, with the provenance the ranker scores and the response projects. There is
     * no place content here: hydration happens after selection, behind the publication gate.
     */
    record CatalogRelationCandidate(UUID targetPlaceId, String relationType, String relationReason,
            String sourceCode, long sourceRegistryVersion, Instant effectiveAt, Instant expiresAt) {}
}
