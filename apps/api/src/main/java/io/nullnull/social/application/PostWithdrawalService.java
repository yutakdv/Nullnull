package io.nullnull.social.application;

import io.nullnull.identity.application.LockWaitLimit;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Takes a published post back (BA-082-T16): the writer behind the {@code withdraw-post} operator
 * task, and the only production path to HIDDEN.
 *
 * <p>WHAT THE COMMIT CLOSES. Every public projection of a post's content - listFeed, getPost, and the
 * checks savePost and recordFeedFeedback make first - decides visibility from
 * {@code status = 'PUBLISHED'} in its own query, and nothing caches a post server-side. So a request
 * whose query runs after this transaction commits does not get the post, and a feed cursor issued
 * before it resumes on a keyset that excludes the row (BA-082-T3). A response already assembled
 * from a read that ran before the commit is not recalled; nothing can recall bytes already sent.
 *
 * <p>WHAT IT DOES NOT CLOSE, NAMED. The cover of a user-authored post is an object at a public URL
 * ({@code covers/user/...}) that this cannot delete: the object store port has no delete for
 * published objects, no task role holds that permission, the bucket is versioned, and the object
 * was served with a one-year immutable cache header - so browsers, and the web app's service
 * worker, keep what they already fetched. The web app's in-memory query cache keeps a page it
 * already has until it refetches. A trip candidate saved from the post keeps the post's id as its
 * source. A withdrawal takes the post off every page the API answers from now on; it does not make
 * the image unreachable to someone who already has its URL.
 *
 * <p>There is no way back to PUBLISHED. {@link FeedStore#publishPost} only moves a DRAFT, and a
 * curated import leaves any existing id alone, so a withdrawn post stays withdrawn.
 */
@Service
public class PostWithdrawalService {

    /**
     * How long a withdrawal waits for a post row another transaction holds. The same bound the
     * repository's other lock waits use ({@code APP_IDEMPOTENCY_LOCK_TIMEOUT} and
     * {@code NULLNULL_JOB_LOCK_TIMEOUT} both default to PT3S): without one PostgreSQL waits forever,
     * and the operator task would hold the deployment lock until the operator gave up on it.
     */
    static final Duration LOCK_WAIT = Duration.ofSeconds(3);

    private final FeedStore feed;
    private final LockWaitLimit lockWaits;
    private final Clock clock;

    public PostWithdrawalService(FeedStore feed, LockWaitLimit lockWaits, Clock clock) {
        this.feed = feed;
        this.lockWaits = lockWaits;
        this.clock = clock;
    }

    /**
     * Withdraws the post if it is published.
     *
     * <p>WITHDRAWN comes from the row the UPDATE changed, never from a status read beforehand: a
     * status read and a write are two statements, and two withdrawals running at once would both
     * read PUBLISHED. Only when nothing changed is the status read, to say why.
     */
    @Transactional
    public Withdrawal withdraw(UUID postId) {
        lockWaits.applyToCurrentTransaction(LOCK_WAIT);
        if (feed.withdrawIfPublished(postId, clock.instant()) == 1) {
            return Withdrawal.WITHDRAWN;
        }
        return switch (feed.postStatus(postId).orElse(null)) {
            case null -> Withdrawal.NOT_FOUND;
            // Already where a withdrawal leaves it, so a rerun of an approved task succeeds and
            // changes nothing.
            case HIDDEN -> Withdrawal.ALREADY_HIDDEN;
            // Never shown to anyone, so there is nothing to take back, and hiding it would stop a
            // publication nobody asked to stop.
            case DRAFT -> Withdrawal.NOT_PUBLISHED;
            // Not PUBLISHED when the UPDATE looked and PUBLISHED now: a draft was published in
            // between. The owner approved withdrawing a published post, so this says so rather
            // than guessing; running the task again withdraws it.
            case PUBLISHED -> throw new IllegalStateException(
                    "the post was published while it was being withdrawn; run the withdrawal again");
        };
    }

    /** What a withdrawal did. The first two leave the post withdrawn; the last two refuse. */
    public enum Withdrawal {
        WITHDRAWN,
        ALREADY_HIDDEN,
        NOT_PUBLISHED,
        NOT_FOUND
    }
}
