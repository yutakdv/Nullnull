package io.nullnull.crowd.application;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A canonical KTO place mapping for the reviewed concentration forecast operation. These are
 * source identifiers, never caller-controlled search values.
 */
public record KtoForecastRequest(UUID placeId, String areaCode, String sigunguCode, String touristSiteName) {

    private static final Pattern KTO_CODE = Pattern.compile("[1-9][0-9]{0,9}");

    public KtoForecastRequest {
        Objects.requireNonNull(placeId, "placeId");
        areaCode = code(areaCode, "areaCode");
        sigunguCode = code(sigunguCode, "sigunguCode");
        touristSiteName = text(touristSiteName, "touristSiteName");
    }

    /** Stable, non-sensitive key used only for same-place single-flight coalescing. */
    public String cacheKey() {
        return placeId.toString();
    }

    private static String code(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (!KTO_CODE.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " is not a KTO area identifier");
        }
        return normalized;
    }

    private static String text(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > 300
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }
}
