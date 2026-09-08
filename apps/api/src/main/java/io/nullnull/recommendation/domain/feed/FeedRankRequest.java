package io.nullnull.recommendation.domain.feed;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Mirrors {@code FeedRankRequest} of the internal contract. */
public record FeedRankRequest(Instant evaluatedAt, String locale, int sortVersion, List<FeedCandidateIn> candidates) {

    public static final int MAX_CANDIDATES = 2000;

    public FeedRankRequest {
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        Objects.requireNonNull(locale, "locale");
        if (!locale.equals("ko") && !locale.equals("en")) {
            throw new IllegalArgumentException("locale must be ko or en");
        }
        if (sortVersion < 1) {
            throw new IllegalArgumentException("sortVersion must be >= 1");
        }
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
        if (candidates.size() > MAX_CANDIDATES) {
            throw new IllegalArgumentException("at most " + MAX_CANDIDATES + " candidates per request");
        }
    }
}
