package io.nullnull.crowd.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Which calendar date a stored forecast point is the forecast for, and back.
 *
 * <p>The one forecast source P0 has, KTO's concentration forecast, publishes one value per KST calendar
 * date ({@code baseYmd}), and {@link KtoForecastResponseValidator} files it at that date's Seoul
 * midnight. So a point's date is read in Asia/Seoul - never in the trip's timezone. A trip's timezone
 * says where the traveller's device is, not where the place is, and a date read through it lands a day
 * early west of Seoul and misses the point entirely anywhere whose offset is not +9 (measured
 * 2026-09-19: a UTC trip failed DATA_INSUFFICIENT where the same trip in Asia/Seoul reached READY).
 *
 * <p>This is a matter of which point is the one for a date, not of whether two points may be
 * compared: {@code TemporalComparisonPolicy} decides that from source, metric, issue, normalization and
 * freshness, none of which reads a date or a zone.
 *
 * <p>The zone is the source's, and only one FORECAST source exists. A point from any other source is
 * refused rather than read as KST: its dates would be guessed, and a guessed date is exactly the
 * mis-paired comparison this exists to prevent. The day a second forecast source is registered, the
 * zone moves onto its registry entry.
 */
public final class ForecastDays {

    /** The zone KTO's forecast dates are in. */
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private ForecastDays() {
    }

    /** The instant a point for this date is filed at. */
    public static Instant startOf(LocalDate day) {
        return Objects.requireNonNull(day, "day").atStartOfDay(ZONE).toInstant();
    }

    /**
     * The last instant that still belongs to this date, for the inclusive {@code target_at <= to} bound
     * the set queries use (#259): with the next date's midnight as the bound, a set holding only the
     * NEXT date's point counted as covering this one. One microsecond is PostgreSQL's resolution.
     */
    public static Instant endOf(LocalDate day) {
        return startOf(Objects.requireNonNull(day, "day").plusDays(1)).minus(1, ChronoUnit.MICROS);
    }

    /** The date this point is the forecast for. */
    public static LocalDate dayOf(CrowdForecastQuery.Snapshot point) {
        Objects.requireNonNull(point, "point");
        if (!KtoForecastSnapshotSet.SOURCE_CODE.equals(point.source().code())) {
            throw new IllegalStateException("a FORECAST point from " + point.source().code()
                    + " has no known day zone; only " + KtoForecastSnapshotSet.SOURCE_CODE + " dates are read");
        }
        return LocalDate.ofInstant(Objects.requireNonNull(point.targetAt(), "targetAt"), ZONE);
    }
}
