package io.nullnull.live.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BA-090's mapping policy, tested where it is a decision rather than where it is a route.
 *
 * <p>A route can only show the combinations its fixtures happen to build. The clause is about every
 * combination of "is there a mapping" and "is there a reading", including the one that looks covered
 * and is not, so the decision is driven directly.
 */
@DisplayName("BA-090 live area coverage")
class LiveCoverageTest {

    private static final UUID PLACE = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID OTHER_PLACE = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID AREA = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID OTHER_AREA = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    @Test
    @DisplayName("BA-090-T2 a place with no coverage carries no area value, not zero and not a neighbour's")
    void anUncoveredPlaceCarriesNoAreaValue() {
        LiveAreaMapping mapping = new LiveAreaMapping(OTHER_PLACE, AREA, LiveAreaMapping.AREA,
                new BigDecimal("0.90"), false);

        // This clause on its own is satisfied by a constant - an implementation that answered NONE
        // for everything would pass it. That is why the covered case below is part of the same pair:
        // neither test means anything without the other, the same way BA-024-T6 puts the populated
        // answer beside the empty one.
        //
        // Three ways to be uncovered, and each is a way an implementation could quietly fill one in:
        // nothing maps this place, something maps it to an area that has not reported, and another
        // place's mapping sits right there in the same map with a reading behind it.
        LiveCoverage unmapped = LiveCoverage.decide(PLACE, Map.of(OTHER_PLACE, mapping), Set.of(AREA));
        LiveCoverage silentArea = LiveCoverage.decide(PLACE,
                Map.of(PLACE, new LiveAreaMapping(PLACE, OTHER_AREA, LiveAreaMapping.AREA,
                        new BigDecimal("0.90"), false)),
                Set.of(AREA));
        LiveCoverage nothingReported = LiveCoverage.decide(PLACE,
                Map.of(PLACE, new LiveAreaMapping(PLACE, AREA, LiveAreaMapping.AREA,
                        new BigDecimal("0.90"), false)),
                Set.of());

        for (LiveCoverage uncovered : java.util.List.of(unmapped, silentArea, nothingReported)) {
            assertThat(uncovered.covered()).isFalse();
            assertThat(uncovered.liveAreaId()).isNull();
            // The number is absent, not zero. BigDecimal.ZERO here would be a reading nobody took.
            assertThat(uncovered.confidence()).isNull();
            assertThat(uncovered.mappingType()).isEqualTo(LiveCoverage.NONE);
            assertThat(uncovered.fallbackUsed()).isFalse();
        }
    }

    @Test
    @DisplayName("a mapped place whose area reported carries that area, on the mapping's own terms")
    void aCoveredPlaceCarriesItsOwnAreaAndTerms() {
        LiveCoverage covered = LiveCoverage.decide(PLACE,
                Map.of(PLACE, new LiveAreaMapping(PLACE, AREA, LiveAreaMapping.AREA,
                                new BigDecimal("0.72"), true),
                        OTHER_PLACE, new LiveAreaMapping(OTHER_PLACE, OTHER_AREA, LiveAreaMapping.AREA,
                                new BigDecimal("0.10"), false)),
                Set.of(AREA, OTHER_AREA));

        // Its own area and its own terms: confidence and fallbackUsed come from this place's mapping
        // row, so a second place in the same map cannot lend or borrow either.
        assertThat(covered.covered()).isTrue();
        assertThat(covered.liveAreaId()).isEqualTo(AREA);
        assertThat(covered.confidence()).isEqualByComparingTo("0.72");
        assertThat(covered.fallbackUsed()).isTrue();
        assertThat(covered.mappingType()).isEqualTo(LiveAreaMapping.AREA);
    }

    @Test
    @DisplayName("a coverage cannot name an area and call itself uncovered, or the reverse")
    void coveredAndNamingAnAreaAreOneFact() {
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> new LiveCoverage(LiveCoverage.NONE, false, null, AREA))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> new LiveCoverage(LiveAreaMapping.AREA, false, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
