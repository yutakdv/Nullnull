package io.nullnull.recommendation.domain;

/**
 * Bounded selection. Safety filters are never relaxed to fill the quota; fewer results are
 * returned instead (§3.1 Selector).
 *
 * @param maxResults upper bound on returned candidates, at least 1
 */
public record SelectionPolicy(int maxResults) {

    public SelectionPolicy {
        if (maxResults < 1) {
            throw new IllegalArgumentException("maxResults must be >= 1");
        }
    }
}
