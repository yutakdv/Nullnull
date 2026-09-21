package io.nullnull.live.application;

import io.nullnull.crowd.application.LiveAreaCrowdQuery;
import io.nullnull.crowd.application.LiveAreaCrowdQuery.AreaReading;
import io.nullnull.crowd.application.ReplayManifestReader;
import io.nullnull.crowd.domain.SourceState;
import io.nullnull.live.domain.LiveQueryMode;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Chooses a whole Live or approved replay set, never a mixture of their readings. */
@Component
public class LiveAreaReadingSelector {

    private final LiveAreaCrowdQuery live;
    private final ReplayManifestReader replay;
    private final LiveAreaStore areas;
    private final boolean replayEnabled;

    public LiveAreaReadingSelector(LiveAreaCrowdQuery live, ReplayManifestReader replay, LiveAreaStore areas,
            @Value("${nullnull.capabilities.replay}") boolean replayEnabled) {
        this.live = Objects.requireNonNull(live, "live");
        this.replay = Objects.requireNonNull(replay, "replay");
        this.areas = Objects.requireNonNull(areas, "areas");
        this.replayEnabled = replayEnabled;
    }

    public List<AreaReading> select(LiveQueryMode mode, String sourceCode, List<UUID> areaIds, Instant now) {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(sourceCode, "sourceCode");
        Objects.requireNonNull(areaIds, "areaIds");
        Objects.requireNonNull(now, "now");
        if (!replayEnabled || mode == LiveQueryMode.LIVE_ONLY) {
            return live.latestFor(sourceCode, areaIds, now);
        }
        Set<UUID> requested = Set.copyOf(areaIds);
        List<AreaReading> sourceCurrent = live.latestFor(sourceCode,
                areas.activeAreas(sourceCode).stream().map(LiveAreaStore.StoredArea::id).toList(), now);
        List<AreaReading> current = sourceCurrent.stream()
                .filter(row -> requested.contains(row.liveAreaId())).toList();
        if (sourceCurrent.stream().anyMatch(row -> row.crowd().state() == SourceState.LIVE)) {
            return current;
        }
        List<AreaReading> fallback = replay.latestFor(sourceCode, now)
                .map(batch -> batch.readings().stream()
                        .filter(row -> requested.contains(row.liveAreaId())).toList())
                .orElse(List.of());
        return fallback.isEmpty() ? current : fallback;
    }
}
