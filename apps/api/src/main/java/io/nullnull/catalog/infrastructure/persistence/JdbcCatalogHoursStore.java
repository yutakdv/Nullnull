package io.nullnull.catalog.infrastructure.persistence;

import io.nullnull.catalog.application.CatalogHoursStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Writes what a reviewed plan file states, and nothing it does not. */
@Repository
public class JdbcCatalogHoursStore implements CatalogHoursStore {

    private final JdbcTemplate jdbc;

    public JdbcCatalogHoursStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UUID> currentObservationId(UUID placeId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT id FROM place_hours_observations WHERE place_id = ? AND superseded_at IS NULL",
                    UUID.class, placeId));
        } catch (EmptyResultDataAccessException none) {
            return Optional.empty();
        }
    }

    @Override
    public void supersede(UUID observationId, Instant at) {
        jdbc.update("UPDATE place_hours_observations SET superseded_at = ? WHERE id = ?",
                Timestamp.from(at), observationId);
    }

    @Override
    public UUID recordObservation(UUID placeId, String outcome, Instant observedAt, String evidenceUrl,
            Instant staleAt, Instant recordedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO place_hours_observations
                    (id, place_id, source_code, source_registry_version, outcome, observed_at,
                     evidence_url, stale_at, created_at)
                VALUES (?, ?, 'NULLNULL_CURATED_HOURS', 1, ?, ?, ?, ?, ?)
                """, id, placeId, outcome, Timestamp.from(observedAt), evidenceUrl,
                Timestamp.from(staleAt), Timestamp.from(recordedAt));
        return id;
    }

    @Override
    public void recordWindow(UUID observationId, LocalDate date, String state, LocalTime opensAt,
            LocalTime closesAt) {
        jdbc.update("""
                INSERT INTO place_hours_windows (id, observation_id, effective_on, state, opens_at, closes_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), observationId, java.sql.Date.valueOf(date), state,
                opensAt == null ? null : java.sql.Time.valueOf(opensAt),
                closesAt == null ? null : java.sql.Time.valueOf(closesAt));
    }
}
