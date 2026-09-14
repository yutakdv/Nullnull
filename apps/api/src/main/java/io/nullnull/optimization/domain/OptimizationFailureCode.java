package io.nullnull.optimization.domain;

/**
 * Why a run ended without a preview, and whether asking again could help.
 *
 * <p>These are the {@code OptimizationFailure.code} values in docs/api/openapi.yaml minus
 * {@code APPLY_FAILED}, which belongs to a decision rather than to a run: it names an APPLY that
 * could not be written, and a run that never attempted one cannot report it. The same split is
 * written as a CHECK in V024 and amended by V031, so this vocabulary is declared in three places -
 * this enum, the column constraint and the published contract. {@code OptimizationFailureVocabularyIT}
 * compares all three in both directions. That sentence was here before the test was, which is the
 * reason the test exists: a comment naming a device is not the device.
 *
 * <p>{@code retryable} is the contract's own field and it is a statement about the CAUSE, not about
 * how the client feels: a trip that moved will not un-move, so asking again produces the same
 * failure, while a route service that did not answer may answer next time.
 */
public enum OptimizationFailureCode {

    /** The trip changed after the run froze its input; the preview would describe a trip nobody has. */
    TRIP_CHANGED(false),
    /** Evidence the run depended on was withdrawn or replaced while it was running. */
    DATA_CHANGED(false),
    /** A lock on the target refuses every move the run could propose. */
    LOCK_CONFLICT(false),
    /** No route evidence, so a proposal could not be shown to be reachable. */
    ROUTE_UNAVAILABLE(true),
    /** Nothing the run could propose was better than what the trip already says. */
    NO_IMPROVEMENT(false),
    /**
     * There was not enough evidence to judge any alternative - distinct from NO_IMPROVEMENT, which
     * says the alternatives were judged and lost.
     *
     * <p>Retryable, and for the same reason ROUTE_UNAVAILABLE is: the cause is evidence we do not
     * have yet rather than a fact about the trip. A forecast that arrives tomorrow changes the
     * answer, while a trip that moved will not un-move.
     */
    DATA_INSUFFICIENT(true);

    private final boolean retryable;

    OptimizationFailureCode(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }

    public static OptimizationFailureCode of(String value) {
        for (OptimizationFailureCode code : values()) {
            if (code.name().equals(value)) {
                return code;
            }
        }
        throw new IllegalArgumentException("unknown optimization failure code: " + value);
    }
}
