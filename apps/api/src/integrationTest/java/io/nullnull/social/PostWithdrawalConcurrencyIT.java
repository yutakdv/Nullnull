package io.nullnull.social;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.social.application.PostWithdrawalService;
import io.nullnull.social.application.PostWithdrawalService.Withdrawal;
import io.nullnull.testsupport.TestcontainersConfiguration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
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
 * BA-082-T19: two withdrawals of one post that arrive at once.
 *
 * <p>WHY A CLASS OF ITS OWN. The test makes both withdrawals wait on a row it holds, and releases the
 * row once it has seen them both waiting. With application.yaml's bound (PT3S) the first to wait
 * would give up if the second took three seconds to arrive, and the result would depend on how
 * busy the machine is. Here the bound is two minutes, above every wait in this class, so the only
 * thing that decides the verdict is the order of events: both waiting, then released. The waits
 * below are safety stops - they decide how long a broken run takes to fail, never whether a working
 * one passes. PostWithdrawalIT keeps the real bound and measures it (T20).
 */
@SpringBootTest(properties = "nullnull.posts.withdrawal-lock-timeout=PT2M")
@Import(TestcontainersConfiguration.class)
@DisplayName("BA-082 post withdrawal under concurrency")
class PostWithdrawalConcurrencyIT {

    /** Below the two-minute lock bound, so a withdrawal is never the one that gives up first. */
    private static final long SAFETY_STOP_SECONDS = 60;

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
    @DisplayName("BA-082-T19 of two withdrawals of one post that arrive at once, only one is WITHDRAWN")
    void twoWithdrawalsAtOnceReportOneWithdrawal() throws Exception {
        UUID post = posts.published("동시에 회수될 글");
        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<?> holder = posts.holdRow(pool, transactions, post, release);
            Future<Withdrawal> first = pool.submit(() -> withdrawals.withdraw(post));
            Future<Withdrawal> second = pool.submit(() -> withdrawals.withdraw(post));
            awaitWithdrawalsWaiting(2);
            release.countDown();
            holder.get(SAFETY_STOP_SECONDS, TimeUnit.SECONDS);

            // A status read before the write would have let both see PUBLISHED and both report it.
            assertThat(List.of(first.get(SAFETY_STOP_SECONDS, TimeUnit.SECONDS),
                    second.get(SAFETY_STOP_SECONDS, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(Withdrawal.WITHDRAWN, Withdrawal.ALREADY_HIDDEN);
            assertThat(posts.row(post).get("status")).isEqualTo("HIDDEN");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    /**
     * Waits until {@code count} withdrawal statements are blocked on a row lock. Only the withdrawal
     * writes that SET clause, so the count is about these withdrawals even in a database other suites
     * use. (Matched on the SET clause rather than the statement's first words: this repository's
     * row-ownership check reads a quoted statement as one this test runs.)
     */
    private void awaitWithdrawalsWaiting(int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SAFETY_STOP_SECONDS);
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
}
