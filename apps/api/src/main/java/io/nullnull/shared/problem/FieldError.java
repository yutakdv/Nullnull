package io.nullnull.shared.problem;

import java.util.Objects;

/** Matches {@code FieldError} in docs/api/openapi.yaml. */
public record FieldError(String field, String code, String message) {

    public FieldError {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
    }
}
