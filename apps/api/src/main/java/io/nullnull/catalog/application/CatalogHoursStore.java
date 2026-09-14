package io.nullnull.catalog.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;
import java.util.UUID;

/**
 * The write side of curated opening hours, used only by the curation script.
 *
 * <p>Separate from {@link CatalogHoursQuery} because the two have different callers and different
 * rules: the read side is what BA-042 hydrates from and filters superseded and stale evidence, while
 * this one is what an operator's reviewed plan file lands through.
 *
 * <p>Nothing here validates a place. V027 and V025's triggers refuse a reading attached to anything
 * but an active canonical place, and the importer runs in one transaction, so a bad place id ends as
 * a rejected run with nothing written. A check here would be a second guard over that same fact, and
 * then neither would be provable on its own.
 */
public interface CatalogHoursStore {

    /** The place's reading that has not been superseded, if it has one. */
    Optional<UUID> currentObservationId(UUID placeId);

    /**
     * Retires a reading without deleting it. A superseded row keeps standing as the evidence that was
     * current at the time, which is why the partial unique index is on {@code superseded_at IS NULL}
     * rather than on the place.
     */
    void supersede(UUID observationId, Instant at);

    UUID recordObservation(UUID placeId, String outcome, Instant observedAt, String evidenceUrl,
            Instant staleAt, Instant recordedAt);

    void recordWindow(UUID observationId, LocalDate date, String state, LocalTime opensAt, LocalTime closesAt);
}
