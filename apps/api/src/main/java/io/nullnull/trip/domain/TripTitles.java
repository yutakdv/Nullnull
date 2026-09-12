package io.nullnull.trip.domain;

import java.util.Locale;

/**
 * The default title createTrip gives a trip when the caller omits one.
 *
 * <p>docs/api/openapi.yaml: "the server creates a deterministic locale-aware default (새 여행/New
 * trip); it never asks an LLM to invent one." Deterministic matters twice over - the same request
 * must produce the same trip on a retry through the idempotency guard, and a title is user-visible
 * copy that a generator has no business inventing (invariant 9).
 */
public final class TripTitles {

    public static final String KOREAN_DEFAULT = "새 여행";
    public static final String ENGLISH_DEFAULT = "New trip";

    private TripTitles() {
    }

    /**
     * @param locale the owner's locale tag, for example {@code ko-KR}. P0 really supports Korean and
     *               English; every other tag, and a missing one, falls back to English rather than
     *               to a language the product does not ship (Japanese and Chinese are disabled).
     */
    public static String defaultTitle(String locale) {
        if (locale == null || locale.isBlank()) {
            return ENGLISH_DEFAULT;
        }
        // Language subtag only: ko, ko-KR and ko-Hang-KR are all Korean, and matching the whole tag
        // would quietly give ko-KR a Korean title and plain ko an English one.
        String language = Locale.forLanguageTag(locale).getLanguage();
        return "ko".equals(language) ? KOREAN_DEFAULT : ENGLISH_DEFAULT;
    }

    /**
     * The title to persist: a caller-supplied one is trimmed and kept, an absent or blank one becomes
     * the locale default. Blank is treated as absent because the schema's minLength 1 counts a space.
     */
    public static String resolve(String submitted, String locale) {
        if (submitted == null || submitted.isBlank()) {
            return defaultTitle(locale);
        }
        String trimmed = submitted.trim();
        if (trimmed.length() > 100) {
            throw new TripValidationException("title", "Size", "title must be at most 100 characters");
        }
        return trimmed;
    }
}
