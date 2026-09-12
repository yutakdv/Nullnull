package io.nullnull.trip.domain;

/**
 * A trip candidate's state.
 *
 * <p>ACTIVE and SCHEDULED are both "the user wants this place"; SCHEDULED additionally means it now
 * has a slot. DISMISSED is the record that they said no, kept rather than deleted so a later re-save
 * is a new decision rather than an undo of something invisible.
 */
public enum CandidateStatus {
    ACTIVE,
    SCHEDULED,
    DISMISSED;

    public static CandidateStatus of(String value) {
        for (CandidateStatus status : values()) {
            if (status.name().equals(value)) {
                return status;
            }
        }
        throw new TripValidationException("status", "Enum",
                "status must be ACTIVE, SCHEDULED or DISMISSED");
    }
}
