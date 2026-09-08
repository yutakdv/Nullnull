package io.nullnull.crowd.domain;

import java.util.Objects;

/** Pair-level comparison decision (docs/data/SOURCE_CATALOG.md §10). reasonCode is one of §9. */
public record ComparisonVerdict(boolean eligible, String reasonCode) {

    public ComparisonVerdict {
        Objects.requireNonNull(reasonCode, "reasonCode");
    }

    public static ComparisonVerdict eligible(String reasonCode) {
        return new ComparisonVerdict(true, reasonCode);
    }

    public static ComparisonVerdict ineligible(String reasonCode) {
        return new ComparisonVerdict(false, reasonCode);
    }
}
