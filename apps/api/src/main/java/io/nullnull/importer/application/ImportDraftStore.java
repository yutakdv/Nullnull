package io.nullnull.importer.application;

import io.nullnull.importer.domain.ImportDraft;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Storage boundary for import drafts.
 *
 * <p>Every read takes the owner. A draft id alone never identifies a row the caller may see
 * (invariant 11), and making the owner a parameter rather than a filter the caller remembers to
 * apply is what keeps that true for the next reader as well.
 */
public interface ImportDraftStore {

    /** The draft as it stands, for a caller that is only going to answer with it. */
    Optional<ImportDraft> find(UUID ownerId, UUID draftId);

    /**
     * The draft, locked for the rest of the caller's transaction.
     *
     * <p>This is what makes two concurrent confirms produce one trip: the second waits here, and by
     * the time it reads the row the first has already written CONFIRMED. Without the lock both would
     * read NEEDS_REVIEW, both would create a trip, and only the {@code confirmed_trip_id} unique
     * index would stop the second - as a driver error, after a trip had already been written.
     */
    Optional<ImportDraft> findForUpdate(UUID ownerId, UUID draftId);

    void insert(ImportDraft draft);

    /**
     * Writes a remapped draft at {@code draft.version()}, which the caller has already raised.
     *
     * @return true when the row still held the previous version. A false is a concurrent remap that
     *         won, not a missing draft: the caller has already established that the row exists.
     */
    boolean saveRemap(ImportDraft draft, long previousVersion);

    /** Records the trip a draft became. Refused by the table if the draft already has one. */
    void markConfirmed(UUID draftId, UUID tripId, Instant confirmedAt);
}
