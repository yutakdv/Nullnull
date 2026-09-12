package io.nullnull.crowd.application;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A canonical KTO place mapping for the reviewed concentration forecast operation. These are
 * source identifiers, never caller-controlled search values.
 *
 * <p>{@code areaCode} and {@code sigunguCode} are stored exactly as detailCommon2 returned them
 * ({@code lDongRegnCd} and {@code lDongSignguCd}), because provenance means keeping the provider's
 * own values rather than a shape convenient for one request. The concentration forecast operation
 * does NOT take them in that form - see {@link #signguRequestCode()}.
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

    /**
     * The value {@code tatsCnctrRatedList} wants for {@code signguCd}: the sido code and the sigungu
     * code JOINED, not the sigungu code on its own. Seoul Jongno-gu is {@code 11} + {@code 110} =
     * {@code 11110}.
     *
     * <p>This is a fact about the provider's code system, which is why it lives here and not in URI
     * assembly. It was established by a real call (#109): {@code areaCd=11&signguCd=110} - the raw
     * stored pair - answers {@code resultCode 0000} with {@code totalCount 0}, and so does
     * {@code areaCd=11110&signguCd=11110}, while {@code areaCd=11&signguCd=11110} answers with
     * rows. A zero count is not an error here, so nothing upstream would have reported the mismatch;
     * the collector would simply have recorded "no coverage" forever.
     */
    public String signguRequestCode() {
        return areaCode + sigunguCode;
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
