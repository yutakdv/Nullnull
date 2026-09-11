package io.nullnull.catalog.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One source-backed locale projection for an active canonical place. */
public record CatalogPlaceLocalization(
        UUID id,
        UUID placeId,
        String locale,
        String name,
        String shortDescription,
        String address,
        Instant updatedAt) {

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
