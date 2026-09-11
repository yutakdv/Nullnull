package io.nullnull.catalog.application;

import java.util.Objects;
import java.util.regex.Pattern;

/** Stable KTO identifiers only; raw search text never becomes a provider request. */
public record KtoPlaceRequest(String contentId, String contentTypeId) {

    private static final Pattern ID = Pattern.compile("[1-9][0-9]{0,29}");

    public KtoPlaceRequest {
        contentId = normalized(contentId, "contentId");
        contentTypeId = normalized(contentTypeId, "contentTypeId");
    }

    public String cacheKey() {
        return contentId + ":" + contentTypeId;
    }

    private static String normalized(String value, String field) {
        Objects.requireNonNull(value, field);
        String trimmed = value.trim();
        if (!ID.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(field + " must be a positive KTO identifier");
        }
        return trimmed;
    }
}
