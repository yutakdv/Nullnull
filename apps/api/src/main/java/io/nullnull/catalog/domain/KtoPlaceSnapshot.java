package io.nullnull.catalog.domain;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Immutable, normalized detailCommon2 evidence. Raw KTO bodies and image fields are intentionally absent. */
public record KtoPlaceSnapshot(
        UUID id,
        long sourceRegistryVersion,
        UUID collectorRunId,
        String contentId,
        String contentTypeId,
        String title,
        String categoryCode,
        String areaCode,
        String sigunguCode,
        String address,
        BigDecimal latitude,
        BigDecimal longitude,
        String payloadHash,
        Instant fetchedAt,
        Instant staleAt) {

    public static final String SOURCE_CODE = "KTO_KOR_SERVICE_2";
    private static final Pattern IDENTIFIER = Pattern.compile("[1-9][0-9]{0,29}");
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_:-]{1,30}");
    private static final Pattern AREA_CODE = Pattern.compile("[A-Za-z0-9_:-]{1,20}");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

    public KtoPlaceSnapshot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(collectorRunId, "collectorRunId");
        contentId = identifier(contentId, "contentId");
        contentTypeId = identifier(contentTypeId, "contentTypeId");
        title = text(title, "title", 300, true);
        categoryCode = code(categoryCode, "categoryCode", CODE);
        areaCode = code(areaCode, "areaCode", AREA_CODE);
        sigunguCode = code(sigunguCode, "sigunguCode", AREA_CODE);
        address = text(address, "address", 500, false);
        Objects.requireNonNull(payloadHash, "payloadHash");
        Objects.requireNonNull(fetchedAt, "fetchedAt");
        Objects.requireNonNull(staleAt, "staleAt");
        if (sourceRegistryVersion < 1 || !HASH.matcher(payloadHash).matches()) {
            throw new IllegalArgumentException("KTO snapshot provenance is invalid");
        }
        if ((latitude == null) != (longitude == null)) {
            throw new IllegalArgumentException("KTO coordinates must be present together");
        }
        if (latitude != null && (latitude.compareTo(BigDecimal.valueOf(-90)) < 0
                || latitude.compareTo(BigDecimal.valueOf(90)) > 0
                || longitude.compareTo(BigDecimal.valueOf(-180)) < 0
                || longitude.compareTo(BigDecimal.valueOf(180)) > 0)) {
            throw new IllegalArgumentException("KTO coordinates are outside the earth bounds");
        }
        if (!staleAt.isAfter(fetchedAt)) {
            throw new IllegalArgumentException("KTO snapshot staleAt must be after fetchedAt");
        }
    }

    public static KtoPlaceSnapshot accepted(long sourceRegistryVersion, UUID collectorRunId,
            String contentId, String contentTypeId, String title, String categoryCode, String areaCode,
            String sigunguCode, String address, BigDecimal latitude, BigDecimal longitude,
            Instant fetchedAt, Duration staleAfter) {
        Objects.requireNonNull(staleAfter, "staleAfter");
        if (staleAfter.isZero() || staleAfter.isNegative()) {
            throw new IllegalArgumentException("KTO snapshot staleAfter must be positive");
        }
        String hash = hash(contentId, contentTypeId, title, categoryCode, areaCode, sigunguCode, address,
                latitude, longitude);
        return new KtoPlaceSnapshot(UUID.randomUUID(), sourceRegistryVersion, collectorRunId, contentId,
                contentTypeId, title, categoryCode, areaCode, sigunguCode, address, latitude, longitude,
                hash, fetchedAt, fetchedAt.plus(staleAfter));
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
        if (value == null) {
            if (required) {
                throw new IllegalArgumentException(field + " is required");
            }
            return null;
        }
        String trimmed = value.trim();
        if ((required && trimmed.isEmpty()) || trimmed.length() > maximum) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String code(String value, String field, Pattern pattern) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (!pattern.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return trimmed;
    }

    private static String hash(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                digest.update((value == null ? "\u0000" : value).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0x1f);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is required", failure);
        }
    }

    private static String hash(String contentId, String contentTypeId, String title, String categoryCode,
            String areaCode, String sigunguCode, String address, BigDecimal latitude, BigDecimal longitude) {
        return hash(contentId, contentTypeId, title, categoryCode, areaCode, sigunguCode, address,
                decimal(latitude), decimal(longitude));
    }

    private static String decimal(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }
}
