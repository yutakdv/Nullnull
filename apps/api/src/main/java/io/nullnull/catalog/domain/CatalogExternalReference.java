package io.nullnull.catalog.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Stable provider identity; a source/content/type tuple belongs to one active canonical POI. */
public record CatalogExternalReference(
        UUID id,
        UUID placeId,
        String sourceCode,
        long sourceRegistryVersion,
        String externalId,
        String externalType,
        Instant verifiedAt) {

    private static final Pattern SOURCE_CODE = Pattern.compile("[A-Z][A-Z0-9_]{2,63}");

    public CatalogExternalReference {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(placeId, "placeId");
        sourceCode = sourceCode(sourceCode);
        if (sourceRegistryVersion < 1) {
            throw new IllegalArgumentException("sourceRegistryVersion must be positive");
        }
        externalId = requiredText("externalId", externalId, 200);
        externalType = requiredText("externalType", externalType, 100);
        Objects.requireNonNull(verifiedAt, "verifiedAt");
    }

    private static String sourceCode(String value) {
        Objects.requireNonNull(value, "sourceCode");
        String trimmed = value.trim();
        if (!SOURCE_CODE.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("sourceCode is invalid");
        }
        return trimmed;
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
