package io.nullnull.social.domain;

/**
 * A post's publication state. Only PUBLISHED reaches the feed; HIDDEN is a curator taking one back
 * without deleting it, which is why hiding must not renumber anything for readers mid-page.
 */
public enum PostStatus {
    DRAFT,
    PUBLISHED,
    HIDDEN;

    public static PostStatus of(String value) {
        for (PostStatus status : values()) {
            if (status.name().equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("unknown post status");
    }
}
