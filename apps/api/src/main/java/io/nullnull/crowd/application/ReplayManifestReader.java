package io.nullnull.crowd.application;

import io.nullnull.crowd.application.LiveAreaCrowdQuery.AreaReading;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Reads one explicitly selected, approved replay manifest after checking its complete entry list. */
public interface ReplayManifestReader {

    record ReplayBatch(UUID manifestId, Instant approvedAt, Instant replayAt, List<AreaReading> readings) {
        public ReplayBatch {
            readings = List.copyOf(readings);
        }
    }

    Optional<ReplayBatch> read(UUID manifestId, Instant now);

    /** Selects the newest approved manifest; a corrupt newest one fails closed. */
    Optional<ReplayBatch> latestFor(String sourceCode, Instant now);
}
