package io.nullnull.social;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.testsupport.OwnedRows;
import io.nullnull.testsupport.PostCovers;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The rows PostWithdrawalIT and PostWithdrawalConcurrencyIT withdraw, and the removal of exactly
 * those rows. The required gate runs every context against one database, so every read and delete
 * here names what this instance made.
 */
final class PostWithdrawalFixtures {

    /**
     * How long a held row is kept at most, whatever the test does. A safety stop that only decides
     * how long a broken test takes to fail, never whether a working one passes.
     */
    private static final long HOLD_AT_MOST_SECONDS = 120;

    private final JdbcTemplate jdbc;
    private final List<UUID> createdPosts = new ArrayList<>();
    private final List<UUID> createdAssets = new ArrayList<>();
    private final List<UUID> createdPlaces = new ArrayList<>();

    PostWithdrawalFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void removeOnlyTheseRows() {
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

    Map<String, Object> row(UUID postId) {
        return jdbc.queryForMap("SELECT status, published_at, updated_at FROM posts WHERE id = ?", postId);
    }

    UUID place(String name) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("INSERT INTO places (id, canonical_name, category_code, region_code, status,"
                + " created_at, updated_at) VALUES (?, ?, 'HS', '11', 'ACTIVE', ?, ?)", id, name, now, now);
        createdPlaces.add(id);
        return id;
    }

    UUID draft(String title, UUID placeId) {
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
    UUID published(String title) {
        UUID id = draft(title, place(title + " 장소"));
        jdbc.update("UPDATE posts SET status = 'PUBLISHED', published_at = updated_at WHERE id = ?", id);
        return id;
    }

    UUID publishedUserCover(String title, String coverUrl) {
        UUID id = published(title);
        UUID assetId = UUID.randomUUID();
        UUID licenceId = jdbc.queryForObject(
                "SELECT id FROM asset_licenses WHERE source_code = 'USER_UPLOAD'", UUID.class);
        String hex = assetId.toString().replace("-", "");
        jdbc.update("INSERT INTO media_assets (id, asset_license_id, source_external_id, origin_url,"
                        + " served_url, checksum, media_type, alt_text, license_checked_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, 'IMAGE', ?, ?)",
                assetId, licenceId, "upload-" + assetId, coverUrl, coverUrl, hex + hex,
                "사용자 표지", Timestamp.from(Instant.now()));
        createdAssets.add(assetId);
        jdbc.update("UPDATE posts SET cover_url = ?, cover_asset_id = ? WHERE id = ?",
                coverUrl, assetId, id);
        return id;
    }

    /**
     * Holds the post's row in another transaction until {@code release}, returning once it is held.
     * The hold ends on the release, not on a timer; the timer only stops a broken test from hanging.
     */
    Future<?> holdRow(ExecutorService pool, TransactionTemplate transactions, UUID postId,
            CountDownLatch release) throws InterruptedException {
        CountDownLatch held = new CountDownLatch(1);
        Future<?> holder = pool.submit(() -> transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT id FROM posts WHERE id = ? FOR UPDATE", UUID.class, postId);
            held.countDown();
            try {
                if (!release.await(HOLD_AT_MOST_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the row was never released");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(held.await(HOLD_AT_MOST_SECONDS, TimeUnit.SECONDS)).as("the row is held").isTrue();
        return holder;
    }
}
