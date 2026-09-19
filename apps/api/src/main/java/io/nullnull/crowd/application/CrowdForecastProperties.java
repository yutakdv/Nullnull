package io.nullnull.crowd.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Bounds one client-visible forecast query; the provider only publishes a 30-day daily series. */
@Component
public final class CrowdForecastProperties {

    private final int maxRangeDays;

    public CrowdForecastProperties(@Value("${nullnull.crowd.max-range-days:30}") int maxRangeDays) {
        if (maxRangeDays < 1 || maxRangeDays > 31) {
            throw new IllegalArgumentException("nullnull.crowd.max-range-days must be between 1 and 31");
        }
        this.maxRangeDays = maxRangeDays;
    }

    public int maxRangeDays() {
        return maxRangeDays;
    }

    public boolean accepts(Instant from, Instant to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        return !from.isBefore(EARLIEST) && !to.isAfter(LATEST)
                && !to.isBefore(from) && Duration.between(from, to).compareTo(Duration.ofDays(maxRangeDays)) <= 0;
    }

    /**
     * The four-digit years RFC 3339 - the contract's {@code format: date-time} - can write. Jackson
     * and Spring also read "+300000-01-01T00:00:00Z" into an Instant; PostgreSQL's timestamptz ends in
     * 294276 AD, so such a window passed the relative check above and failed as the query bound it,
     * which the catch-all answered 500 (BA-023-T20).
     */
    private static final Instant EARLIEST = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant LATEST = Instant.parse("9999-12-31T23:59:59.999999999Z");
}
