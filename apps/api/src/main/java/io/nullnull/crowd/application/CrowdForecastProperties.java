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
        return !to.isBefore(from) && Duration.between(from, to).compareTo(Duration.ofDays(maxRangeDays)) <= 0;
    }
}
