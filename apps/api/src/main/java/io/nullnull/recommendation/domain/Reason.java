package io.nullnull.recommendation.domain;

import java.util.Objects;

/**
 * Internal rejection or uncertainty reason. Distinct from public
 * {@code OptimizationFailure.code} and public comparison reason codes; the projection layer
 * maps between them explicitly (§3.2).
 *
 * @param code   stable internal code, UPPER_SNAKE_CASE
 * @param detail short operator-facing text without user input, place names or owner ids
 */
public record Reason(String code, String detail) {

    public Reason {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(detail, "detail");
        if (code.isBlank() || !code.equals(code.toUpperCase()) || code.length() > 64) {
            throw new IllegalArgumentException("reason code must be non-blank UPPER_SNAKE_CASE <= 64 chars");
        }
    }

    public static Reason of(String code, String detail) {
        return new Reason(code, detail);
    }
}
