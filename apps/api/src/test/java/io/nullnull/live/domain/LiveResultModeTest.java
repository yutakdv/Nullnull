package io.nullnull.live.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.crowd.domain.SourceState;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("BA-091 live page mode")
class LiveResultModeTest {

    @Test
    @DisplayName("a page of current readings is LIVE, so the rule is not a constant")
    void allLiveIsLive() {
        assertThat(LiveResultMode.of(List.of(SourceState.LIVE, SourceState.LIVE)))
                .isEqualTo(SourceState.LIVE);
    }

    @Test
    @DisplayName("one stale area makes the page stale, because rounding up would claim it is current")
    void theWeakestStateWins() {
        assertThat(LiveResultMode.of(List.of(SourceState.LIVE, SourceState.STALE)))
                .isEqualTo(SourceState.STALE);
        assertThat(LiveResultMode.of(List.of(SourceState.LIVE, SourceState.REPLAY)))
                .isEqualTo(SourceState.REPLAY);
        // Order of arrival must not matter: the page is a set of readings, not a sequence.
        assertThat(LiveResultMode.of(List.of(SourceState.STALE, SourceState.LIVE)))
                .isEqualTo(SourceState.STALE);
        // UNAVAILABLE is weaker than STALE: an area that did not answer at all outranks one that
        // answered late.
        assertThat(LiveResultMode.of(List.of(SourceState.LIVE, SourceState.STALE, SourceState.UNAVAILABLE)))
                .isEqualTo(SourceState.UNAVAILABLE);
    }

    @Test
    @DisplayName("an empty page is UNAVAILABLE, not LIVE")
    void emptyIsUnavailable() {
        // Otherwise a working server with nothing to say and a silent provider look identical.
        assertThat(LiveResultMode.of(List.of())).isEqualTo(SourceState.UNAVAILABLE);
        assertThat(LiveResultMode.of(null)).isEqualTo(SourceState.UNAVAILABLE);
    }

    @Test
    @DisplayName("a state this path does not serve folds to UNAVAILABLE rather than being ranked")
    void unrankedStatesAreNotGuessed() {
        // FORECAST and QUALITATIVE are deliberately outside the ranking. Reaching one here means the
        // caller served something this page is not for, and inventing a rank for it would be the
        // "made-up constant" this repository refuses. It must not silently become LIVE.
        assertThat(LiveResultMode.of(Set.of(SourceState.FORECAST))).isEqualTo(SourceState.UNAVAILABLE);
        assertThat(LiveResultMode.of(Set.of(SourceState.QUALITATIVE))).isEqualTo(SourceState.UNAVAILABLE);
    }
}
