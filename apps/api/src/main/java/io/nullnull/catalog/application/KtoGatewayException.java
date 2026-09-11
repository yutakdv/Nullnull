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
        KTO_PERSISTENCE_FAILED
    }

    private final Code code;

    public KtoGatewayException(Code code) {
        super(code.name(), null, false, false);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
