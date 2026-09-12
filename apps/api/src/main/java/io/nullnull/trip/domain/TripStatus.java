package io.nullnull.trip.domain;

/**
 * {@code TripStatus} in docs/api/openapi.yaml. ERD §11 allows DRAFT -> ACTIVE -> ARCHIVED and only an
 * explicit restore for ARCHIVED -> ACTIVE. Deletion is not a status: it is a hard delete of the owned
 * aggregate, so no value here ever means "deleted".
 */
public enum TripStatus {
    DRAFT,
    ACTIVE,
    ARCHIVED;

    public static TripStatus of(String value) {
        for (TripStatus status : values()) {
            if (status.name().equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("unknown trip status");
    }
}
