package io.nullnull.recommendation.domain.related;

import java.util.Objects;
import java.util.UUID;

/**
 * Mirrors {@code PlaceCategoryIn}: the canonical category of one place at a fixed taxonomy version.
 * {@code categoryCode} may be null (missing); a missing category is answered as an unknown match and
 * never as a mismatch, so nothing is inferred from the absence.
 */
public record PlaceCategoryIn(UUID placeId, String categoryCode, String parentCategoryCode, String taxonomyVersion) {

    public PlaceCategoryIn {
        Objects.requireNonNull(placeId, "placeId");
        Objects.requireNonNull(taxonomyVersion, "taxonomyVersion");
    }
}
