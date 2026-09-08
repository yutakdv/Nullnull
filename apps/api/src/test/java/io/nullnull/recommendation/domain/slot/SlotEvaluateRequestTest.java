package io.nullnull.recommendation.domain.slot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.recommendation.domain.item.ItemProposeRequest;
import io.nullnull.recommendation.domain.item.NeighbourItemIn;
import io.nullnull.recommendation.domain.item.OpeningWindowIn;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every transported collection and number carries an explicit bound, and the trip zone that decides
 * which day each opening window and duplicate date belongs to is declared {@code minLength: 1,
 * maxLength: 64}. A value outside those bounds is a hydration bug on this side, so it fails here
 * rather than coming back from the service as an opaque 422.
 */
@DisplayName("SlotEvaluateRequest transport bounds")
class SlotEvaluateRequestTest {

    static final UUID ID = UUID.fromString("018f3f8e-9b67-7a21-8d31-31d315b93b01");
    static final LocalDate D12 = LocalDate.of(2026, 9, 12);
    static final LocalDate D14 = LocalDate.of(2026, 9, 14);

    @Test
    void aTripZoneOfExactlyTheContractLengthIsAccepted() {
        SlotEvaluateRequest request = zoned("Z".repeat(SlotEvaluateRequest.MAX_TRIP_ZONE));

        assertThat(request.tripZone()).hasSize(SlotEvaluateRequest.MAX_TRIP_ZONE);
    }

    @Test
    void aTripZoneOneCharacterOverTheContractLengthIsRefused() {
        assertThatThrownBy(() -> zoned("Z".repeat(SlotEvaluateRequest.MAX_TRIP_ZONE + 1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("tripZone must be at most");
    }

    @Test
    void aTripZoneThatNamesNoZoneIsRefused() {
        // minLength 1 on the service side: an empty or whitespace-only zone resolves to no calendar at
        // all, and the slot answer would then be a date the user never sees the same way.
        assertThatThrownBy(() -> zoned("")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tripZone must not be blank");
        assertThatThrownBy(() -> zoned("   ")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tripZone must not be blank");
    }

    @Test
    void collectionsOfExactlyTheContractSizeAreAccepted() {
        SlotEvaluateRequest request = sized(SlotEvaluateRequest.MAX_ITEMS, SlotEvaluateRequest.MAX_OPENING_HOURS,
                SlotEvaluateRequest.MAX_DATES_WITH_SAME_PLACE);

        assertThat(request.items()).hasSize(SlotEvaluateRequest.MAX_ITEMS);
        assertThat(request.openingHours()).hasSize(SlotEvaluateRequest.MAX_OPENING_HOURS);
        assertThat(request.datesWithSamePlace()).hasSize(SlotEvaluateRequest.MAX_DATES_WITH_SAME_PLACE);
    }

    @Test
    void oneItemOverTheContractSizeIsRefused() {
        assertThatThrownBy(() -> sized(SlotEvaluateRequest.MAX_ITEMS + 1, 1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most " + SlotEvaluateRequest.MAX_ITEMS + " items per request");
    }

    @Test
    void oneOpeningWindowOverTheContractSizeIsRefused() {
        assertThatThrownBy(() -> sized(0, SlotEvaluateRequest.MAX_OPENING_HOURS + 1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most " + SlotEvaluateRequest.MAX_OPENING_HOURS + " openingHours per request");
    }

    @Test
    void oneDuplicateDateOverTheContractSizeIsRefused() {
        assertThatThrownBy(() -> sized(0, 1, SlotEvaluateRequest.MAX_DATES_WITH_SAME_PLACE + 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(
                        "at most " + SlotEvaluateRequest.MAX_DATES_WITH_SAME_PLACE + " datesWithSamePlace per request");
    }

    @Test
    void aStayLengthIsUnknownOrPositive() {
        // exclusiveMinimum 0 on the service side: a zero or negative stay would collapse the candidate's
        // interval to nothing and let an overlapping slot read as free.
        assertThat(duration(1).durationMinutes()).isEqualTo(1);
        assertThat(duration(null).durationMinutes()).isNull();
        assertThatThrownBy(() -> duration(0)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("durationMinutes must be positive when present");
        assertThatThrownBy(() -> duration(-30)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("durationMinutes must be positive when present");
    }

    @Test
    void aDayThatHoldsNoItemIsRefused() {
        // minimum 1 on the service side: a per-day limit of zero would answer every trip date as full
        // and report the candidate as unschedulable for a reason the trip never set.
        assertThat(perDay(1).maxItemsPerDay()).isEqualTo(1);
        assertThatThrownBy(() -> perDay(0)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxItemsPerDay must be >= 1");
        assertThatThrownBy(() -> perDay(-1)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxItemsPerDay must be >= 1");
    }

    private static SlotEvaluateRequest duration(Integer durationMinutes) {
        return new SlotEvaluateRequest(Instant.parse("2026-09-06T00:00:00Z"), ID, ID, ID, D12, D14, "Asia/Seoul",
                durationMinutes, List.<NeighbourItemIn>of(),
                Map.of(D12, OpeningWindowIn.open(LocalTime.of(9, 0), LocalTime.of(18, 0))), List.of(),
                ItemProposeRequest.RouteEvidence.NONE, 20, false);
    }

    private static SlotEvaluateRequest perDay(int maxItemsPerDay) {
        return new SlotEvaluateRequest(Instant.parse("2026-09-06T00:00:00Z"), ID, ID, ID, D12, D14, "Asia/Seoul", 90,
                List.<NeighbourItemIn>of(), Map.of(D12, OpeningWindowIn.open(LocalTime.of(9, 0), LocalTime.of(18, 0))),
                List.of(), ItemProposeRequest.RouteEvidence.NONE, maxItemsPerDay, false);
    }

    /** A duplicate date must lie inside the trip, so the range spans more days than the cap allows. */
    private static SlotEvaluateRequest sized(int items, int openingHours, int datesWithSamePlace) {
        return new SlotEvaluateRequest(Instant.parse("2026-09-06T00:00:00Z"), ID, ID, ID, D12, D12.plusDays(40),
                "Asia/Seoul", 90, items(items), openingHours(openingHours), datesWithSamePlace(datesWithSamePlace),
                ItemProposeRequest.RouteEvidence.NONE, 20, false);
    }

    private static List<NeighbourItemIn> items(int size) {
        List<NeighbourItemIn> items = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            items.add(new NeighbourItemIn(ID, D12, i, LocalTime.of(9, 0), 30));
        }
        return items;
    }

    private static Map<LocalDate, OpeningWindowIn> openingHours(int size) {
        Map<LocalDate, OpeningWindowIn> hours = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) {
            hours.put(D12.plusDays(i), OpeningWindowIn.unknown());
        }
        return hours;
    }

    private static List<LocalDate> datesWithSamePlace(int size) {
        List<LocalDate> dates = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            dates.add(D12.plusDays(i));
        }
        return dates;
    }

    private static SlotEvaluateRequest zoned(String tripZone) {
        return new SlotEvaluateRequest(Instant.parse("2026-09-06T00:00:00Z"), ID, ID, ID, D12, D14, tripZone, 90,
                List.<NeighbourItemIn>of(), Map.of(D12, OpeningWindowIn.open(LocalTime.of(9, 0), LocalTime.of(18, 0))),
                List.of(), ItemProposeRequest.RouteEvidence.NONE, 20, false);
    }
}
