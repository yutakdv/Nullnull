package io.nullnull.trip.application;

import io.nullnull.catalog.application.CatalogPlaceQuery.CatalogPlaceSummary;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * TripDraftPreview, as the contract spells it. Nothing here is stored: it is the answer to one
 * previewTripDraft request, and the client turns each stop into a createTrip seed item.
 */
public record TripDraftPreviewView(String state, Instant evaluatedAt, String policyVersion, List<String> basis,
        List<Day> days, List<String> reasons) {

    public static final String BASIS_DATE_RANGE = "DATE_RANGE";
    public static final String BASIS_OPENING_HOURS_VERIFIED = "OPENING_HOURS_VERIFIED";
    public static final String REASON_POOL_TRUNCATED = "POOL_TRUNCATED";

    public record Day(LocalDate date, List<Stop> stops) { }

    /** {@code hoursState} is OPEN or UNKNOWN; a verified-closed date never holds a stop. */
    public record Stop(CatalogPlaceSummary place, LocalDate date, int position, String hoursState) { }
}
