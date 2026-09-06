package io.nullnull.recommendation.application;

/** The recommendation service could not serve a valid answer. Callers apply the documented fallback; never a silent default. */
public class RecommendationUnavailableException extends RuntimeException {

    private final boolean retryable;

    public RecommendationUnavailableException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
