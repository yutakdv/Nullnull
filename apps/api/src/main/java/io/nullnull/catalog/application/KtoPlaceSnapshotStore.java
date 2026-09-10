package io.nullnull.catalog.application;

import io.nullnull.catalog.domain.KtoPlaceSnapshot;
import java.time.Instant;
import java.util.Optional;

/** Persistence port for normalized KTO detail snapshots. It deliberately has no raw-payload method. */
public interface KtoPlaceSnapshotStore {

    Optional<KtoPlaceSnapshot> findFresh(KtoPlaceRequest request, Instant at);

    void save(KtoPlaceSnapshot snapshot);
}
