package io.nullnull.social.domain;

/**
 * What became of an upload we signed for.
 *
 * <p>The vocabulary is V045's CHECK and the two are pinned together. REJECTED is a distinct ending
 * from CONSUMED because a refused upload must not be retryable against the same key: the object is
 * deleted where it is refused, so a second attempt would read nothing.
 */
public enum UploadIntentStatus {
    PENDING,
    CONSUMED,
    REJECTED;

    public static UploadIntentStatus of(String value) {
        for (UploadIntentStatus status : values()) {
            if (status.name().equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("unknown upload intent status");
    }
}
