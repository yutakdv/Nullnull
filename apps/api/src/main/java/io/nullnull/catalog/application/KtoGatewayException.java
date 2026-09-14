package io.nullnull.catalog.application;

/** A stable, provider-safe failure code for the KTO detail adapter. */
public final class KtoGatewayException extends RuntimeException {

    public enum Code {
        SOURCE_DISABLED,
        SOURCE_QUARANTINED,
        KTO_NOT_CONFIGURED,
        KTO_BASE_URL_NOT_APPROVED,
        KTO_QUOTA_EXHAUSTED,
        KTO_TRANSPORT_FAILED,
        KTO_RESPONSE_REJECTED,
        KTO_PERSISTENCE_FAILED,
        /**
         * Something this adapter did not anticipate. It exists so that the catch-all has somewhere to
         * go other than KTO_NOT_CONFIGURED: "not configured" is a diagnosis, and a diagnosis is only
         * honest when it came from checking the configuration. #227 records the two hours that cost.
         */
        KTO_INTERNAL_FAILURE
    }

    private final Code code;
    private final String failureType;

    public KtoGatewayException(Code code) {
        this(code, (String) null);
    }

    /**
     * The same stable code, plus the TYPE of what actually went wrong.
     *
     * <p>The class name and nothing else. A provider message can carry response text and an
     * application message can carry configuration, so neither is safe to pass on - but the type alone
     * separates "the provider refused us" from "this process could not start", which is the
     * distinction that was missing when a Spring startup failure read as a provider failure.
     */
    public KtoGatewayException(Code code, Class<? extends Throwable> failureType) {
        this(code, failureType == null ? null : failureType.getSimpleName());
    }

    private KtoGatewayException(Code code, String failureType) {
        super(failureType == null ? code.name() : code.name() + " (" + failureType + ")",
                null, false, false);
        this.code = code;
        this.failureType = failureType;
    }

    public Code code() {
        return code;
    }

    /** The simple class name of the underlying failure, or null when this code says it all. */
    public String failureType() {
        return failureType;
    }
}
