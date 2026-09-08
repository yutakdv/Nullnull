package io.nullnull.recommendation.domain.related;

import io.nullnull.recommendation.domain.related.RelationCandidateIn.RelationTier;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Mirrors {@code RelatedItemOut}: one canonical place with the evidence that survived. The service
 * sends {@code categoryMatch} as a string, so it arrives as an exact {@link BigDecimal}; it is null
 * when no comparable category exists, which is a different answer from the 0 of a different category.
 * {@code channels} names the evidence channels behind the place, never a popularity or crowd signal.
 */
public record RelatedItemOut(UUID placeId, RelationTier tier, BigDecimal categoryMatch, int evidenceCount,
        List<String> channels) {

    public RelatedItemOut {
        Objects.requireNonNull(placeId, "placeId");
        Objects.requireNonNull(tier, "tier");
        channels = List.copyOf(Objects.requireNonNull(channels, "channels"));
    }
}
