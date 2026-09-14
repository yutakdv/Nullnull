package io.nullnull.importer.application;

import io.nullnull.shared.problem.ApiException;
import io.nullnull.shared.problem.ProblemCode;
import java.util.Objects;

/**
 * A validated parseTripImport body.
 *
 * <p>{@code rawText} is held for the length of one request and is never stored, logged or echoed. The
 * bound is the contract's and is checked here, before anything reads the text: a paste over the limit
 * is refused rather than truncated, because a truncated itinerary parses into a shorter trip and
 * nothing in the answer would say that half of it was dropped.
 */
public record ParseImportCommand(String rawText, String locale, String timezone) {

    /** The contract's ParseImportRequest.rawText bounds. */
    public static final int MIN_RAW_TEXT = 1;
    public static final int MAX_RAW_TEXT = 20000;

    private static final String DEFAULT_LOCALE = "ko-KR";
    private static final String DEFAULT_TIMEZONE = "Asia/Seoul";

    public ParseImportCommand {
        Objects.requireNonNull(rawText, "rawText");
        // Code points, not chars: the bound is a count of characters a person typed, and a surrogate
        // pair is one of those. Measuring in UTF-16 units would refuse a shorter paste than the
        // contract promises to accept.
        int length = rawText.codePointCount(0, rawText.length());
        if (length < MIN_RAW_TEXT || length > MAX_RAW_TEXT) {
            // The message names the bound and never the value, so no part of the paste reaches a
            // Problem body (docs/api/README.md, and BA-060-T2).
            throw new ApiException(ProblemCode.VALIDATION_FAILED,
                    "rawText must be between " + MIN_RAW_TEXT + " and " + MAX_RAW_TEXT + " characters.");
        }
        locale = locale == null || locale.isBlank() ? DEFAULT_LOCALE : locale;
        timezone = timezone == null || timezone.isBlank() ? DEFAULT_TIMEZONE : timezone;
    }
}
