package io.nullnull.trip.domain;

/** Who placed a lock. {@code TripConstraint.source} in docs/api/openapi.yaml. */
public enum ConstraintSource {
    USER,
    IMPORT;

    public static ConstraintSource of(String value) {
        for (ConstraintSource source : values()) {
            if (source.name().equals(value)) {
                return source;
            }
        }
        throw new TripValidationException("constraints[].source", "Enum", "source must be USER or IMPORT");
    }
}
