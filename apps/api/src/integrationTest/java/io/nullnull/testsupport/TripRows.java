package io.nullnull.testsupport;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Inserts a minimal valid trip row.
 *
 * <p>Tests used to point {@code owners.active_trip_id} at a random UUID because the trips table did
 * not exist. V013 adds the foreign key V002 deferred and the trigger that requires the trip to
 * belong to the same owner, so a made-up id is now rejected by the database - which is the point of
 * the constraint, and why those tests need a real row instead of a placeholder.
 */
public final class TripRows {

    private TripRows() {
    }

    /** A DRAFT trip at version 1 owned by {@code ownerId}, with a valid four-day range. */
    public static UUID insert(JdbcTemplate jdbc, UUID ownerId, Instant now) {
        UUID id = UUID.randomUUID();
        LocalDate start = LocalDate.of(2026, 10, 4);
        jdbc.update("INSERT INTO trips (id, owner_id, title, start_date, end_date, timezone,"
                        + " planning_level, status, version, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, ownerId, "새 여행", Date.valueOf(start), Date.valueOf(start.plusDays(3)),
                "Asia/Seoul", "NOTHING", "DRAFT", 1L, Timestamp.from(now), Timestamp.from(now));
        return id;
    }
}
