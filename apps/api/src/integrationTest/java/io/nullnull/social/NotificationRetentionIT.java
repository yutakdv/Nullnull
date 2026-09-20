package io.nullnull.social;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.social.application.NotificationService;
import io.nullnull.social.domain.NotificationType;
import io.nullnull.testsupport.MutableClock;
import io.nullnull.testsupport.TestcontainersConfiguration;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * BA-085 retention: 90 days, or 30 days after it was read, whichever is earlier (ERD §6).
 *
 * <p><strong>This file is what V037 and {@code NotificationService} point at, and it is where the
 * rule is allowed to live.</strong> Two measurements against PostgreSQL 17.6 put it here rather
 * than in the schema, and both are worth stating because the schema is the obvious place to put it
 * and neither failure is visible from Java:
 *
 * <ol>
 *   <li>{@code timestamptz + interval} is STABLE, not IMMUTABLE ({@code pg_proc.provolatile = 's'},
 *       the same letter {@code date_trunc(text, timestamptz)} carries and the same reason BA-033's
 *       minute bucket could not be a generated column). A column
 *       {@code GENERATED ALWAYS AS (LEAST(created_at + interval '90 days', ...))} is refused
 *       outright: {@code ERROR: generation expression is not immutable}.</li>
 *   <li>The same expression written as a CHECK <em>is accepted</em>, and that is worse. It answers
 *       differently per session TimeZone: {@code '2026-03-01T00:00Z' + interval '90 days'} is
 *       {@code 2026-05-30T00:00Z} under UTC and an hour earlier under a zone that crosses a DST
 *       boundary in between. A row inserted under UTC then fails that constraint when it is
 *       re-validated under America/New_York - which is what a dump and restore does.</li>
 * </ol>
 *
 * <p>So the derivation is resolved to instants in Java and stored, no interval arithmetic reaches
 * SQL, and these tests are the thing that keeps it correct. V037 cannot be edited once applied;
 * this file can.
 */
@SpringBootTest(properties = "nullnull.notifications.enabled=true")
@Import({TestcontainersConfiguration.class, NotificationRetentionIT.Time.class})
@DisplayName("BA-085 notification retention")
class NotificationRetentionIT {

    private static final Instant NOW = Instant.parse("2032-03-01T00:00:00Z");

    @TestConfiguration
    static class Time {
        @Bean
        @Primary
        MutableClock notificationClock() {
            return MutableClock.at(NOW);
        }
    }

    @Autowired NotificationService notifications;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;

    private final List<UUID> seededOwners = new ArrayList<>();

    @AfterEach
    void removeTheRowsThisClassCreated() {
        // Named by this class's own owners. The required gate runs every context against one
        // database, so a statement that does not point at its own rows is a statement about every
        // test that ran before it (AGENTS rule 6).
        for (UUID ownerId : seededOwners) {
            jdbc.update("DELETE FROM notifications WHERE owner_id = ?", ownerId);
            jdbc.update("DELETE FROM owners WHERE id = ?", ownerId);
        }
        seededOwners.clear();
        // The clock is deliberately NOT reset: MutableClock only moves forward by design. Every
        // assertion below is relative to the instant its own row was created, so where the clock
        // has got to by then does not matter - which is what keeps these three independent of the
        // order JUnit runs them in.
    }

    @Test
    @DisplayName("BA-085-T7 a notification 90 days old does not survive the sweep")
    void ninetyDayRetention() {
        UUID owner = owner();
        Instant created = clock.instant();
        UUID kept = notifications.record(owner, NotificationType.TRIP_REMINDER, "t", "b", "/profile");
        assertThat(expiryOf(kept)).as("an unread notification expires 90 days after it was created")
                .isEqualTo(created.plus(Duration.ofDays(90)));

        // Three statements, not two. "my row is gone" and "the sweep ran" are both satisfied by a
        // sweep that deletes everything, so the row's survival one second before the boundary is
        // what says the cut is in the right place.
        //
        // The RETURN VALUE is deliberately not asserted. The sweep is global by design, so in the
        // required gate - where every context shares one database - its count is a statement about
        // every test that ran before this one rather than about this row (AGENTS rule 6).
        notifications.deleteExpired(created.plus(Duration.ofDays(90)).minusSeconds(1));
        assertThat(exists(kept)).as("one second inside retention it is still ours to keep").isTrue();

        notifications.deleteExpired(created.plus(Duration.ofDays(90)));
        assertThat(exists(kept)).as("90 days after it was created it is gone").isFalse();
    }

    @Test
    @DisplayName("BA-085-T8 a notification read 30 days ago does not survive the sweep")
    void thirtyDayReadRetention() {
        UUID owner = owner();
        UUID read = notifications.record(owner, NotificationType.TRIP_REMINDER, "t", "b", "/profile");

        // Read on day 5. min(created + 90d, read + 30d) is read + 30d, so reading SHORTENED it.
        clock.advance(Duration.ofDays(5));
        Instant readAt = clock.instant();
        notifications.markRead(ownerContext(owner), read);
        assertThat(expiryOf(read)).isEqualTo(readAt.plus(Duration.ofDays(30)));

        notifications.deleteExpired(readAt.plus(Duration.ofDays(30)).minusSeconds(1));
        assertThat(exists(read)).as("one second inside the read window it is still there").isTrue();
        notifications.deleteExpired(readAt.plus(Duration.ofDays(30)));
        assertThat(exists(read)).as("30 days after it was read it is gone").isFalse();
    }

    @Test
    @DisplayName("BA-085 reading shortens a notification's life and never extends it")
    void readingOnlyEverShortens() {
        UUID owner = owner();

        // Day 70: read + 30d would be day 100, which is PAST created + 90d. The rule takes the
        // earlier, so the expiry must not move. Without the min - a plain "expires = read + 30d" -
        // this row would outlive its 90 days, and the day-5 case above would still pass.
        Instant created = clock.instant();
        UUID late = notifications.record(owner, NotificationType.TRIP_REMINDER, "t", "b", "/profile");
        clock.advance(Duration.ofDays(70));
        notifications.markRead(ownerContext(owner), late);
        assertThat(expiryOf(late)).as("day 70: created + 90d is the earlier of the two")
                .isEqualTo(created.plus(Duration.ofDays(90)));

        // And opening it again keeps the first readAt, so the expiry it produced does not drift
        // forward every time the screen is opened.
        UUID reopened = notifications.record(owner, NotificationType.TRIP_REMINDER, "t", "b", "/profile");
        Instant firstRead = clock.instant();
        notifications.markRead(ownerContext(owner), reopened);
        Instant afterFirstOpen = expiryOf(reopened);
        clock.advance(Duration.ofDays(3));
        notifications.markRead(ownerContext(owner), reopened);
        assertThat(expiryOf(reopened)).as("a second open moves neither readAt nor the expiry")
                .isEqualTo(afterFirstOpen);
        assertThat(readAtOf(reopened)).isEqualTo(firstRead);
    }

    private io.nullnull.identity.application.OwnerContext ownerContext(UUID ownerId) {
        return new io.nullnull.identity.application.OwnerContext(ownerId, null, false);
    }

    private UUID owner() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO owners (id, kind, locale, timezone, created_at)"
                        + " VALUES (?, 'ANONYMOUS', 'ko-KR', 'Asia/Seoul', ?)",
                id, Timestamp.from(NOW));
        seededOwners.add(id);
        return id;
    }

    private Instant expiryOf(UUID id) {
        return jdbc.queryForObject("SELECT expires_at FROM notifications WHERE id = ?",
                Timestamp.class, id).toInstant();
    }

    private Instant readAtOf(UUID id) {
        Timestamp value = jdbc.queryForObject("SELECT read_at FROM notifications WHERE id = ?",
                Timestamp.class, id);
        return value == null ? null : value.toInstant();
    }

    private boolean exists(UUID id) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM notifications WHERE id = ?",
                Integer.class, id);
        return count != null && count > 0;
    }
}
