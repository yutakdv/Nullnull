package io.nullnull.social.application;

import io.nullnull.social.domain.PostStatus;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Takes a published post back (BA-082-T16): the writer behind the {@code withdraw-post} operator
 * task, and the only production path to HIDDEN.
 *
 * <p>ONE STATEMENT CLOSES EVERY READER. listFeed, getPost, savePost and recordFeedFeedback all decide
 * visibility from {@code status = 'PUBLISHED'} at query time and nothing caches a post server-side,
 * so the commit of this transaction is the moment the post stops being served by the API. A cursor
 * issued before it resumes on a keyset that excludes the row (BA-082-T3).
 *
 * <p>WHAT IT DOES NOT CLOSE, NAMED. The cover of a user-authored post is an object at a public URL
 * ({@code covers/user/...}) that this cannot delete: the object store port has no delete for
 * published objects, no task role holds that permission, the bucket is versioned, and the object was
 * served with a one-year immutable cache header. A withdrawal takes the post off every page this
 * service answers; it does not make the image unreachable to someone who already has its URL.
 *
 * <p>There is no way back to PUBLISHED. {@link FeedStore#publishPost} only moves a DRAFT, and a curated
 * import leaves any existing id alone, so a withdrawn post stays withdrawn.
 */
@Service
public class PostWithdrawalService {

    private final FeedStore feed;
    private final Clock clock;

    public PostWithdrawalService(FeedStore feed, Clock clock) {
        this.feed = feed;
        this.clock = clock;
    }

    /**
     * Withdraws the post if it is published.
     *
     * <p>The row is locked before its status is read, so the answer describes the row this
     * transaction wrote or left alone - not a status another transaction changed in between.
     */
    @Transactional
    public Withdrawal withdraw(UUID postId) {
        Optional<PostStatus> status = feed.lockPostStatus(postId);
        if (status.isEmpty()) {
            return Withdrawal.NOT_FOUND;
        }
        return switch (status.get()) {
            case PUBLISHED -> {
                feed.withdrawPublished(postId, clock.instant());
                yield Withdrawal.WITHDRAWN;
            }
            // Already where a withdrawal leaves it, so a rerun of an approved task succeeds and writes
            // nothing.
            case HIDDEN -> Withdrawal.ALREADY_HIDDEN;
            // Never shown to anyone, so there is nothing to take back, and hiding it would stop a
            // publication nobody asked to stop.
            case DRAFT -> Withdrawal.NOT_PUBLISHED;
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
