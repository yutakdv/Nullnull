package io.nullnull.trip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.testsupport.TestcontainersConfiguration;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * What the database already decides about scheduling a candidate, before BA-040 decides anything.
 *
 * <p>PM-009 (#165) asks for a transition matrix: when an item is removed, replaced or duplicated,
 * what happens to its candidate. Most of that is an open product question - but not all of it, and
 * the part that is already settled is settled in DDL rather than in any document. These are those
 * parts, measured against real PostgreSQL so the proposal rests on behaviour instead of on a
 * reading of V016.
 *
 * <p>The first one is the load-bearing fact: removing an item while its candidate is SCHEDULED is
 * REFUSED. So {@code removeTripItem} does not get to choose whether to touch the candidate - only
 * which state to leave it in. A design that moved the item first and the candidate afterwards
 * cannot be built at all.
 */
@SpringBootTest(properties = "NULLNULL_CURSOR_SECRET=test-invariants-secret-that-is-long-enough")
@Import(TestcontainersConfiguration.class)
@org.junit.jupiter.api.DisplayName("PM-009 scheduling invariants the database already enforces")
class CandidateSchedulingInvariantsIT {

    @Autowired JdbcTemplate jdbc;

    @Test
    @org.junit.jupiter.api.DisplayName(
            "BA-034 an item cannot be removed while its candidate still points at it")
    void schedulingInvariantsHold() {
        OffsetDateTime now = OffsetDateTime.now();
        UUID owner = UUID.randomUUID();
        UUID place = UUID.randomUUID();
        UUID trip = UUID.randomUUID();
        UUID item = UUID.randomUUID();
        UUID candidate = UUID.randomUUID();
        jdbc.update("INSERT INTO owners (id, kind, locale, timezone, created_at)"
                + " VALUES (?, 'ANONYMOUS', 'ko-KR', 'Asia/Seoul', ?)", owner, now);
        jdbc.update("INSERT INTO places (id, canonical_name, category_code, region_code, status,"
                + " created_at, updated_at) VALUES (?, '탐침 장소', 'HS', '11', 'ACTIVE', ?, ?)",
                place, now, now);
        jdbc.update("INSERT INTO trips (id, owner_id, title, start_date, end_date, timezone,"
                + " planning_level, status, version, created_at, updated_at)"
                + " VALUES (?, ?, '탐침', ?::date, (?::date + 3), 'Asia/Seoul', 'NOTHING', 'DRAFT', 1, ?, ?)",
                trip, owner, now, now, now, now);
        jdbc.update("INSERT INTO trip_items (id, trip_id, place_id, trip_date, position,"
                + " created_at, updated_at) VALUES (?, ?, ?, ?::date, 0, ?, ?)",
                item, trip, place, now, now, now);
        jdbc.update("INSERT INTO trip_candidates (id, trip_id, place_id, status,"
                + " scheduled_trip_item_id, created_at, updated_at)"
                + " VALUES (?, ?, ?, 'SCHEDULED', ?, ?, ?)", candidate, trip, place, item, now, now);

        // PROBE 1: deleting the item fires ON DELETE SET NULL, which leaves a SCHEDULED row with a
        // null pointer - exactly what the shape CHECK forbids. Does the database refuse?
        assertThatThrownBy(() -> jdbc.update("DELETE FROM trip_items WHERE id = ?", item))
                .hasMessageContaining("trip_candidates_scheduled_shape_check");

        // PROBE 2: with the candidate moved off SCHEDULED first, the delete succeeds.
        jdbc.update("UPDATE trip_candidates SET status = 'ACTIVE', scheduled_trip_item_id = NULL,"
                + " updated_at = ? WHERE id = ?", now, candidate);
        jdbc.update("DELETE FROM trip_items WHERE id = ?", item);
        assertThat(jdbc.queryForObject("SELECT status FROM trip_candidates WHERE id = ?",
                String.class, candidate)).isEqualTo("ACTIVE");

        // PROBE 3: may the SAME place sit in two items of one trip? (PROBE 2 deleted the first
        // item, so both are inserted fresh here.)
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        jdbc.update("INSERT INTO trip_items (id, trip_id, place_id, trip_date, position,"
                + " created_at, updated_at) VALUES (?, ?, ?, ?::date, 0, ?, ?)",
                first, trip, place, now, now, now);
        jdbc.update("INSERT INTO trip_items (id, trip_id, place_id, trip_date, position,"
                + " created_at, updated_at) VALUES (?, ?, ?, ?::date, 1, ?, ?)",
                second, trip, place, now, now, now);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trip_items WHERE trip_id = ?"
                + " AND place_id = ?", Integer.class, trip, place))
                .as("two items may hold the same place").isEqualTo(2);

        // PROBE 4: and the partial unique means the trip can still hold only ONE non-dismissed
        // candidate for that place - so a candidate cannot point at both items.
        UUID other = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO trip_candidates (id, trip_id, place_id,"
                + " status, scheduled_trip_item_id, created_at, updated_at)"
                + " VALUES (?, ?, ?, 'SCHEDULED', ?, ?, ?)", other, trip, place, second, now, now))
                .as("a second non-dismissed candidate for the same place is refused")
                .hasMessageContaining("trip_candidates_active_place_idx");
        jdbc.execute("DELETE FROM trip_candidates WHERE id = '" + candidate + "'");
        jdbc.execute("DELETE FROM trip_items WHERE trip_id = '" + trip + "'");
        jdbc.execute("DELETE FROM trips WHERE id = '" + trip + "'");
        jdbc.execute("DELETE FROM places WHERE id = '" + place + "'");
        jdbc.execute("DELETE FROM owners WHERE id = '" + owner + "'");
    }
}
