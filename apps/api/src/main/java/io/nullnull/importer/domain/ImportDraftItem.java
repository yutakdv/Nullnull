package io.nullnull.importer.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;
import java.util.UUID;

/**
 * One parsed line of the itinerary.
 *
 * <p>{@code placeId} is null while the line names something the parser matched to no canonical place;
 * {@code date} is null while it belongs to no day yet. Neither is filled in on the traveller's behalf
 * (AGENTS.md 원칙 3), and an item missing either cannot become a TripItem - confirm says so rather
 * than choosing a value.
 *
 * <p>{@code originalLabel} is the short token the parser matched, never the surrounding text: the
 * contract says so in words and BA-060-T1 is where that is checked, because no schema can.
 *
 * <p>There is no {@code constraints} field. The contract has one, described as "independently parsed
 * locks", and nothing parses a lock yet - a stored field with no producer would read as a capability
 * that exists. It arrives with the parser that fills it.
 */
public record ImportDraftItem(String clientKey, UUID placeId, String originalLabel, LocalDate date,
        LocalTime startTime, int position, BigDecimal confidence) {

    public static final int MAX_CLIENT_KEY = 64;
    public static final int MAX_ORIGINAL_LABEL = 120;

    public ImportDraftItem {
        Objects.requireNonNull(clientKey, "clientKey");
        Objects.requireNonNull(confidence, "confidence");
        if (clientKey.isBlank() || clientKey.length() > MAX_CLIENT_KEY) {
            throw new IllegalArgumentException("clientKey must be 1.." + MAX_CLIENT_KEY + " characters");
        }
        if (originalLabel != null && originalLabel.length() > MAX_ORIGINAL_LABEL) {
            throw new IllegalArgumentException("originalLabel must be a short token");
        }
        if (position < 0) {
            throw new IllegalArgumentException("position must not be negative");
        }
        if (confidence.signum() < 0 || confidence.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
    }
}
