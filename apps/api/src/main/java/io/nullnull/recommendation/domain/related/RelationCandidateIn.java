package io.nullnull.recommendation.domain.related;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Mirrors {@code RelationCandidateIn}: one relation evidence row from {@code catalog.place_relations}
 * after canonical mapping. {@code mapping} is the certainty of that mapping, and an UNCERTAIN row is
 * quarantined by the service rather than ranked. {@code confidence} is provenance the service carries
 * but never orders on, and a row is only usable inside {@code [effectiveAt, expiresAt)}.
 */
public record RelationCandidateIn(UUID sourcePlaceId, UUID targetPlaceId, RelationTier tier, String sourceCode,
        String channel, BigDecimal confidence, Instant effectiveAt, Instant expiresAt, MappingCertainty mapping) {

    /** EXACT before SIMILAR. Assigned by the catalog mapping policy, never synthesized from a confidence. */
    public enum RelationTier { EXACT, SIMILAR }

    public enum MappingCertainty { CERTAIN, UNCERTAIN }

    public RelationCandidateIn {
        Objects.requireNonNull(sourcePlaceId, "sourcePlaceId");
        Objects.requireNonNull(targetPlaceId, "targetPlaceId");
        Objects.requireNonNull(tier, "tier");
        Objects.requireNonNull(sourceCode, "sourceCode");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(effectiveAt, "effectiveAt");
        Objects.requireNonNull(mapping, "mapping");
    }
}
