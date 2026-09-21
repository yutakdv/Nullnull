package io.nullnull.live.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.nullnull.crowd.application.CrowdProvenanceProjection.CrowdMetric;
import io.nullnull.crowd.application.LiveAreaCrowdQuery;
import io.nullnull.crowd.application.LiveAreaCrowdQuery.AreaReading;
import io.nullnull.crowd.application.ReplayManifestReader;
import io.nullnull.crowd.domain.SourceState;
import io.nullnull.live.domain.LiveQueryMode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LiveAreaReadingSelectorTest {

    private static final Instant NOW = Instant.parse("2026-09-20T07:00:00Z");
    private static final UUID AREA = UUID.fromString("018f5a10-2c31-7f40-9a11-0c1d2e3f4c01");
    private static final UUID OTHER_AREA = UUID.fromString("018f5a10-2c31-7f40-9a11-0c1d2e3f4c02");

    @Test
    @DisplayName("BA-092-T2 오래된 Live를 replay로 대체할 때 두 상태를 한 응답에 섞지 않는다")
    void staleLiveUsesApprovedReplayAsOneNamespace() {
        var selector = new LiveAreaReadingSelector(live(SourceState.STALE), replay(SourceState.REPLAY),
                areas(AREA), true);

        List<AreaReading> result = selector.select(LiveQueryMode.REPLAY_ALLOWED, "SEOUL_CITYDATA", List.of(AREA), NOW);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().crowd().state()).isEqualTo(SourceState.REPLAY);
    }

    @Test
    void currentLiveWinsAndLiveOnlyNeverUsesReplay() {
        var current = new LiveAreaReadingSelector(live(SourceState.LIVE), replay(SourceState.REPLAY),
                areas(AREA), true);
        var expired = new LiveAreaReadingSelector(live(SourceState.STALE), replay(SourceState.REPLAY),
                areas(AREA), true);

        assertThat(current.select(LiveQueryMode.REPLAY_ALLOWED, "SEOUL_CITYDATA", List.of(AREA), NOW)
                .getFirst().crowd().state()).isEqualTo(SourceState.LIVE);
        assertThat(expired.select(LiveQueryMode.LIVE_ONLY, "SEOUL_CITYDATA", List.of(AREA), NOW)
                .getFirst().crowd().state()).isEqualTo(SourceState.STALE);
    }

    @Test
    void aDisabledReplayCapabilityNeverUsesItsManifest() {
        var selector = new LiveAreaReadingSelector(live(SourceState.STALE), replay(SourceState.REPLAY),
                areas(AREA), false);

        assertThat(selector.select(LiveQueryMode.AUTO, "SEOUL_CITYDATA", List.of(AREA), NOW)
                .getFirst().crowd().state()).isEqualTo(SourceState.STALE);
    }

    @Test
    @DisplayName("BA-092-T14 다른 구역에 현재 Live가 있으면 장소 상세도 replay로 바꾸지 않는다")
    void aPlaceRequestUsesTheSameSourceWideLiveDecisionAsTheAreaList() {
        LiveAreaCrowdQuery source = (name, ids, now) -> ids.contains(OTHER_AREA)
                ? List.of(new AreaReading(AREA, metric(SourceState.STALE)),
                        new AreaReading(OTHER_AREA, metric(SourceState.LIVE)))
                : List.of(new AreaReading(AREA, metric(SourceState.STALE)));
        var selector = new LiveAreaReadingSelector(source, replay(SourceState.REPLAY),
                areas(AREA, OTHER_AREA), true);

        assertThat(selector.select(LiveQueryMode.AUTO, "SEOUL_CITYDATA", List.of(AREA), NOW)
                .getFirst().crowd().state()).isEqualTo(SourceState.STALE);
    }

    private static LiveAreaCrowdQuery live(SourceState state) {
        return (source, ids, now) -> List.of(new AreaReading(AREA, metric(state)));
    }

    private static LiveAreaStore areas(UUID... ids) {
        LiveAreaStore areas = mock(LiveAreaStore.class);
        when(areas.activeAreas("SEOUL_CITYDATA")).thenReturn(java.util.Arrays.stream(ids)
                .map(id -> new LiveAreaStore.StoredArea(id, id.toString(), "검증 구역", "ACTIVE"))
                .toList());
        return areas;
    }

    private static ReplayManifestReader replay(SourceState state) {
        return new ReplayManifestReader() {
            @Override
            public Optional<ReplayBatch> read(UUID id, Instant now) {
                return Optional.empty();
            }

            @Override
            public Optional<ReplayBatch> latestFor(String source, Instant now) {
                return Optional.of(new ReplayBatch(UUID.randomUUID(), NOW.minusSeconds(1),
                        NOW.minusSeconds(600), List.of(new AreaReading(AREA, metric(state)))));
            }
        };
    }

    private static CrowdMetric metric(SourceState state) {
        return CrowdMetric.of(state, null, null, "2", "서울 혼잡도", null);
    }
}
