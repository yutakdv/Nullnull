package io.nullnull.trip.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A place saved to a trip with no date and no time.
 *
 * <p>Distinct from a TripItem and from a SavedPost (invariant 1). Creating one changes no schedule
 * and raises no trip version (invariant 2) - nothing in this type or the code that writes it
 * touches a trip row.
 */
public record TripCandidate(UUID id, UUID tripId, UUID placeId, CandidateStatus status,
        UUID scheduledTripItemId, String note, List<CandidateSource> sources, Instant createdAt,
        Instant updatedAt) {

    public static final int MAX_NOTE_LENGTH = 500;

    public TripCandidate {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tripId, "tripId");
        Objects.requireNonNull(placeId, "placeId");
        Objects.requireNonNull(status, "status");
        if ((status == CandidateStatus.SCHEDULED) != (scheduledTripItemId != null)) {
            throw new IllegalStateException(
                    "scheduledTripItemId exists exactly while the candidate is SCHEDULED");
        }
        if (note != null && note.length() > MAX_NOTE_LENGTH) {
            throw new TripValidationException("note", "Size",
                    "note must be at most " + MAX_NOTE_LENGTH + " characters");
        }
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    /**
     * Whether this candidate may be dismissed directly.
     *
     * <p>A SCHEDULED one may not: it is on the itinerary, and removing it from there is a schedule
     * change that goes through the item operations so the trip's version moves with it. Dismissing
     * it here would take the place off the plan while leaving the trip's version saying nothing
     * changed.
     */
    public boolean isDismissable() {
        return status == CandidateStatus.ACTIVE;
    }

    /** One record of where this candidate came from. */
    public record CandidateSource(CandidateSourceType type, UUID postId, Instant createdAt) {
        public CandidateSource {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(createdAt, "createdAt");
            if (type != CandidateSourceType.POST && postId != null) {
                throw new TripValidationException("source.postId", "Unexpected",
                        "only a POST source names a post");
            }
            if (type == CandidateSourceType.POST && postId == null) {
                throw new TripValidationException("source.postId", "NotNull",
                        "a POST source must name its post");
            }
        }
    }
}
