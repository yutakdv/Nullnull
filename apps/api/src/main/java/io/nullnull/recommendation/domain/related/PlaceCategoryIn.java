package io.nullnull.recommendation.domain.related;

import java.util.Objects;
import java.util.UUID;

/**
 * Mirrors {@code PlaceCategoryIn}: the canonical category of one place at a fixed taxonomy version.
 * {@code categoryCode} may be null (missing); a missing category is answered as an unknown match and
 * never as a mismatch, so nothing is inferred from the absence.
 */
public record PlaceCategoryIn(UUID placeId, String categoryCode, String parentCategoryCode, String taxonomyVersion) {

    /** A canonical category code; the service refuses a longer one with a 422. */
    public static final int MAX_CODE = 64;
    /** The taxonomy revision the codes belong to; capped like the codes themselves. */
    public static final int MAX_TAXONOMY_VERSION = 64;

    public PlaceCategoryIn {
        Objects.requireNonNull(placeId, "placeId");
        Objects.requireNonNull(taxonomyVersion, "taxonomyVersion");
        if (taxonomyVersion.isBlank()) {
            throw new IllegalArgumentException("taxonomyVersion must not be blank");
        }
        requireAtMost(categoryCode, MAX_CODE, "categoryCode");
        requireAtMost(parentCategoryCode, MAX_CODE, "parentCategoryCode");
        requireAtMost(taxonomyVersion, MAX_TAXONOMY_VERSION, "taxonomyVersion");
    }

    /** A value over the contract length is a mapping bug on this side, never a user input. */
    private static void requireAtMost(String value, int limit, String name) {
        if (value != null && value.length() > limit) {
            throw new IllegalArgumentException(name + " must be at most " + limit + " characters");
        }
    }
}
