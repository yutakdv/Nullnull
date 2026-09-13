package io.nullnull.live.domain;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * One reviewed link from a canonical place to a Seoul live area (ERD {@code SEOUL_LIVE_AREA_MAPS}).
 *
 * <p>The row carries {@code confidence} and {@code fallbackUsed} because SOURCE_CATALOG §5 requires
 * all three of mappingType, confidence and fallbackUsed whenever an area value is attached to a POI.
 * They belong to the mapping rather than to the observation: how sure we are that this place sits in
 * that area does not change when a new reading arrives.
 *
 * <p>Existing is not the same as being usable. A mapping says where a place would read its value
 * from; whether there is a value to read is the observation's question, and
 * {@link LiveCoverage#decide} keeps the two separate.
 */
public record LiveAreaMapping(UUID placeId, UUID liveAreaId, String mappingType, BigDecimal confidence,
        boolean fallbackUsed) {

    /**
     * The place sits inside the area's published boundary. The only value this module produces today;
     * a looser rule (nearest centroid, containing district) would be a different one and would arrive
     * with {@code fallbackUsed} true and its own name.
     */
    public static final String AREA = "AREA";

    public LiveAreaMapping {
        Objects.requireNonNull(placeId, "placeId");
        Objects.requireNonNull(liveAreaId, "liveAreaId");
        Objects.requireNonNull(mappingType, "mappingType");
        Objects.requireNonNull(confidence, "confidence");
        if (mappingType.isBlank()) {
            // V011's crowd_snapshots.mapping_type CHECK says the same thing one layer down.
            throw new IllegalArgumentException("mappingType must not be blank");
        }
        if (confidence.signum() < 0 || confidence.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
    }
}
