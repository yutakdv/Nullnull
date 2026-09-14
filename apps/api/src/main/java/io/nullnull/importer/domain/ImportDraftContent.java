package io.nullnull.importer.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * The structured half of a draft: the trip it would become, with no place content and no raw text.
 *
 * <p>Items carry {@code placeId} rather than a place, so this record can be stored as it stands and
 * hydrated behind the catalog publication gate at read time. A stored PlaceSummary would be a second
 * copy of the catalog that nothing keeps in step, and it would serve place content through a gate
 * that had been passed once, at parse time, under whatever the flag said then.
 *
 * <p>{@code startDate} and {@code endDate} are nullable because the contract's DateRange makes them
 * so: a paste that never named a year has no range yet, and inventing one on the traveller's behalf
 * is the thing this whole card refuses. A draft with no range cannot be READY.
 *
 * <p>{@code timezone} is not nullable and does not come from the paste. It is the caller's own
 * {@code ParseImportRequest.timezone}, carried here because a trip cannot be created without one and
 * confirm has no other source for it - the confirm body carries the traveller's answers, not the
 * request context the parse happened in.
 */
public record ImportDraftContent(String title, LocalDate startDate, LocalDate endDate, String timezone,
        List<ImportDraftItem> items) {

    /** The contract's ImportDraft.items cap. */
    public static final int MAX_ITEMS = 100;

    public ImportDraftContent {
        Objects.requireNonNull(timezone, "timezone");
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        if (timezone.isBlank()) {
            throw new IllegalArgumentException("timezone is required");
        }
        if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("endDate must not precede startDate");
        }
        if ((startDate == null) != (endDate == null)) {
            // Half a range is not a range. A draft either parsed dates or did not.
            throw new IllegalArgumentException("a draft holds both range ends or neither");
        }
        if (items.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("a draft holds at most " + MAX_ITEMS + " items");
        }
    }

    public boolean hasRange() {
        return startDate != null;
    }
}
