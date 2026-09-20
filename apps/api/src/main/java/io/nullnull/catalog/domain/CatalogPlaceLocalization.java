package io.nullnull.catalog.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** One source-backed locale projection for an active canonical place. */
public record CatalogPlaceLocalization(
        UUID id,
        UUID placeId,
        String locale,
        String name,
        String shortDescription,
        String address,
        Instant updatedAt,
        Provenance provenance) {

    /**
     * Who published this text, under which reviewed revision, in which language, and when it was
     * seen (BA-086). Null on the record means the origin is unknown, which is what the ko-KR rows
     * written before V047 are - see that migration for why they are not backfilled.
     *
     * <p>A nested record is how the all-or-nothing rule reaches Java. V047 spells it
     * {@code num_nonnulls(...) IN (0, 4)}; here the four values arrive together or not at all, so a
     * half-filled provenance has no representation to construct rather than a check to fail.
     */
    public record Provenance(String sourceCode, long sourceRegistryVersion, String sourceLocale,
            Instant observedAt) {

        private static final Pattern SOURCE_CODE = Pattern.compile("[A-Z][A-Z0-9_]{2,63}");

        public Provenance {
            Objects.requireNonNull(sourceCode, "sourceCode");
            sourceCode = sourceCode.trim();
            if (!SOURCE_CODE.matcher(sourceCode).matches()) {
                throw new IllegalArgumentException("sourceCode is invalid");
            }
            if (sourceRegistryVersion < 1) {
                throw new IllegalArgumentException("sourceRegistryVersion must be positive");
            }
            sourceLocale = requiredText("sourceLocale", sourceLocale, 2, 35);
            Objects.requireNonNull(observedAt, "observedAt");
        }
    }

    public CatalogPlaceLocalization {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(placeId, "placeId");
        locale = requiredText("locale", locale, 2, 35);
        name = requiredText("name", name, 1, 200);
        shortDescription = optionalText("shortDescription", shortDescription, 10_000);
        address = optionalText("address", address, 500);
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    private static String requiredText(String field, String value, int minimum, int maximum) {
        Objects.requireNonNull(value, field);
        String trimmed = value.trim();
        int length = trimmed.codePointCount(0, trimmed.length());
        if (length < minimum || length > maximum) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return trimmed;
    }

    private static String optionalText(String field, String value, int maximum) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length()) > maximum) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return trimmed;
    }
}
