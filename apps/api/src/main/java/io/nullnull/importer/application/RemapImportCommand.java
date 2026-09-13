package io.nullnull.importer.application;

import io.nullnull.importer.domain.ImportDraftContent;
import io.nullnull.importer.domain.ImportDraftItem;
import io.nullnull.shared.problem.ApiException;
import io.nullnull.shared.problem.ProblemCode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A validated remapTripImport body.
 *
 * <p>Every field but {@code clientKey} is optional and an absent one leaves the item's value alone.
 * That reading is forced by the contract rather than chosen: all of them are nullable, so "null
 * means clear it" would make the same value mean opposite things depending on the field, and a
 * generated TypeScript client cannot tell an explicit null from an absent key anyway (#223 records
 * both arguments). The consequence - that nothing here can clear a value or dismiss a token - is
 * that issue's subject and is deliberately not worked around.
 *
 * <p>An update naming a clientKey the draft does not have is refused rather than ignored. Ignoring it
 * would answer 200 with a draft that does not contain the correction the caller just made, and the
 * caller has no way to tell that from success.
 */
public record RemapImportCommand(List<Update> updates) {

    /** The contract's RemapImportRequest.updates bounds. */
    public static final int MIN_UPDATES = 1;
    public static final int MAX_UPDATES = 100;

    public record Update(String clientKey, UUID placeId, LocalDate date, LocalTime startTime,
            Integer position) {
    }

    public RemapImportCommand {
        updates = List.copyOf(Objects.requireNonNull(updates, "updates"));
        if (updates.size() < MIN_UPDATES || updates.size() > MAX_UPDATES) {
            throw invalid("updates must hold between " + MIN_UPDATES + " and " + MAX_UPDATES + " entries.");
        }
        Set<String> keys = new HashSet<>();
        for (Update update : updates) {
            if (update.clientKey() == null || update.clientKey().isBlank()) {
                throw invalid("Every update must name the clientKey it corrects.");
            }
            if (!keys.add(update.clientKey())) {
                throw invalid("Two updates name the same clientKey, so the intended result is ambiguous.");
            }
            if (update.position() != null && update.position() < 0) {
                throw invalid("position must not be negative.");
            }
        }
    }

    /**
     * The corrected content.
     *
     * <p>Nothing here checks that the result would make a valid trip - that two items do not share a
     * slot, that every date falls inside the range. A draft is work in progress and may be
     * inconsistent halfway through a correction; {@code CreateTripCommand} is what refuses an
     * inconsistent one, at confirm, where it would otherwise become a trip.
     */
    public ImportDraftContent applyTo(ImportDraftContent content) {
        List<ImportDraftItem> items = new ArrayList<>(content.items());
        for (Update update : updates) {
            int at = indexOf(items, update.clientKey());
            if (at < 0) {
                throw new ApiException(ProblemCode.VALIDATION_FAILED,
                        "An update names an entry this draft does not have.");
            }
            ImportDraftItem item = items.get(at);
            items.set(at, new ImportDraftItem(item.clientKey(),
                    update.placeId() == null ? item.placeId() : update.placeId(),
                    item.originalLabel(),
                    update.date() == null ? item.date() : update.date(),
                    update.startTime() == null ? item.startTime() : update.startTime(),
                    update.position() == null ? item.position() : update.position(),
                    item.confidence()));
        }
        return new ImportDraftContent(content.title(), content.startDate(), content.endDate(),
                content.timezone(), List.copyOf(items));
    }

    private static int indexOf(List<ImportDraftItem> items, String clientKey) {
        for (int at = 0; at < items.size(); at++) {
            if (items.get(at).clientKey().equals(clientKey)) {
                return at;
            }
        }
        return -1;
    }

    private static ApiException invalid(String detail) {
        return new ApiException(ProblemCode.INVALID_REQUEST, detail);
    }
}
