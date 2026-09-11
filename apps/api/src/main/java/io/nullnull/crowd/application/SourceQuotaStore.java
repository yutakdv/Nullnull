package io.nullnull.crowd.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SourceQuotaStore {

    Reservation reserve(ReservationRequest request, Instant dayStart, Instant nextDayStart);

    record ReservationRequest(UUID collectorRunId, String sourceCode, String endpointKey,
            String requestId, String releaseVersion, Instant createdAt) {
    }

    record Reservation(UUID ingestLogId, int used, int limit, List<Integer> crossedThresholds) {
        public Reservation {
            crossedThresholds = List.copyOf(crossedThresholds);
        }
    }
}
