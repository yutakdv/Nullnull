package io.nullnull.social.application;

import io.nullnull.shared.problem.ApiException;
import io.nullnull.shared.problem.ProblemCode;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Social owns visibility; candidate commands call this inside their existing transaction. */
@Service
public class PostSourceService {
    private final FeedStore feed;

    public PostSourceService(FeedStore feed) {
        this.feed = feed;
    }

    /** A withdrawal committed first refuses the save; a save first keeps the source until commit. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requirePublished(UUID postId) {
        if (!feed.lockPublishedPost(postId)) {
            // Do not reveal whether an unpublished id exists.
            throw new ApiException(ProblemCode.NOT_FOUND, "The requested post does not exist.");
        }
    }
}
