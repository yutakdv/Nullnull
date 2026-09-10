package io.nullnull.catalog.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Canonical catalog identity independent of a provider's content ID.
 *
 * <p>The database repeats these structural rules and additionally verifies that a deprecated row
 * targets an active row. Keeping the value object strict prevents a future ingest path from
 * constructing a row that could only fail at the persistence boundary.</p>
 */
public record CatalogPlace(
        java.util.UUID id,
        java.util.UUID canonicalPlaceId,
        String canonicalName,
        String categoryCode,
        BigDecimal latitude,
        BigDecimal longitude,
        String regionCode,
        CatalogPlaceStatus status,
        Instant createdAt,
        Instant updatedAt) {

    public CatalogPlace {
        Objects.requireNonNull(id, "id");
        canonicalName = requiredText("canonicalName", canonicalName, 200);
        categoryCode = requiredText("categoryCode", categoryCode, 100);
        regionCode = requiredText("regionCode", regionCode, 100);
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if ((latitude == null) != (longitude == null)) {
            throw new IllegalArgumentException("coordinates must be present together");
        }
        if (latitude != null && (latitude.compareTo(BigDecimal.valueOf(-90)) < 0
                || latitude.compareTo(BigDecimal.valueOf(90)) > 0
                || longitude.compareTo(BigDecimal.valueOf(-180)) < 0
                || longitude.compareTo(BigDecimal.valueOf(180)) > 0)) {
            throw new IllegalArgumentException("coordinates are outside the earth bounds");
        }
        if (status == CatalogPlaceStatus.ACTIVE && canonicalPlaceId != null) {
            throw new IllegalArgumentException("an active place cannot have a canonical target");
        }
        if (status == CatalogPlaceStatus.DEPRECATED
                && (canonicalPlaceId == null || canonicalPlaceId.equals(id))) {
            throw new IllegalArgumentException("a deprecated place must name a different canonical target");
        }
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
    }

    public boolean isCanonical() {
        return status == CatalogPlaceStatus.ACTIVE;
    }

    public java.util.UUID resolvedPlaceId() {
        return canonicalPlaceId == null ? id : canonicalPlaceId;
    }

    private static String requiredText(String field, String value, int maximum) {
        Objects.requireNonNull(value, field);
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length()) > maximum) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return trimmed;
    }
}
