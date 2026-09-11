package io.nullnull.crowd.application;

/** Safe category-only refusal. Provider credentials and request data never enter this exception. */
public final class QuotaExhaustedException extends RuntimeException {

    public QuotaExhaustedException() {
        super("QUOTA_EXHAUSTED");
    }
}
