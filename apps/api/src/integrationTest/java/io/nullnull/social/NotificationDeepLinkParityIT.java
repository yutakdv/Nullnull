package io.nullnull.social;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.social.domain.NotificationDeepLink;
import io.nullnull.testsupport.TestcontainersConfiguration;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The deep link parser and {@code notifications_deep_link_check} refuse the same values.
 *
 * <p>ERD asks for both a database check and an application parser. Written as if that meant two
 * independent defences, it produced one defence and one crash: the parser was WIDER than the CHECK,
 * so {@code /trip/a.b} and {@code /trip/서울} passed Java and died at the INSERT as a constraint
 * violation - a 500 where a refusal belonged. Five of seven sample values were in that band.
 *
 * <p>Two layers only help when the outer one refuses everything the inner one would. This compares
 * them on the same inputs, in both directions, so the band cannot reopen: a character added to one
 * and not the other fails here rather than at a caller.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@DisplayName("BA-085 deep link layers")
class NotificationDeepLinkParityIT {

    /** Both ends of the band, and the shapes the parser names its own reasons for. */
    private static final List<String> SAMPLES = List.of(
            "/trip/018f4a10-2c31-7d42-9a55-6b1f0c3e8a01",
            "/trip/018f4a10-2c31-7d42-9a55-6b1f0c3e8a01/optimizations/018f4a20-9f11-7c08-b3d7-2e5a41c9b104",
            "/notifications", "/profile", "/admin",
            // The band: accepted by the old parser, refused by the CHECK.
            "/trip/a.b", "/trip/서울", "/trip/a%20b", "/trip/a:b", "/trip/a,b", "/trip/a+b", "/trip/a~b",
            // Refused by both, each for a reason the parser names.
            "https://evil.example/x", "//intranet/admin", "///x", "evil.example/x",
            "/trip/a?b=1", "/trip/a#b", "/trip/a\\b", "/trip/a b", "/", "");

    @Autowired
    JdbcTemplate jdbc;

    private final List<UUID> seeded = new ArrayList<>();
    private UUID ownerId;

    @AfterEach
    void removeTheRowsThisClassCreated() {
        for (UUID id : seeded) {
            jdbc.update("DELETE FROM notifications WHERE id = ?", id);
        }
        if (ownerId != null) {
            jdbc.update("DELETE FROM owners WHERE id = ?", ownerId);
        }
        seeded.clear();
        ownerId = null;
    }

    @Test
    @DisplayName("BA-085 every value the parser accepts is storable, and every value it refuses is refused")
    void theParserAndTheCheckConstraintAgree() {
        ownerId = owner();
        List<String> parserAcceptedButUnstorable = new ArrayList<>();
        List<String> storableButParserRefused = new ArrayList<>();
        int accepted = 0;

        for (String candidate : SAMPLES) {
            boolean parserOk;
            try {
                NotificationDeepLink.require(candidate);
                parserOk = true;
            } catch (IllegalArgumentException refused) {
                parserOk = false;
            }
            boolean storable = storable(candidate);
            if (parserOk) {
                accepted++;
                if (!storable) {
                    parserAcceptedButUnstorable.add(candidate);
                }
            } else if (storable) {
                storableButParserRefused.add(candidate);
            }
        }

        // Non-vacuity: a parser that refused everything would satisfy the first list trivially.
        assertThat(accepted).as("some samples are accepted, or this proves nothing").isGreaterThan(3);
        assertThat(parserAcceptedButUnstorable)
                .as("accepted by Java and refused by the database - a 500 where a refusal belonged")
                .isEmpty();
        assertThat(storableButParserRefused)
                .as("the database would keep these, so the parser is refusing more than the schema does")
                .isEmpty();
    }

    /** Whether the column's CHECK would take this value, asked of the database rather than assumed. */
    private boolean storable(String deepLink) {
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO notifications (id, owner_id, type, title, body, deep_link,"
                            + " created_at, read_at, expires_at)"
                            + " VALUES (?, ?, 'TRIP_REMINDER', 't', 'b', ?, ?, NULL, ?)",
                    id, ownerId, deepLink, Timestamp.from(Instant.now()),
                    Timestamp.from(Instant.now().plusSeconds(86400)));
            seeded.add(id);
            return true;
        } catch (org.springframework.dao.DataIntegrityViolationException refused) {
            return false;
        }
    }

    private UUID owner() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO owners (id, kind, locale, timezone, created_at)"
                + " VALUES (?, 'ANONYMOUS', 'ko-KR', 'Asia/Seoul', ?)", id, Timestamp.from(Instant.now()));
        return id;
    }
}
