package io.nullnull.importer.application;

import io.nullnull.importer.domain.ImportDraft;
import io.nullnull.importer.domain.ImportDraftItem;
import io.nullnull.shared.problem.ApiException;
import io.nullnull.shared.problem.ProblemCode;
import io.nullnull.trip.application.CreateTripCommand;
import io.nullnull.trip.domain.TripInterest;
import io.nullnull.trip.domain.TripItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A validated confirmTripImport body: the trip metadata the draft does not carry.
 *
 * <p>The itinerary comes from the draft and the title, planning level and interests come from the
 * request, because the first was parsed and the last three are the traveller's own answers on the
 * confirmation screen. Nothing here can change what was parsed - a correction is a remap, which has
 * an ETag and bumps the version.
 */
public record ConfirmImportCommand(String title, String planningLevel, List<TripInterest> interests) {

    public ConfirmImportCommand {
        Objects.requireNonNull(title, "title");
        interests = interests == null ? List.of() : List.copyOf(interests);
    }

    /**
     * The create command this draft and this request make together.
     *
     * <p>Every item must have resolved a place and a day, which {@link ImportDraft.Status#READY}
     * already requires - this is the second reading of the same rule, under the row lock, and it is
     * the one that runs before a trip is written. It states which entry is at fault rather than
     * failing on a null somewhere inside TripItem's constructor.
     */
    public CreateTripCommand toCreateTrip(ImportDraft draft, String ownerLocale) {
        List<TripItem> items = new ArrayList<>(draft.content().items().size());
        for (ImportDraftItem item : draft.content().items()) {
            if (item.placeId() == null || item.date() == null) {
                // The clientKey is the client's own identifier for the entry, not parsed text.
                throw new ApiException(ProblemCode.VALIDATION_FAILED,
                        "This draft still has an entry with no place or no date: " + item.clientKey());
            }
            items.add(new TripItem(UUID.randomUUID(), item.placeId(), item.date(), item.position(),
                    item.startTime(), null, null, List.of()));
        }
        return CreateTripCommand.of(title, draft.content().startDate(), draft.content().endDate(),
                draft.content().timezone(), planningLevel, interests, List.copyOf(items), ownerLocale);
    }
}
