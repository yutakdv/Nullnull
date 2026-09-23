package io.nullnull.catalog.application;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * The owner's rule for "this English record is that canonical place" (issue #60, 2026-09-21): within
 * 100 m, the same classification (lclsSystm1) and the same legal dong (lDongRegnCd + lDongSignguCd).
 *
 * <p>The owner applies the rule when reviewing candidates; the tie-break between two candidates that both
 * pass is part of that review and is not repeated here. What this class does is refuse to show English text
 * for a record that no longer passes - a typo in the reviewed plan, or a record that moved or was
 * reclassified after the review. A fact missing on either side fails: an unknown distance is not a short one.
 */
public final class EngLinkRule {

    /** Owner decision, #60 2026-09-21. Measured with {@link GeoDistance}, as the reviewed probe output was. */
    public static final double MAX_DISTANCE_METERS = 100.0;

    private EngLinkRule() {
    }

    /**
     * The canonical side. The sigungu code is not on {@code places}; it comes from the latest Korean snapshot
     * the place's Korean reference points at, collected under revision 4 or later (from V012 the code is
     * lDongSignguCd), and is null when there is none.
     */
    public record PlaceFacts(BigDecimal latitude, BigDecimal longitude, String categoryCode, String regionCode,
            String sigunguCode) {
    }

    public static boolean holds(KtoEngRecord record, PlaceFacts place) {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(place, "place");
        if (record.latitude() == null || record.longitude() == null
                || place.latitude() == null || place.longitude() == null) {
            return false;
        }
        if (!present(record.classification()) || !record.classification().equals(place.categoryCode())
                || !present(record.regionCode()) || !record.regionCode().equals(place.regionCode())
                || !present(record.sigunguCode()) || !record.sigunguCode().equals(place.sigunguCode())) {
            return false;
        }
        return GeoDistance.haversineMeters(record.latitude(), record.longitude(), place.latitude(), place.longitude())
                <= MAX_DISTANCE_METERS;
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
