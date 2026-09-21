package io.nullnull.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.social.application.PostLockTimeoutException;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

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
    @Autowired TransactionTemplate transactions;

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

    @Test
    @DisplayName("two withdrawals of one post at once: one withdraws it and the other finds it already hidden")
    void twoWithdrawalsAtOnceReportOneWithdrawal() throws Exception {
        UUID post = published("동시에 회수될 글");
        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch release = new CountDownLatch(1);
        try {
            // Both withdrawals are made to reach the row while another transaction holds it, so they
            // are genuinely concurrent rather than one finishing before the other starts. Released as
            // soon as both are seen waiting, well inside the lock bound.
            Future<?> holder = holdRow(pool, post, release);
            Future<Withdrawal> first = pool.submit(() -> withdrawals.withdraw(post));
            Future<Withdrawal> second = pool.submit(() -> withdrawals.withdraw(post));
            awaitWithdrawalsWaiting(2);
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);

            // A status read before the write would have let both see PUBLISHED and both report it.
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(Withdrawal.WITHDRAWN, Withdrawal.ALREADY_HIDDEN);
            assertThat(row(post).get("status")).isEqualTo("HIDDEN");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a withdrawal that cannot get the post's row within the bound fails instead of waiting")
    void aHeldRowFailsTheWithdrawalInsteadOfWaiting() throws Exception {
        UUID post = published("잠긴 글");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<?> holder = holdRow(pool, post, release);
            Future<Withdrawal> attempt = pool.submit(() -> withdrawals.withdraw(post));

            // Ten seconds against a three-second bound: an unbounded wait shows up here as a timeout,
            // not as a test that never ends.
            assertThatThrownBy(() -> attempt.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause().isInstanceOf(PostLockTimeoutException.class);

            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            assertThat(row(post).get("status")).as("the failed withdrawal wrote nothing")
                    .isEqualTo("PUBLISHED");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    /** Holds the post's row in another transaction until {@code release}; returns once it is held. */
    private Future<?> holdRow(ExecutorService pool, UUID postId, CountDownLatch release) throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        Future<?> holder = pool.submit(() -> transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT id FROM posts WHERE id = ? FOR UPDATE", UUID.class, postId);
            held.countDown();
            try {
                if (!release.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the row was never released");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(held.await(10, TimeUnit.SECONDS)).as("the row is held").isTrue();
        return holder;
    }

    /**
     * Waits until {@code count} withdrawal statements are blocked on a row lock. Only the withdrawal
     * writes that SET clause, so the count is about these withdrawals even in a database other suites
     * use. (Matched on the SET clause rather than the statement's first words: this repository's
     * row-ownership check reads a quoted statement as one this test runs.)
     */
    private void awaitWithdrawalsWaiting(int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                     WHERE datname = current_database() AND wait_event_type = 'Lock'
                       AND query LIKE '%status = ''HIDDEN'', published_at = NULL%'
                    """, Integer.class);
            if (waiting != null && waiting >= count) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("the withdrawals never both reached the held row");
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
