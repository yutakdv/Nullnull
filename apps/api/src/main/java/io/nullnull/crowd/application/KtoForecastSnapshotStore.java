package io.nullnull.crowd.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for normalized KTO forecast batches; raw provider bodies have no path here. */
public interface KtoForecastSnapshotStore {

    /** A currently verified C2 KTO mapping is required before a future read-through may call KTO. */
    Optional<KtoForecastRequest> findFreshRequest(UUID placeId, Instant at);

    void save(KtoForecastRequest request, KtoForecastSnapshotSet snapshotSet);
}
