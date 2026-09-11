package io.nullnull.catalog.application;

import io.nullnull.shared.problem.ApiException;
import io.nullnull.shared.problem.ProblemCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** A normalized, privacy-safe search request. The free-form term is never put in a cursor. */
public record CatalogPlaceSearchRequest(String query, String locale, String regionCode, String cursor, int limit) {

    public static final int DEFAULT_LIMIT = 20;
    private static final String DEFAULT_LOCALE = "ko-KR";
    private static final Pattern LOCALE = Pattern.compile("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*");

    public CatalogPlaceSearchRequest {
        query = required("query", query, 100);
        locale = normalizedLocale(locale == null ? DEFAULT_LOCALE : locale);
        regionCode = optional("regionCode", regionCode, 100);
        cursor = optional("cursor", cursor, 500);
        if (limit < 1 || limit > 50) {
            throw invalid();
        }
    }

    public static CatalogPlaceSearchRequest of(String query, String locale, String regionCode, String cursor,
            Integer limit) {
        return new CatalogPlaceSearchRequest(query, locale, regionCode, cursor,
                limit == null ? DEFAULT_LIMIT : limit);
    }

    public String language() {
        int separator = locale.indexOf('-');
        return separator < 0 ? locale : locale.substring(0, separator);
    }

    /** Stable endpoint/filter binding without exposing the search text in an opaque cursor. */
    public String cursorContext() {
        return "places:" + digest(query + "\u001f" + locale + "\u001f" + (regionCode == null ? "" : regionCode));
    }

    private static String normalizedLocale(String value) {
        String trimmed = required("locale", value, 35);
        if (!LOCALE.matcher(trimmed).matches()) {
            throw invalid();
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private static String required(String field, String value, int maximum) {
        if (value == null) {
            throw invalid();
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length()) > maximum) {
            throw invalid();
        }
        return trimmed;
    }

    private static String optional(String field, String value, int maximum) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.codePointCount(0, trimmed.length()) > maximum) {
            throw invalid();
        }
        return trimmed;
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private static ApiException invalid() {
        return new ApiException(ProblemCode.INVALID_REQUEST,
                "The place search request is malformed or outside the supported bounds.");
    }
}
