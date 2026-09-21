package io.nullnull.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.social.application.PostLockTimeoutException;
import io.nullnull.social.application.PostWithdrawalService;
import io.nullnull.social.application.PostWithdrawalService.Withdrawal;
import io.nullnull.testsupport.TestcontainersConfiguration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * BA-082-T16, T20~T22: the writer behind the {@code withdraw-post} operator task. T19 (two at once)
 * is PostWithdrawalConcurrencyIT, which needs a different lock bound.
 *
 * <p>Until this existed a post left PUBLISHED only when someone typed the UPDATE into the database,
 * and BA-082-T3 had to hand-write that statement to have a withdrawal to read. This measures the
 * production writer against the row it leaves behind; FeedIT's T3 now uses the same writer, so the
 * read side is measured against what the tool actually writes rather than against a copy of it.
 *
 * <p>Runs with application.yaml's own lock bound ({@code nullnull.posts.withdrawal-lock-timeout}),
 * which is what T20 is about.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@DisplayName("BA-082 post withdrawal")
class PostWithdrawalIT {

    @Autowired PostWithdrawalService withdrawals;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;

    private PostWithdrawalFixtures posts;

    @BeforeEach
    void fixtures() {
        posts = new PostWithdrawalFixtures(jdbc);
    }

    @AfterEach
    void removeOnlyThisTestsRows() {
        posts.removeOnlyTheseRows();
    }

    @Test
    @DisplayName("BA-082-T16 a withdrawn post leaves PUBLISHED with published_at cleared")
    void aWithdrawnPostLeavesPublished() {
        UUID post = posts.published("회수될 글");

        assertThat(withdrawals.withdraw(post)).isEqualTo(Withdrawal.WITHDRAWN);

        Map<String, Object> row = posts.row(post);
        assertThat(row.get("status")).as("the post is no longer PUBLISHED").isEqualTo("HIDDEN");
        // Asserted separately from the status. V015's shape CHECK couples the two today, and the day
        // it is relaxed - a screen that wants to say when a post was published is a plausible reason -
        // this is what notices a withdrawal that keeps the date the feed orders by.
        assertThat(row.get("published_at")).as("published_at is cleared").isNull();
    }

    @Test
    @DisplayName("a second withdrawal of the same post answers ALREADY_HIDDEN and writes nothing")
    void aSecondWithdrawalWritesNothing() {
        UUID post = posts.published("두 번 회수될 글");
        assertThat(withdrawals.withdraw(post)).isEqualTo(Withdrawal.WITHDRAWN);
        Object updatedAt = posts.row(post).get("updated_at");

        // A rerun of an approved task is the ordinary way this runs twice, and it must succeed
        // without touching the row: the operator counts ALREADY_HIDDEN as the goal already met.
        assertThat(withdrawals.withdraw(post)).isEqualTo(Withdrawal.ALREADY_HIDDEN);

        Map<String, Object> row = posts.row(post);
        assertThat(row.get("status")).isEqualTo("HIDDEN");
        assertThat(row.get("updated_at")).as("the rerun wrote nothing").isEqualTo(updatedAt);
    }

    @Test
    @DisplayName("withdrawing one post leaves every other published post as it was")
    void onlyTheNamedPostIsWithdrawn() {
        UUID withdrawn = posts.published("회수될 글");
        UUID kept = posts.published("남을 글");
        Map<String, Object> before = posts.row(kept);

        assertThat(withdrawals.withdraw(withdrawn)).isEqualTo(Withdrawal.WITHDRAWN);

        assertThat(posts.row(kept)).isEqualTo(before);
    }

    @Test
    @DisplayName("BA-082-T20 a withdrawal that cannot get the post's row within the bound fails instead of waiting")
    void aHeldRowFailsTheWithdrawalInsteadOfWaiting() throws Exception {
        UUID post = posts.published("잠긴 글");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<?> holder = posts.holdRow(pool, transactions, post, release);
            Future<Withdrawal> attempt = pool.submit(() -> withdrawals.withdraw(post));

            // The verdict is what the attempt ends with, not how long it took: the row is held until
            // this test releases it, so an attempt that ends on its own while held ended because of
            // the bound. Without a bound it would block until the release below and then withdraw -
            // the get() waits far longer than the bound so that a slow machine is not a failure.
            assertThatThrownBy(() -> attempt.get(90, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause().isInstanceOf(PostLockTimeoutException.class);

            release.countDown();
            holder.get(90, TimeUnit.SECONDS);
            assertThat(posts.row(post).get("status")).as("the failed withdrawal wrote nothing")
                    .isEqualTo("PUBLISHED");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("BA-082-T21 withdrawing a post that was never published (DRAFT) is refused")
    void aDraftIsRefused() {
        // A draft was never shown to anyone, so there is nothing to take back - and moving it to
        // HIDDEN would quietly stop a publication nobody asked to stop.
        UUID draft = posts.draft("아직 공개 안 된 글", posts.place("경복궁"));
        Map<String, Object> before = posts.row(draft);

        assertThat(withdrawals.withdraw(draft)).isEqualTo(Withdrawal.NOT_PUBLISHED);

        assertThat(posts.row(draft)).as("still the same DRAFT").isEqualTo(before);
    }

    @Test
    @DisplayName("BA-082-T22 withdrawing a post that does not exist is refused")
    void anUnknownPostIsRefused() {
        // A published post beside the missing one: a refusal that still wrote would show up here. A
        // count of rows with the unknown id could not - nothing on this path inserts.
        UUID bystander = posts.published("옆 글");
        Map<String, Object> before = posts.row(bystander);

        assertThat(withdrawals.withdraw(UUID.randomUUID())).isEqualTo(Withdrawal.NOT_FOUND);

        assertThat(posts.row(bystander)).as("the post beside it is untouched").isEqualTo(before);
    }
}
