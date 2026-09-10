package io.nullnull.crowd.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Atomically reserves one provider call against the KST provider day. */
@Service
public class SourceQuotaGuard {

    static final ZoneId PROVIDER_DAY_ZONE = ZoneId.of("Asia/Seoul");
    private static final Logger log = LoggerFactory.getLogger(SourceQuotaGuard.class);

    private final SourceQuotaStore store;
    private final Clock clock;

    public SourceQuotaGuard(SourceQuotaStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public SourceQuotaStore.Reservation acquire(UUID collectorRunId, String sourceCode,
            String endpointKey, String requestId, String releaseVersion) {
        Instant now = clock.instant();
        LocalDate day = now.atZone(PROVIDER_DAY_ZONE).toLocalDate();
        Instant start = day.atStartOfDay(PROVIDER_DAY_ZONE).toInstant();
        Instant end = day.plusDays(1).atStartOfDay(PROVIDER_DAY_ZONE).toInstant();
        SourceQuotaStore.Reservation reservation = store.reserve(
                new SourceQuotaStore.ReservationRequest(collectorRunId, sourceCode, endpointKey,
                        requestId, releaseVersion, now), start, end);
        for (int threshold : reservation.crossedThresholds()) {
            log.warn("source.quota.threshold source={} threshold={} used={} limit={}",
                    sourceCode, threshold, reservation.used(), reservation.limit());
        }
        return reservation;
    }
}
