package io.nullnull.trip.domain;

import java.util.List;
import java.util.Objects;

/**
 * A trip input the domain refuses. Pure on purpose: {@code trip.domain} cannot depend on the shared
 * problem types (they live outside it and carry Spring's HttpStatus), so this names the offending
 * field and the application layer turns it into VALIDATION_FAILED with fieldErrors.
 */
public class TripValidationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient List<FieldViolation> violations;

    public TripValidationException(String field, String code, String message) {
        this(List.of(new FieldViolation(field, code, message)));
    }

    public TripValidationException(List<FieldViolation> violations) {
        super(summary(violations));
        this.violations = List.copyOf(violations);
    }

    public List<FieldViolation> violations() {
        return violations;
    }

    private static String summary(List<FieldViolation> violations) {
        if (violations == null || violations.isEmpty()) {
            throw new IllegalArgumentException("a validation failure needs at least one violation");
        }
        // The message is the Problem detail, which docs/api/README.md keeps generic: the per-field
        // answer travels in fieldErrors, and the message must not repeat a rejected value.
        return "One or more fields are invalid.";
    }

    /** One entry of {@code Problem.fieldErrors}: a pointer, a stable code, and safe text. */
    public record FieldViolation(String field, String code, String message) {
        public FieldViolation {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
        }
    }
}
