package io.nullnull.catalog.application;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One EngService2 detailCommon2 record, reduced to what BA-086 may use: the English name and address
 * that become a localization, and the facts the link rule compares against the canonical place.
 *
 * <p>The facts are read, never stored. Coordinates, codes and dates stay the canonical place's, so an
 * English record can decide whether its text is shown but cannot change what the place is (BA-086-T1).
 * The overview is not kept at all: the Korean side stores no description, and an English-only
 * description would be facts the Korean record does not carry.
 */
public record KtoEngRecord(String contentId, String contentTypeId, String title, String address,
        BigDecimal latitude, BigDecimal longitude, String classification, String regionCode,
        String sigunguCode, String payloadHash) {

    /** The registry code of the English dataset (V050). */
    public static final String SOURCE_CODE = "KTO_ENG_SERVICE";

    /** place_localizations.name is varchar(200); a longer title is rejected, not cut. */
    public static final int TITLE_LIMIT = 200;
    /** place_localizations.address is varchar(500). */
    public static final int ADDRESS_LIMIT = 500;

    private static final Pattern IDENTIFIER = Pattern.compile("[1-9][0-9]{0,29}");
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_:-]{1,30}");
    private static final Pattern AREA_CODE = Pattern.compile("[A-Za-z0-9_:-]{1,20}");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

    public KtoEngRecord {
        contentId = identifier(contentId, "contentId");
        contentTypeId = identifier(contentTypeId, "contentTypeId");
        title = text(title, "title", TITLE_LIMIT, true);
        address = text(address, "address", ADDRESS_LIMIT, false);
        classification = code(classification, "classification", CODE);
        regionCode = code(regionCode, "regionCode", AREA_CODE);
        sigunguCode = code(sigunguCode, "sigunguCode", AREA_CODE);
        if ((latitude == null) != (longitude == null)) {
            throw new IllegalArgumentException("coordinates travel together");
        }
        Objects.requireNonNull(payloadHash, "payloadHash");
        if (!HASH.matcher(payloadHash).matches()) {
            throw new IllegalArgumentException("payloadHash is not a SHA-256 hex digest");
        }
    }

    /** Builds the record with the hash of its normalized fields, the way KtoPlaceSnapshot does. */
    public static KtoEngRecord of(String contentId, String contentTypeId, String title, String address,
            BigDecimal latitude, BigDecimal longitude, String classification, String regionCode,
            String sigunguCode) {
        return new KtoEngRecord(contentId, contentTypeId, title, address, latitude, longitude, classification,
                regionCode, sigunguCode, hash(contentId, contentTypeId, title, address, decimal(latitude),
                        decimal(longitude), classification, regionCode, sigunguCode));
    }

    private static String identifier(String value, String field) {
        Objects.requireNonNull(value, field);
        String trimmed = value.trim();
        if (!IDENTIFIER.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(field + " is not a KTO identifier");
        }
        return trimmed;
    }

    private static String text(String value, String field, int maximum, boolean required) {
        String trimmed = value == null ? "" : value.trim();
        if ((required && trimmed.isEmpty()) || trimmed.length() > maximum) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String code(String value, String field, Pattern pattern) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (!pattern.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return trimmed;
    }

    private static String decimal(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private static String hash(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                String normalized = value == null ? null : value.trim();
                digest.update((normalized == null || normalized.isEmpty() ? "\u0000" : normalized)
                        .getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0x1f);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is required", failure);
        }
    }
}
