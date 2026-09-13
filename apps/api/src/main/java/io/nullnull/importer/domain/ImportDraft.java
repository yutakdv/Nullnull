package io.nullnull.importer.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A parsed itinerary waiting for its traveller to confirm it - and nothing of the paste itself.
 *
 * <p>V028 has no {@code raw_text} column and this record has no field for one, which is the same
 * decision expressed twice: storing the paste has to be a deliberate act rather than the default.
 *
 * <p>{@code status} and {@code confirmedTripId} are one fact in two columns and the table states the
 * biconditional; this record keeps them together so a caller cannot construct half of it.
 */
public record ImportDraft(UUID id, UUID ownerId, Status status, long version, ImportDraftContent content,
        List<UnresolvedToken> unresolved, UUID confirmedTripId, Instant confirmedAt, Instant expiresAt,
        Instant createdAt) {

    public enum Status { NEEDS_REVIEW, READY, CONFIRMED, EXPIRED }

    public ImportDraft {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(createdAt, "createdAt");
        unresolved = List.copyOf(Objects.requireNonNull(unresolved, "unresolved"));
        if (version < 1) {
            throw new IllegalArgumentException("version must be at least 1");
        }
        if ((status == Status.CONFIRMED) != (confirmedTripId != null && confirmedAt != null)) {
            throw new IllegalArgumentException("CONFIRMED and having a trip are the same fact");
        }
    }

    /**
     * Past its 24-hour window. The boundary instant itself counts as expired, matching the window
     * every other dated record in this schema uses: a row stands while {@code now < expiresAt}.
     */
    public boolean expiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }

    /**
     * READY is "nothing is left for the traveller to answer", derived rather than declared: a remap
     * that fills in the last missing mapping has to make the draft READY without anyone saying so,
     * and one that empties a field again has to take it back.
     *
     * <p>Three conditions, and only the first is written down in the contract. #223 states that READY
     * requires zero unresolved tokens; the other two - that the draft has a date range, and that
     * every item has a place and a day - are here because READY has exactly one consumer, and confirm
     * cannot create a trip or build a TripItem without them.
     * A draft that answered READY while confirm would reject it is telling the client something
     * untrue, and the client has no other way to find out.
     */
    public static Status statusFor(ImportDraftContent content, List<UnresolvedToken> unresolved) {
        boolean everyItemPlaced = content.items().stream()
                .allMatch(item -> item.placeId() != null && item.date() != null);
        return unresolved.isEmpty() && content.hasRange() && everyItemPlaced
                ? Status.READY
                : Status.NEEDS_REVIEW;
    }
}
