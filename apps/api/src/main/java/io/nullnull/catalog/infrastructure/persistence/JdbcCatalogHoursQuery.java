package io.nullnull.catalog.infrastructure.persistence;

import io.nullnull.catalog.application.CatalogHoursQuery;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

/**
 * One statement: the windows of the place's current, unexpired reading that fall in the range.
 *
 * <p>{@code stale_at > now} rather than {@code >=} because the threshold is the moment the reading
 * stops being trusted, which is the boundary V025's CHECK uses when it refuses {@code stale_at} equal
 * to {@code observed_at}.
 */
@Repository
public class JdbcCatalogHoursQuery implements CatalogHoursQuery {

    private static final String WINDOWS = """
            SELECT window_row.effective_on, window_row.state, window_row.opens_at, window_row.closes_at
              FROM place_hours_windows window_row
              JOIN place_hours_observations evidence ON evidence.id = window_row.observation_id
             WHERE evidence.place_id = ?
               AND evidence.superseded_at IS NULL
               AND evidence.stale_at > ?
               AND window_row.effective_on BETWEEN ? AND ?
            """;

    private final JdbcTemplate jdbc;

    public JdbcCatalogHoursQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<LocalDate, CatalogOpeningWindow> windowsFor(UUID placeId, LocalDate from, LocalDate to,
            Instant now) {
        Map<LocalDate, CatalogOpeningWindow> windows = new LinkedHashMap<>();
        RowCallbackHandler collect = row -> put(windows, row);
        jdbc.query(WINDOWS, collect, placeId, Timestamp.from(now),
                java.sql.Date.valueOf(from), java.sql.Date.valueOf(to));
        return Map.copyOf(windows);
    }

    /** {@link #WINDOWS} with the place id widened to a set and returned with each row. */
    private static final String WINDOWS_FOR_ALL = """
            SELECT evidence.place_id, window_row.effective_on, window_row.state, window_row.opens_at,
                   window_row.closes_at
              FROM place_hours_windows window_row
              JOIN place_hours_observations evidence ON evidence.id = window_row.observation_id
             WHERE evidence.place_id = ANY (?)
               AND evidence.superseded_at IS NULL
               AND evidence.stale_at > ?
               AND window_row.effective_on BETWEEN ? AND ?
            """;

    @Override
    public Map<UUID, Map<LocalDate, CatalogOpeningWindow>> windowsForAll(java.util.List<UUID> placeIds,
            LocalDate from, LocalDate to, Instant now) {
        if (placeIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Map<LocalDate, CatalogOpeningWindow>> byPlace = new LinkedHashMap<>();
        RowCallbackHandler collect = row -> put(
                byPlace.computeIfAbsent(row.getObject("place_id", UUID.class), id -> new LinkedHashMap<>()), row);
        jdbc.query(WINDOWS_FOR_ALL, collect, placeIds.toArray(UUID[]::new), Timestamp.from(now),
                java.sql.Date.valueOf(from), java.sql.Date.valueOf(to));
        Map<UUID, Map<LocalDate, CatalogOpeningWindow>> frozen = new LinkedHashMap<>();
        byPlace.forEach((place, windows) -> frozen.put(place, Map.copyOf(windows)));
        return Map.copyOf(frozen);
    }

    private static void put(Map<LocalDate, CatalogOpeningWindow> windows, ResultSet row) throws SQLException {
        java.sql.Time opensAt = row.getTime("opens_at");
        java.sql.Time closesAt = row.getTime("closes_at");
        windows.put(row.getDate("effective_on").toLocalDate(),
                new CatalogOpeningWindow(
                        CatalogOpeningWindow.State.valueOf(row.getString("state")),
                        opensAt == null ? null : opensAt.toLocalTime(),
                        closesAt == null ? null : closesAt.toLocalTime()));
    }
}
