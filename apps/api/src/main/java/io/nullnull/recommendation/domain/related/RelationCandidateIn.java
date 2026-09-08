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

    /** The registry code of the source this row came from; the service refuses a longer one with a 422. */
    public static final int MAX_SOURCE_CODE = 64;
    /** The evidence channel behind the relation; capped like the source code. */
    public static final int MAX_CHANNEL = 64;

    public RelationCandidateIn {
        Objects.requireNonNull(sourcePlaceId, "sourcePlaceId");
        Objects.requireNonNull(targetPlaceId, "targetPlaceId");
        Objects.requireNonNull(tier, "tier");
        Objects.requireNonNull(sourceCode, "sourceCode");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(effectiveAt, "effectiveAt");
        Objects.requireNonNull(mapping, "mapping");
        requireBounded(sourceCode, MAX_SOURCE_CODE, "sourceCode");
        requireBounded(channel, MAX_CHANNEL, "channel");
    }

    /** A value outside the contract length is a mapping bug on this side, never a user input. */
    private static void requireBounded(String value, int limit, String name) {
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (value.length() > limit) {
            throw new IllegalArgumentException(name + " must be at most " + limit + " characters");
        }
    }
}
