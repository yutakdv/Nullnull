package io.nullnull.recommendation.domain.item;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.crowd.domain.ComparisonReasonCode;
import java.math.BigDecimal;
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
 * Every transported collection carries an explicit upper bound, so an oversized request fails here
 * rather than at the service, where it would come back as an opaque 422.
 */
@DisplayName("ItemProposeRequest transport bounds")
class ItemProposeRequestTest {

    static final UUID ID = UUID.fromString("018f3f8e-9b67-7a21-8d31-31d315b93a01");
    static final LocalDate D12 = LocalDate.of(2026, 9, 12);
    static final LocalDate D14 = LocalDate.of(2026, 9, 14);

    @Test
    void everyCollectionIsBounded() {
        assertThatThrownBy(() -> request(locks(ItemProposeRequest.MAX_LOCKS + 1), List.of(), openingHours(1),
                List.of())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("locks");
        assertThatThrownBy(() -> request(List.of(), neighbours(ItemProposeRequest.MAX_NEIGHBOURS + 1),
                openingHours(1), List.of())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("neighbours");
        assertThatThrownBy(() -> request(List.of(), List.of(),
                openingHours(ItemProposeRequest.MAX_OPENING_HOURS + 1), List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("openingHours");
        assertThatThrownBy(() -> request(List.of(), List.of(), openingHours(1),
                candidates(ItemProposeRequest.MAX_CANDIDATES + 1))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("candidates");
    }

    @Test
    void aNeighbouringStayIsUnknownOrPositive() {
        // Zero or a negative length would shrink a neighbouring interval to nothing and let an
        // overlapping preview through; the service refuses the same values with a 422.
        assertThatThrownBy(() -> new NeighbourItemIn(ID, D12, 2, LocalTime.of(10, 0), 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("durationMinutes");
        assertThatThrownBy(() -> new NeighbourItemIn(ID, D12, 2, LocalTime.of(10, 0), -30))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("durationMinutes");
        new NeighbourItemIn(ID, D12, 2, LocalTime.of(10, 0), null);
    }

    @Test
    void anOvernightOpeningWindowIsRefused() {
        // D-REC-18: P0 has no representation for a window that crosses midnight, and both languages
        // refuse it rather than silently treating 22:00-02:00 as an empty or inverted day.
        assertThatThrownBy(() -> OpeningWindowIn.open(LocalTime.of(22, 0), LocalTime.of(2, 0)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("closesAt");
        assertThatThrownBy(() -> OpeningWindowIn.open(LocalTime.of(9, 0), LocalTime.of(9, 0)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("closesAt");
        OpeningWindowIn.open(LocalTime.of(9, 0), LocalTime.of(18, 0));
    }

    @Test
    void theLargestAllowedRequestIsAccepted() {
        request(locks(ItemProposeRequest.MAX_LOCKS), neighbours(ItemProposeRequest.MAX_NEIGHBOURS),
                openingHours(ItemProposeRequest.MAX_OPENING_HOURS), candidates(ItemProposeRequest.MAX_CANDIDATES));
    }

    private static ItemProposeRequest request(List<LockIn> locks, List<NeighbourItemIn> neighbours,
            Map<LocalDate, OpeningWindowIn> openingHours, List<TemporalCandidateIn> candidates) {
        return new ItemProposeRequest(Instant.parse("2026-09-06T00:00:00Z"), ID, 7, D12, D14, "Asia/Seoul",
                new TargetItemIn(ID, ID, D12, LocalTime.of(10, 0), 90, 1), locks, neighbours, openingHours,
                ItemProposeRequest.RouteEvidence.NONE, candidates);
    }

    /** The four lock types are distinct, so an oversized list needs a repeated type; the cap still applies. */
    private static List<LockIn> locks(int size) {
        List<LockIn> locks = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            locks.add(LockIn.mustVisit());
        }
        return locks;
    }

    private static List<NeighbourItemIn> neighbours(int size) {
        List<NeighbourItemIn> neighbours = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            neighbours.add(new NeighbourItemIn(ID, D12, i, LocalTime.of(9, 0), 30));
        }
        return neighbours;
    }

    private static Map<LocalDate, OpeningWindowIn> openingHours(int size) {
        Map<LocalDate, OpeningWindowIn> hours = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) {
            hours.put(D12.plusDays(i), OpeningWindowIn.unknown());
        }
        return hours;
    }

    private static List<TemporalCandidateIn> candidates(int size) {
        List<TemporalCandidateIn> candidates = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            candidates.add(new TemporalCandidateIn(ID, D12, LocalTime.of(9, 0), TemporalCandidateIn.ForecastResolution.HOUR,
                    new BigDecimal("80"), new BigDecimal("40"), "KTO_RELATIVE_CONCENTRATION_INDEX", true,
                    ComparisonReasonCode.SAME_METRIC_AND_ISSUE, ID, ID));
        }
        return candidates;
    }
}
