package io.nullnull.social;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.social.application.PostWithdrawalService;
import io.nullnull.social.application.PostWithdrawalService.Withdrawal;
import io.nullnull.testsupport.OwnedRows;
import io.nullnull.testsupport.PostCovers;
import io.nullnull.testsupport.TestcontainersConfiguration;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * BA-082-T16: the writer behind the {@code withdraw-post} operator task.
 *
 * <p>Until this existed a post left PUBLISHED only when someone typed the UPDATE into the database,
 * and BA-082-T3 had to hand-write that statement to have a withdrawal to read. This measures the
 * production writer against the row it leaves behind; FeedIT's T3 now uses the same writer, so the
 * read side is measured against what the tool actually writes rather than against a copy of it.
 *
 * <p>Every read and delete names the rows this class made. The required gate runs every context
 * against one database, where a count over {@code posts} is a count of every other test's posts too.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@DisplayName("BA-082 post withdrawal")
class PostWithdrawalIT {

    @Autowired PostWithdrawalService withdrawals;
    @Autowired JdbcTemplate jdbc;

    private final List<UUID> createdPosts = new ArrayList<>();
    private final List<UUID> createdAssets = new ArrayList<>();
    private final List<UUID> createdPlaces = new ArrayList<>();

    @AfterEach
    void removeOnlyThisTestsRows() {
        for (UUID postId : createdPosts) {
            // post_places cascades. The post goes before its cover, which it references.
            jdbc.update("DELETE FROM posts WHERE id = ?", postId);
        }
        for (UUID assetId : createdAssets) {
            jdbc.update("DELETE FROM media_assets WHERE id = ?", assetId);
        }
        OwnedRows.remove(jdbc, "places", List.copyOf(createdPlaces));
        createdPosts.clear();
        createdAssets.clear();
        createdPlaces.clear();
    }

    @Test
    @DisplayName("BA-082-T16 a withdrawn post leaves PUBLISHED with published_at cleared")
    void aWithdrawnPostLeavesPublished() {
        UUID post = published("회수될 글");

        assertThat(withdrawals.withdraw(post)).isEqualTo(Withdrawal.WITHDRAWN);

        Map<String, Object> row = row(post);
        assertThat(row.get("status")).as("the post is no longer PUBLISHED").isEqualTo("HIDDEN");
        // Asserted separately from the status. V015's shape CHECK couples the two today, and the day
        // it is relaxed - a screen that wants to say when a post was published is a plausible reason -
        // this is what notices a withdrawal that keeps the date the feed orders by.
        assertThat(row.get("published_at")).as("published_at is cleared").isNull();
    }

    @Test
    @DisplayName("a second withdrawal of the same post answers ALREADY_HIDDEN and writes nothing")
    void aSecondWithdrawalWritesNothing() {
        UUID post = published("두 번 회수될 글");
        assertThat(withdrawals.withdraw(post)).isEqualTo(Withdrawal.WITHDRAWN);
        Object updatedAt = row(post).get("updated_at");

        // A rerun of an approved task is the ordinary way this runs twice, and it must succeed
        // without touching the row: the operator counts ALREADY_HIDDEN as the goal already met.
        assertThat(withdrawals.withdraw(post)).isEqualTo(Withdrawal.ALREADY_HIDDEN);

        Map<String, Object> row = row(post);
        assertThat(row.get("status")).isEqualTo("HIDDEN");
        assertThat(row.get("updated_at")).as("the rerun wrote nothing").isEqualTo(updatedAt);
    }

    @Test
    @DisplayName("withdrawing one post leaves every other published post as it was")
    void onlyTheNamedPostIsWithdrawn() {
        UUID withdrawn = published("회수될 글");
        UUID kept = published("남을 글");
        Map<String, Object> before = row(kept);

        assertThat(withdrawals.withdraw(withdrawn)).isEqualTo(Withdrawal.WITHDRAWN);

        Map<String, Object> after = row(kept);
        assertThat(after.get("status")).isEqualTo("PUBLISHED");
        assertThat(after.get("published_at")).isEqualTo(before.get("published_at"));
        assertThat(after.get("updated_at")).isEqualTo(before.get("updated_at"));
    }

    @Test
    @DisplayName("an id that names no post is refused and writes nothing")
    void anUnknownPostIsRefused() {
        UUID unknown = UUID.randomUUID();

        assertThat(withdrawals.withdraw(unknown)).isEqualTo(Withdrawal.NOT_FOUND);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM posts WHERE id = ?", Integer.class, unknown))
                .isZero();
    }

    @Test
    @DisplayName("a draft is refused and stays a draft")
    void aDraftIsRefusedAndLeftADraft() {
        // A draft was never shown to anyone, so there is nothing to take back - and moving it to
        // HIDDEN would quietly stop a publication nobody asked to stop.
        UUID draft = draft("아직 공개 안 된 글", place("경복궁"));
        Object updatedAt = row(draft).get("updated_at");

        assertThat(withdrawals.withdraw(draft)).isEqualTo(Withdrawal.NOT_PUBLISHED);

        Map<String, Object> row = row(draft);
        assertThat(row.get("status")).isEqualTo("DRAFT");
        assertThat(row.get("published_at")).isNull();
        assertThat(row.get("updated_at")).isEqualTo(updatedAt);
    }

    private Map<String, Object> row(UUID postId) {
        return jdbc.queryForMap("SELECT status, published_at, updated_at FROM posts WHERE id = ?", postId);
    }

    private UUID place(String name) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("INSERT INTO places (id, canonical_name, category_code, region_code, status,"
                + " created_at, updated_at) VALUES (?, ?, 'HS', '11', 'ACTIVE', ?, ?)", id, name, now, now);
        createdPlaces.add(id);
        return id;
    }

    private UUID draft(String title, UUID placeId) {
        UUID id = UUID.randomUUID();
        UUID cover = PostCovers.firstPartyAsset(jdbc, Instant.now());
        createdAssets.add(cover);
        // An hour back, so the writer's own clock is certainly later and updated_at >= created_at holds.
        Timestamp created = Timestamp.from(Instant.now().minusSeconds(3600));
        jdbc.update("INSERT INTO posts (id, status, title, body, cover_url, cover_asset_id, created_at,"
                        + " updated_at) VALUES (?, 'DRAFT', ?, ?, 'https://example.test/cover.jpg', ?, ?, ?)",
                id, title, "본문 " + title, cover, created, created);
        createdPosts.add(id);
        jdbc.update("INSERT INTO post_places (post_id, place_id, position, mention_type)"
                + " VALUES (?, ?, 0, 'PRIMARY')", id, placeId);
        return id;
    }

    /** A PUBLISHED post: written, given its place, then made visible - the order V022 requires. */
    private UUID published(String title) {
        UUID id = draft(title, place(title + " 장소"));
        jdbc.update("UPDATE posts SET status = 'PUBLISHED', published_at = updated_at WHERE id = ?", id);
        return id;
    }
}
